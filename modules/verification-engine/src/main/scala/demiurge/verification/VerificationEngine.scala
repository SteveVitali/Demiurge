package demiurge.verification

import java.time.Instant
import java.util.UUID

import demiurge.model._
import demiurge.inference.InferenceService

// Phase 4+6: VerificationEngine — orchestrates verifier generation, execution, and verdict aggregation.
// Phase 6: Supports optional WorkerProcessManager for browser verifier dispatch.
// Browser verifiers produce observations and evidenceRefs from worker artifacts.
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

  def runVerification(
    runId: String,
    attemptNumber: Int,
    graph: RequirementGraph,
    browserExecutor: Option[BrowserVerifierExecutor] = None,
    inferenceService: Option[InferenceService] = None,
    resolvedConfig: Option[ResolvedConfig] = None,
    storageStatePath: Option[String] = None,
  ): VerificationResult = {
    val verifiers = VerifierGenerator.generate(graph)
    val outcomes = verifiers.map { v =>
      val startTime = System.currentTimeMillis()
      v match {
        // Phase 6+E: Browser verifiers dispatched through worker, with selector discovery
        case bv: BrowserFlowVerifier =>
          browserExecutor match {
            case Some(executor) =>
              // Phase E: Discover missing selectors before execution
              val discoveredBv = discoverSelectorsIfNeeded(bv, executor, runId, inferenceService, resolvedConfig)
              // Gap 4: Apply storage state path from auth bootstrap if available
              val resolvedBv = storageStatePath match {
                case Some(path) if discoveredBv.storageStatePath.isEmpty =>
                  discoveredBv.copy(storageStatePath = Some(path))
                case _ => discoveredBv
              }
              val browserResult = executor.execute(resolvedBv)
              val durationMs = System.currentTimeMillis() - startTime
              (v, browserResult.outcome, durationMs, browserResult.observations, browserResult.artifactRefs)
            case None =>
              val durationMs = System.currentTimeMillis() - startTime
              (v, VerifierOutcome.Error("No browser executor available for BrowserFlowVerifier"): VerifierOutcome, durationMs, List.empty[Observation], List.empty[String])
          }
        case _ =>
          val outcome = VerifierExecutor.execute(v)
          val durationMs = System.currentTimeMillis() - startTime
          (v, outcome, durationMs, List.empty[Observation], List.empty[String])
      }
    }

    val verdicts = outcomes.map { case (verifier, outcome, durationMs, observations, evidenceRefs) =>
      val (status, failureClass, failureMessage) = outcome match {
        case VerifierOutcome.Passed =>
          (VerdictStatus.Pass, None, None)
        case VerifierOutcome.Failed(msg) =>
          val fc = verifier match {
            case _: BrowserFlowVerifier => Some(FailureClass.FrontendRenderError)
            case _ => Some(FailureClass.UnknownFailure)
          }
          (VerdictStatus.Fail, fc, Some(msg))
        case VerifierOutcome.Error(msg) =>
          (VerdictStatus.Fail, Some(FailureClass.UnknownFailure), Some(msg))
        case VerifierOutcome.TimedOut =>
          val fc = verifier match {
            case _: BrowserFlowVerifier => Some(FailureClass.BrowserTimingFlake)
            case _ => None
          }
          (VerdictStatus.Timeout, fc, Some("Verifier timed out"))
      }

      RequirementVerdict(
        verdictId = UUID.randomUUID().toString,
        runId = runId,
        attemptNumber = attemptNumber,
        requirementId = verifier.requirementId,
        verifierId = verifier.id,
        status = status,
        executionDurationMs = durationMs,
        retryCount = 0,
        observations = observations,
        evidenceRefs = evidenceRefs,
        failureClass = failureClass,
        failureMessage = failureMessage,
        suggestedRerunScope = None,
        confidence = 1.0,
        producedAt = Instant.now(),
      )
    }

    val outcomeList = outcomes.map { case (v, o, _, _, _) => (v.id, o) }
    val aggregate = VerdictAggregator.aggregate(outcomeList)

    VerificationResult(verdicts = verdicts, aggregate = aggregate)
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
