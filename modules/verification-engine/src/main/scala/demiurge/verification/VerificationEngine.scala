package demiurge.verification

import java.time.Instant
import java.util.UUID
import java.util.concurrent.{Executors, Callable, Future => JFuture, TimeUnit}

import demiurge.model._
import demiurge.inference.InferenceService

// Spec §12.3–12.4: VerificationEngine — layer-based verification with parallel groups,
// blocked detection, flake detection, and retry count tracking.
// Layers 0–4 execute in order. Within each layer, parallel-safe verifiers run concurrently.
// Blocked verifiers (hard dependency not yet passed) are skipped with VerdictStatus.Blocked.
object VerificationEngine {

  case class VerificationResult(
    verdicts:  List[RequirementVerdict],
    aggregate: VerdictAggregator.AggregateResult,
  )

  // Phase 6: Browser verifier executor — injected by orchestrator when worker is available
  trait BrowserVerifierExecutor {
    def execute(verifier: BrowserFlowVerifier): BrowserVerifierResult

    /** Phase E: Capture a page snapshot (DOM/accessibility tree) for selector discovery. */
    def capturePageSnapshot(url: String): Either[String, String] = Left("capturePageSnapshot not implemented")
  }

  // Design: Agentic Browser UI Verification §7.1 — executor for agent-based browser verification
  trait AgentBrowserExecutor {
    def execute(verifier: AgentBrowserVerifier): BrowserVerifierResult
  }

  def runVerification(
    runId: String,
    attemptNumber: Int,
    graph: RequirementGraph,
    browserExecutor: Option[BrowserVerifierExecutor] = None,
    inferenceService: Option[InferenceService] = None,
    resolvedConfig: Option[ResolvedConfig] = None,
    authContext: Option[AuthContext] = None,
    agentBrowserExecutor: Option[AgentBrowserExecutor] = None,
  ): VerificationResult = {
    val plan = VerificationPlanner.buildPlan(graph)
    val verifierMap = buildVerifierMap(graph)
    val specMap = graph.nodes.flatMap(_.verifiers).map(s => s.verifierId -> s).toMap

    // Accumulate verdicts per requirement as layers execute
    val reqVerdicts = scala.collection.mutable.Map[String, VerdictStatus]()
    val allVerdicts = scala.collection.mutable.ListBuffer[RequirementVerdict]()
    // Spec §4.3: Track requirements that have stopOnFailure=true and already failed
    val stoppedRequirements = scala.collection.mutable.Set[String]()
    val nodeMap = graph.nodes.map(n => n.requirementId -> n).toMap

    // Execute layers in order 0..4
    for (layer <- plan.layers if layer.verifiers.nonEmpty) {
      val layerGroups = plan.parallelGroups.filter(_.layerIndex == layer.layerIndex)

      for (group <- layerGroups) {
        val groupSpecs = group.verifierIds.flatMap(specMap.get)
        val groupVerifiers = group.verifierIds.flatMap(verifierMap.get)

        if (group.verifierIds.size > 1) {
          // Parallel execution
          // Note: stopOnFailure only blocks verifiers in subsequent groups/layers for the
          // same requirement. Within a single parallel group, all verifiers execute concurrently
          // and stopOnFailure is checked at submission time (before results are collected).
          val maxParallel = 4
          val pool = Executors.newFixedThreadPool(group.verifierIds.size.min(maxParallel))
          try {
            val futuresWithMeta: List[(JFuture[RequirementVerdict], VerifierSpec, Verifier)] =
              groupSpecs.zip(groupVerifiers).map { case (spec, verifier) =>
                val future = pool.submit(new Callable[RequirementVerdict] {
                  override def call(): RequirementVerdict = {
                    // Spec §4.3: stopOnFailure — skip if requirement already failed
                    if (stoppedRequirements.contains(verifier.requirementId)) {
                      return makeBlockedVerdict(runId, attemptNumber, verifier, "stopped_on_failure")
                    }
                    executeOneVerifier(
                      spec, verifier, runId, attemptNumber, graph, reqVerdicts.toMap,
                      browserExecutor, inferenceService, resolvedConfig, authContext,
                      agentBrowserExecutor,
                    )
                  }
                })
                (future, spec, verifier)
              }
            futuresWithMeta.foreach { case (f, spec, verifier) =>
              val verdict = try {
                f.get(120, TimeUnit.SECONDS)
              } catch {
                case _: Exception =>
                  f.cancel(true)
                  RequirementVerdict(
                    verdictId = UUID.randomUUID().toString,
                    runId = runId,
                    attemptNumber = attemptNumber,
                    requirementId = verifier.requirementId,
                    verifierId = verifier.id,
                    status = VerdictStatus.Timeout,
                    executionDurationMs = 120000,
                    retryCount = 0,
                    observations = Nil,
                    evidenceRefs = Nil,
                    failureClass = None,
                    failureMessage = Some("Parallel verifier timed out after 120s"),
                    suggestedRerunScope = None,
                    confidence = 1.0,
                    producedAt = Instant.now(),
                  )
              }
              reqVerdicts(verdict.requirementId) = verdict.status
              allVerdicts += verdict
              // Spec §4.3: Mark requirement as stopped if stopOnFailure and verdict is Fail/Timeout
              checkStopOnFailure(verdict, nodeMap, stoppedRequirements)
            }
          } finally {
            pool.shutdownNow()
          }
        } else {
          // Sequential execution (single verifier or sequential group)
          groupSpecs.zip(groupVerifiers).foreach { case (spec, verifier) =>
            // Spec §4.3: stopOnFailure — skip if requirement already failed
            val verdict = if (stoppedRequirements.contains(verifier.requirementId)) {
              makeBlockedVerdict(runId, attemptNumber, verifier, "stopped_on_failure")
            } else {
              executeOneVerifier(
                spec, verifier, runId, attemptNumber, graph, reqVerdicts.toMap,
                browserExecutor, inferenceService, resolvedConfig, authContext,
                agentBrowserExecutor,
              )
            }
            reqVerdicts(verdict.requirementId) = verdict.status
            allVerdicts += verdict
            // Spec §4.3: Mark requirement as stopped if stopOnFailure and verdict is Fail/Timeout
            checkStopOnFailure(verdict, nodeMap, stoppedRequirements)
          }
        }
      }
    }

    val verdicts = allVerdicts.toList
    // Spec §4.3–4.4: Use priority-aware aggregation with requirement graph
    val aggregate = VerdictAggregator.aggregateWithGraph(verdicts, graph)

    VerificationResult(verdicts = verdicts, aggregate = aggregate)
  }

  /**
   * Execute a single verifier with blocked detection, retry, and flake detection.
   * Spec §12.4: Before executing, check hard dependency verdicts.
   * If blocked, return VerdictStatus.Blocked immediately.
   * On retry success after initial failure, mark as Flake.
   */
  private def executeOneVerifier(
    spec:             VerifierSpec,
    verifier:         Verifier,
    runId:            String,
    attemptNumber:    Int,
    graph:            RequirementGraph,
    currentVerdicts:  Map[String, VerdictStatus],
    browserExecutor:  Option[BrowserVerifierExecutor],
    inferenceService: Option[InferenceService],
    resolvedConfig:   Option[ResolvedConfig],
    authContext:      Option[AuthContext],
    agentBrowserExecutor: Option[AgentBrowserExecutor] = None,
  ): RequirementVerdict = {
    // Spec §12.4: Blocked detection
    if (VerificationPlanner.isBlocked(spec, graph, currentVerdicts)) {
      return RequirementVerdict(
        verdictId = UUID.randomUUID().toString,
        runId = runId,
        attemptNumber = attemptNumber,
        requirementId = verifier.requirementId,
        verifierId = verifier.id,
        status = VerdictStatus.Blocked,
        executionDurationMs = 0,
        retryCount = 0,
        observations = Nil,
        evidenceRefs = Nil,
        failureClass = None,
        failureMessage = Some("Blocked: hard dependency not yet passed"),
        suggestedRerunScope = None,
        confidence = 1.0,
        producedAt = Instant.now(),
      )
    }

    val startTime = System.currentTimeMillis()
    val firstResult = executeSingleVerifier(verifier, browserExecutor, inferenceService, resolvedConfig, authContext, runId, agentBrowserExecutor)

    var finalOutcome = firstResult.outcome
    var finalObs = firstResult.observations
    var finalArtifacts = firstResult.artifactRefs
    var retryCount = 0
    var isFlake = false

    // Retry logic with flake detection
    if (finalOutcome != VerifierOutcome.Passed && spec.maxRetries > 0) {
      var attempts = 0
      while (attempts < spec.maxRetries && finalOutcome != VerifierOutcome.Passed) {
        attempts += 1
        retryCount += 1
        if (spec.retryDelayMs > 0) Thread.sleep(spec.retryDelayMs.toLong)
        val retryResult = executeSingleVerifier(verifier, browserExecutor, inferenceService, resolvedConfig, authContext, runId, agentBrowserExecutor)
        finalOutcome = retryResult.outcome
        finalObs = retryResult.observations
        finalArtifacts = retryResult.artifactRefs
      }
      // Spec: If failed initially but passed on retry → Flake
      if (finalOutcome == VerifierOutcome.Passed && retryCount > 0) {
        isFlake = true
      }
    }

    val durationMs = System.currentTimeMillis() - startTime
    val (status, failureClass, failureMessage) = outcomeToVerdict(finalOutcome, verifier, isFlake)

    RequirementVerdict(
      verdictId = UUID.randomUUID().toString,
      runId = runId,
      attemptNumber = attemptNumber,
      requirementId = verifier.requirementId,
      verifierId = verifier.id,
      status = status,
      executionDurationMs = durationMs,
      retryCount = retryCount,
      observations = finalObs,
      evidenceRefs = finalArtifacts,
      failureClass = failureClass,
      failureMessage = failureMessage,
      suggestedRerunScope = None,
      confidence = if (isFlake) 0.5 else 1.0,
      producedAt = Instant.now(),
    )
  }

  /** Execute a single verifier attempt (no retry). */
  private case class SingleResult(
    outcome:      VerifierOutcome,
    observations: List[Observation],
    artifactRefs: List[String],
  )

  private def executeSingleVerifier(
    verifier:         Verifier,
    browserExecutor:  Option[BrowserVerifierExecutor],
    inferenceService: Option[InferenceService],
    resolvedConfig:   Option[ResolvedConfig],
    authContext:      Option[AuthContext],
    runId:            String,
    agentBrowserExecutor: Option[AgentBrowserExecutor] = None,
  ): SingleResult = {
    verifier match {
      case abv: AgentBrowserVerifier =>
        agentBrowserExecutor match {
          case Some(executor) =>
            val result = executor.execute(abv)
            SingleResult(result.outcome, result.observations, result.artifactRefs)
          case None =>
            SingleResult(VerifierOutcome.Error("No agent browser executor available"), Nil, Nil)
        }
      case bv: BrowserFlowVerifier =>
        browserExecutor match {
          case Some(executor) =>
            val discoveredBv = discoverSelectorsIfNeeded(bv, executor, runId, inferenceService, resolvedConfig)
            val resolvedBv = authContext.flatMap(_.storageStatePath) match {
              case Some(path) if discoveredBv.storageStatePath.isEmpty =>
                discoveredBv.copy(storageStatePath = Some(path))
              case _ => discoveredBv
            }
            val browserResult = executor.execute(resolvedBv)
            SingleResult(browserResult.outcome, browserResult.observations, browserResult.artifactRefs)
          case None =>
            SingleResult(VerifierOutcome.Error("No browser executor available for BrowserFlowVerifier"), Nil, Nil)
        }
      case _ =>
        val outcome = VerifierExecutor.executeOnce(verifier)
        SingleResult(outcome, Nil, Nil)
    }
  }

  /** Map outcome + flake status to verdict fields. */
  private def outcomeToVerdict(
    outcome: VerifierOutcome,
    verifier: Verifier,
    isFlake: Boolean,
  ): (VerdictStatus, Option[FailureClass], Option[String]) = {
    if (isFlake && outcome == VerifierOutcome.Passed) {
      (VerdictStatus.Flake, Some(FailureClass.SuspectedNondeterminism), Some("Passed on retry (flake)"))
    } else outcome match {
      case VerifierOutcome.Passed =>
        (VerdictStatus.Pass, None, None)
      case VerifierOutcome.Failed(msg) =>
        val fc = verifier match {
          case _: BrowserFlowVerifier  => Some(FailureClass.FrontendRenderError)
          case _: AgentBrowserVerifier => Some(FailureClass.FrontendRenderError)
          case _ => Some(FailureClass.UnknownFailure)
        }
        (VerdictStatus.Fail, fc, Some(msg))
      case VerifierOutcome.Error(msg) =>
        (VerdictStatus.Fail, Some(FailureClass.UnknownFailure), Some(msg))
      case VerifierOutcome.TimedOut =>
        val fc = verifier match {
          case _: BrowserFlowVerifier  => Some(FailureClass.BrowserTimingFlake)
          case _: AgentBrowserVerifier => Some(FailureClass.BrowserTimingFlake)
          case _ => None
        }
        (VerdictStatus.Timeout, fc, Some("Verifier timed out"))
    }
  }

  /** Spec §4.3: Check if a verdict triggers stopOnFailure for its requirement. */
  private def checkStopOnFailure(
    verdict: RequirementVerdict,
    nodeMap: Map[String, RequirementNode],
    stoppedRequirements: scala.collection.mutable.Set[String],
  ): Unit = {
    if (verdict.status == VerdictStatus.Fail || verdict.status == VerdictStatus.Timeout) {
      nodeMap.get(verdict.requirementId).foreach { node =>
        if (node.stopOnFailure) {
          stoppedRequirements += verdict.requirementId
        }
      }
    }
  }

  /** Create a Blocked verdict for a verifier skipped due to stopOnFailure. */
  private def makeBlockedVerdict(
    runId: String,
    attemptNumber: Int,
    verifier: Verifier,
    reason: String,
  ): RequirementVerdict = {
    RequirementVerdict(
      verdictId = UUID.randomUUID().toString,
      runId = runId,
      attemptNumber = attemptNumber,
      requirementId = verifier.requirementId,
      verifierId = verifier.id,
      status = VerdictStatus.Blocked,
      executionDurationMs = 0,
      retryCount = 0,
      observations = Nil,
      evidenceRefs = Nil,
      failureClass = None,
      failureMessage = Some(s"Blocked: $reason"),
      suggestedRerunScope = None,
      confidence = 1.0,
      producedAt = Instant.now(),
    )
  }

  /** Build verifier ID → Verifier map. */
  private def buildVerifierMap(graph: RequirementGraph): Map[String, Verifier] = {
    VerifierGenerator.generate(graph).map(v => v.id -> v).toMap
  }

  /** Phase E: Discover missing selectors via LLM before browser flow execution. */
  private def discoverSelectorsIfNeeded(
    bv: BrowserFlowVerifier,
    executor: BrowserVerifierExecutor,
    runId: String,
    inferenceService: Option[InferenceService],
    resolvedConfig: Option[ResolvedConfig],
  ): BrowserFlowVerifier = {
    (inferenceService, resolvedConfig) match {
      case (Some(svc), Some(config)) =>
        // Build a BrowserFlowVerifierSpec from the verifier's fields to check for missing selectors
        val spec = demiurge.model.BrowserFlowVerifierSpec(
          entryUrl = bv.entryUrl,
          selectorMapRef = None,
          entryConditions = Nil,
          actions = bv.actions,
          assertions = bv.assertions,
          artifactPlan = bv.artifactPlan,
          cleanup = Nil,
        )
        if (!SelectorDiscovery.needsDiscovery(spec)) return bv

        // Capture page snapshot from the worker
        executor.capturePageSnapshot(bv.entryUrl) match {
          case Right(snapshot) =>
            val updatedSpec = SelectorDiscovery.discoverSelectors(spec, snapshot, runId, config, svc)
            bv.copy(
              actions = updatedSpec.actions,
              assertions = updatedSpec.assertions,
            )
          case Left(err) =>
            System.err.println(s"[SelectorDiscovery] Page snapshot failed for ${bv.entryUrl}: $err")
            bv
        }
      case _ => bv // no inference available
    }
  }
}
