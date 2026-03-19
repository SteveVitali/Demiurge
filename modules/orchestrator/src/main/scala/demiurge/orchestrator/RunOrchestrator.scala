package demiurge.orchestrator

import java.sql.Connection

import demiurge.model._
import demiurge.inspector.RepoInspector
import demiurge.compiler.RequirementCompiler
import demiurge.planner.EnvironmentPlanner
import demiurge.runtime.{RuntimeSupervisor, EnvironmentHealthMonitor}
import demiurge.verification.VerificationEngine
import demiurge.persistence._
import demiurge.repair._
import demiurge.config.{ConfigResolver, ConfigResolverImpl}
import demiurge.inference.InferenceService
import demiurge.worker.WorkerProcessManager
import demiurge.agent.{AgentBackend, AgentConfig, AgentResult, AgentCompleted, AgentFailed, AgentTimeout, AgentBudgetExceeded, AgentToolRpcHandlers, AgentBrowserExecutorImpl}
import demiurge.license.{CredentialStore, UsageReporter, UsageReport, UsageLimitExceeded, UsageReportError}

// Spec §4.1: Synchronous orchestrator loop.
// Executes the path: Created → InspectingRepo → CompilingRequirements →
// PlanningEnvironment → BootstrappingEnvironment → SeedingFixtures → ReadyToVerify →
// [Verification + Repair Loop up to maxAttempts]:
//   Verifying → (Pass → Succeeded) | (Fail → AnalyzingFailure → PlanningRepair →
//     Repairing → SoftResettingEnvironment → ReadyToVerify → next attempt)
// All transitions enforce persist-before-side-effects (Spec §4.1).
// Gap 6: Supports resume via optional resumeFromStatus — skips completed phases
// and restores persisted data from prior execution.
object RunOrchestrator {

  // Phase ordering for resume skip logic. Phases before the resume point are skipped.
  private val phaseOrder: List[RunStatus] = List(
    RunStatus.Created,
    RunStatus.InspectingRepo,
    RunStatus.CompilingRequirements,
    RunStatus.PlanningFeature,
    RunStatus.GeneratingCode,
    RunStatus.PlanningEnvironment,
    RunStatus.BootstrappingEnvironment,
    RunStatus.SeedingFixtures,
    RunStatus.BootstrappingAuth,
    RunStatus.ReadyToVerify,
  )

  private def shouldExecute(phase: RunStatus, startPhase: RunStatus): Boolean = {
    val startIdx = phaseOrder.indexOf(startPhase)
    val phaseIdx = phaseOrder.indexOf(phase)
    // If phase or startPhase not in list, default to executing
    startIdx < 0 || phaseIdx < 0 || phaseIdx >= startIdx
  }

  /**
   * Execute the orchestration path with bounded multi-attempt repair loop.
   * Iterates up to maxAttempts (default 5, build mode 8).
   * Returns the final TaskRun state after reaching a terminal status.
   *
   * Gap 6: When resumeFromStatus is provided, skips all phases before the
   * specified status and loads persisted data from prior execution.
   */
  // Phase 6: Optional browserExecutor for dispatching BrowserFlowVerifiers through the worker.
  // Worker persists across attempts. Orchestrator spawns worker only when browser verification is needed.
  def execute(
    ctx: RunContext,
    inspector: RepoInspector,
    compiler: RequirementCompiler,
    planner: EnvironmentPlanner,
    supervisor: RuntimeSupervisor,
    repairBackend: Option[RepairBackend] = None,
    browserExecutor: Option[VerificationEngine.BrowserVerifierExecutor] = None,
    configResolver: Option[ConfigResolver] = None,
    inferenceService: Option[InferenceService] = None,
    workerManager: Option[WorkerProcessManager] = None,
    resumeFromStatus: Option[RunStatus] = None,
    agentBackend: Option[AgentBackend] = None,
    agentConfig: AgentConfig = AgentConfig.Default,
  ): TaskRun = {
    // Register signal handler for interruption persistence (Spec §4.4)
    SignalHandler.register(ctx, ctx.repoRoot)

    var currentRun = ctx.run
    var currentCtx = ctx
    implicit val conn: Connection = ctx.conn

    // --- Gap 6: Determine start phase and load resume data ---
    val startPhase = resumeFromStatus.getOrElse(RunStatus.Created)

    // Hoist all mutable result variables so resume can pre-populate them
    var inspectionResult: Option[RepoInspectionReport] = None
    var resolvedConfig: Option[ResolvedConfig] = None
    var requirementResult: Option[RequirementGraph] = None
    var planResult: Option[RuntimePlan] = None
    var patchHistory: List[PatchProposal] = Nil
    var attemptNumber = 1
    var authContext: Option[AuthContext] = None
    // Spec 05 §5.2: Accumulate token usage across all agent interactions for cloud reporting
    var accumulatedInputTokens: Long = 0
    var accumulatedOutputTokens: Long = 0

    if (resumeFromStatus.isDefined) {
      val data = ResumeDataLoader.load(currentRun.runId)
      inspectionResult = data.inspection
      requirementResult = data.graph
      planResult = data.plan
      patchHistory = data.patchHistory
      attemptNumber = data.lastAttemptNumber + 1
      // Re-resolve config if we have a config resolver and inspection
      resolvedConfig = for {
        cr <- configResolver
        inspection <- inspectionResult
      } yield cr.resolve(
        repoPath = currentCtx.worktreePath,
        taskText = currentRun.taskText,
        changedFiles = currentRun.changedFiles,
        inspection = inspection,
        inferenceService = inferenceService,
      )
    }

    // --- Spec 05 §3.1: Increment license usage (run count) ---
    // Count the run when it transitions past Created (first real work state).
    // Resumed runs are NOT counted again (already counted on initial start).
    val isResume = resumeFromStatus.isDefined
    if (!isResume && shouldExecute(RunStatus.InspectingRepo, startPhase)) {
      try {
        val licenseKey = CredentialStore.loadCredentials().map(_.licenseKey).getOrElse("")
        val fingerprint = CredentialStore.getMachineFingerprint()
        UsageReporter.incrementRunCount(licenseKey, fingerprint) match {
          case Left(_: UsageLimitExceeded) =>
            System.err.println(s"[demiurge] Run limit reached: uses exceeded maxUses this period")
            currentRun = RunTransitionManager.transitionToTerminal(
              currentCtx, RunStatus.Exhausted,
              summary = Some("Run limit reached — upgrade your plan at https://demiurge.dev/pricing"),
            )
            return currentRun
          case Left(UsageReportError(msg)) =>
            // Non-fatal: log warning but continue (offline grace)
            System.err.println(s"[demiurge] Could not report usage: $msg")
          case Right(UsageReport(uses, maxUses)) =>
            System.err.println(s"[demiurge] Run $uses/$maxUses this period")
            // Spec 05 §6.3: Approaching limit warning (≥80%)
            if (maxUses > 0 && uses.toDouble / maxUses >= 0.8) {
              val pct = (uses.toDouble / maxUses * 100).toInt
              System.err.println(s"[demiurge] Warning: $uses/$maxUses runs used this period ($pct%).")
            }
          case _ => // Shouldn't happen but handle gracefully
        }
      } catch {
        case e: Exception =>
          // Non-fatal: usage metering failure should never block a run
          System.err.println(s"[demiurge] Usage metering skipped: ${e.getMessage}")
      }
    }

    // --- Transition: Created → InspectingRepo ---
    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
    if (shouldExecute(RunStatus.InspectingRepo, startPhase)) {

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.InspectingRepo,
        sideEffect = { updatedCtx =>
          val report = inspector.inspect(
            currentRun.runId,
            currentCtx.worktreePath,
            currentRun.changedFiles,
          )
          // Persist inspection report (Spec §7.2)
          RepoInspectionReportRepo.insert(report)
          inspectionResult = Some(report)

          // Phase E: Resolve config (explicit YAML → cached → inferred)
          resolvedConfig = configResolver.map(_.resolve(
            repoPath = currentCtx.worktreePath,
            taskText = currentRun.taskText,
            changedFiles = currentRun.changedFiles,
            inspection = report,
            inferenceService = inferenceService,
          ))
          // Cache for future runs
          resolvedConfig.foreach(cfg =>
            ConfigResolverImpl.cacheResolvedConfig(currentCtx.repoRoot, cfg))
        },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)
    }

    // --- Transition: InspectingRepo → CompilingRequirements ---
    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
    if (shouldExecute(RunStatus.CompilingRequirements, startPhase)) {

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.CompilingRequirements,
        sideEffect = { updatedCtx =>
          // Phase E: Use compileWithInference when available
          val graph = compiler.compileWithInference(
            currentRun.runId,
            inspectionResult.get,
            currentRun.taskText,
            resolvedConfig = resolvedConfig,
            inferenceService = inferenceService,
          )
          RequirementGraphRepo.insert(graph)
          requirementResult = Some(graph)
        },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)
    }

    // --- Build Mode: PlanningFeature → GeneratingCode ---
    if (currentRun.runMode == RunMode.Build && repairBackend.isDefined &&
        shouldExecute(RunStatus.PlanningFeature, startPhase)) {
      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

      var featurePlan: Option[FeaturePlan] = None

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.PlanningFeature,
        sideEffect = { _ =>
          featurePlan = Some(BuildPhaseManager.planFeature(
            currentRun.runId,
            currentRun.taskText,
            inspectionResult.get,
            requirementResult.get,
            inferenceService,
            resolvedConfig,
          ))
        },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)

      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

      var codeGenOutcome: Option[RepairExecutor.RepairOutcome] = None

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.GeneratingCode,
        sideEffect = { _ =>
          codeGenOutcome = Some(BuildPhaseManager.generateCode(
            currentCtx,
            repairBackend.get,
            currentRun.taskText,
            featurePlan.get,
            inspectionResult.get,
            requirementResult.get,
            None, // planResult not yet computed — PlanningEnvironment happens after
          ))
        },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)

      // Handle code generation outcome
      codeGenOutcome.get match {
        case RepairExecutor.RepairApplied(packet, proposal, filesChanged) =>
          RepairManager.persistFailurePacket(packet)
          RepairManager.persistPatchRecord(proposal)
          patchHistory = patchHistory :+ proposal
          // Continue to PlanningEnvironment → verification loop

        case RepairExecutor.RepairRejected(packet, reason) =>
          RepairManager.persistFailurePacket(packet)
          currentRun = RunTransitionManager.transitionToTerminal(
            currentCtx,
            RunStatus.Exhausted,
            summary = Some(s"Code generation failed: $reason"),
          )
          return currentRun
      }
    }

    // --- Transition: → PlanningEnvironment ---
    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
    if (shouldExecute(RunStatus.PlanningEnvironment, startPhase)) {

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.PlanningEnvironment,
        sideEffect = { updatedCtx =>
          val rawPlan = planner.plan(
            currentRun.runId,
            inspectionResult.get,
            requirementResult.get,
          )
          // Remap service CWDs from repoRoot to worktreePath so the server
          // runs from the worktree where patches are applied (Bug fix: §8.2)
          val plan = remapPlanCwds(rawPlan, currentCtx.repoRoot, currentCtx.worktreePath)
          // Persist runtime plan (Spec §7.2)
          RuntimePlanRepo.insert(plan)
          planResult = Some(plan)
        },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)
    }

    // --- Transition: PlanningEnvironment → BootstrappingEnvironment ---
    // Spec §2.1: Retry boot up to max_env_boot_retries (default 2) on failure.
    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
    if (shouldExecute(RunStatus.BootstrappingEnvironment, startPhase)) {
      val maxEnvBootRetries = 3
      var bootAttempt = 0
      var bootSucceeded = false

      while (bootAttempt <= maxEnvBootRetries && !bootSucceeded) {
        if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

        var bootResult: Option[RuntimeSupervisor.BootResult] = None

        currentRun = RunTransitionManager.transition(
          currentCtx,
          RunStatus.BootstrappingEnvironment,
          sideEffect = { updatedCtx =>
            bootResult = Some(supervisor.bootEnvironment(planResult.get, currentCtx.repoRoot))
          },
        )
        currentCtx = currentCtx.copy(run = currentRun)
        SignalHandler.updateContext(currentCtx)

        bootResult.get match {
          case RuntimeSupervisor.BootFailure(reason, partialSnapshot) =>
            partialSnapshot.foreach(RuntimeSnapshotRepo.insert(_))
            try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }

            bootAttempt += 1
            if (bootAttempt > maxEnvBootRetries) {
              // Spec §2.1: Transition to EnvironmentFailed → Exhausted after all retries
              currentRun = RunTransitionManager.transition(
                currentCtx,
                RunStatus.EnvironmentFailed,
                sideEffect = { _ => },
              )
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)

              currentRun = RunTransitionManager.transitionToTerminal(
                currentCtx,
                RunStatus.Exhausted,
                summary = Some(s"Environment bootstrap failed after ${bootAttempt} attempt(s): $reason"),
              )
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
              return currentRun
            } else {
              // Spec §2.1: EnvironmentFailed → retry BootstrappingEnvironment
              System.err.println(s"[orchestrator] Boot attempt $bootAttempt failed: $reason — retrying (${maxEnvBootRetries - bootAttempt} retries left)")
              currentRun = RunTransitionManager.transition(
                currentCtx,
                RunStatus.EnvironmentFailed,
                sideEffect = { _ => },
              )
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
            }

          case RuntimeSupervisor.BootSuccess(snapshot) =>
            RuntimeSnapshotRepo.insert(snapshot)
            bootSucceeded = true
        }
      }

      if (!bootSucceeded) {
        currentRun = RunTransitionManager.transitionToTerminal(
          currentCtx, RunStatus.Exhausted,
          summary = Some("Environment bootstrap failed after all retries"),
        )
        return currentRun
      }
    }

    // --- Transition: BootstrappingEnvironment → SeedingFixtures ---
    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
    if (shouldExecute(RunStatus.SeedingFixtures, startPhase)) {

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.SeedingFixtures,
        sideEffect = { _ => /* fixtures already executed in bootEnvironment */ },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)
    }

    // --- Auth Bootstrap (if configured) ---
    if (shouldExecute(RunStatus.BootstrappingAuth, startPhase)) {
      val authConfig: Option[ResolvedAuthConfig] = resolvedConfig.flatMap(_.auth)

      if (authConfig.isDefined) {
        if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

        var authResult: Option[AuthBootstrapExecutor.AuthResult] = None

        currentRun = RunTransitionManager.transition(
          currentCtx,
          RunStatus.BootstrappingAuth,
          sideEffect = { _ =>
            authResult = Some(AuthBootstrapExecutor.execute(
              authConfig.get, workerManager, currentCtx.worktreePath, currentRun.runId,
            ))
          },
        )
        currentCtx = currentCtx.copy(run = currentRun)
        SignalHandler.updateContext(currentCtx)

        authResult.foreach {
          case AuthBootstrapExecutor.AuthResult(true, path, headers, _) =>
            val devHeaders = if (authConfig.get.mode == AuthMode.DevBypassHeader) headers else Map.empty[String, String]
            authContext = Some(AuthContext(
              mode = authConfig.get.mode,
              storageStatePath = path,
              apiHeaders = headers,
              staticToken = authConfig.get.staticToken,
              devBypassHeaders = devHeaders,
              expiresAt = None,
            ))
          case AuthBootstrapExecutor.AuthResult(false, _, _, errOpt) =>
            val msg = errOpt.getOrElse("unknown reason")
            System.err.println(s"[orchestrator] Auth bootstrap failed (non-fatal): $msg")
        }
      }
    }

    // --- Transition: → ReadyToVerify ---
    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
    if (shouldExecute(RunStatus.ReadyToVerify, startPhase)) {

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.ReadyToVerify,
        sideEffect = { _ => },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)
    }

    // --- Phase 5: Verification + multi-attempt repair loop ---
    // attemptNumber and patchHistory may already be populated from resume data
    // or from build mode code generation (PlanningFeature → GeneratingCode).

    while (attemptNumber <= currentRun.maxAttempts) {

      // --- Check for interrupt at top of loop ---
      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

      // --- Spec §8: Environment health check before verification ---
      planResult.foreach { plan =>
        val health = EnvironmentHealthMonitor.checkHealth(plan)
        health.status match {
          case EnvironmentHealthMonitor.Degraded(services, details) =>
            System.err.println(s"[orchestrator] Environment degraded: ${services.mkString(", ")}. Attempting recovery...")
            EnvironmentHealthMonitor.attemptRecovery(plan, currentCtx.repoRoot, health) match {
              case EnvironmentHealthMonitor.RecoverySuccess =>
                System.err.println("[orchestrator] Environment recovery succeeded")
              case EnvironmentHealthMonitor.RecoveryPartial(recovered, failed) =>
                System.err.println(s"[orchestrator] Partial recovery: recovered=${recovered.mkString(",")}, failed=${failed.mkString(",")}")
              case EnvironmentHealthMonitor.RecoveryFailed(reason) =>
                System.err.println(s"[orchestrator] Recovery failed: $reason — continuing with degraded environment")
            }
          case EnvironmentHealthMonitor.Failed(reason) =>
            System.err.println(s"[orchestrator] Environment failed: $reason — attempting full restart")
            try {
              supervisor.restartEnvironment(plan, currentCtx.repoRoot) match {
                case RuntimeSupervisor.BootSuccess(snap) =>
                  RuntimeSnapshotRepo.insert(snap)
                  System.err.println("[orchestrator] Environment restart succeeded")
                case RuntimeSupervisor.BootFailure(r, _) =>
                  System.err.println(s"[orchestrator] Environment restart failed: $r")
              }
            } catch { case e: Exception =>
              System.err.println(s"[orchestrator] Environment restart threw exception: ${e.getMessage}")
            }
          case EnvironmentHealthMonitor.Healthy => // OK
        }
      }

      // --- Transition: → Verifying ---
      var verificationResult: Option[VerificationEngine.VerificationResult] = None
      var currentAttempt: Option[Attempt] = None

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.Verifying,
        sideEffect = { _ =>
          val attempt = AttemptManager.createAttempt(currentRun.runId, attemptNumber)
          val verifying = AttemptManager.startVerifying(attempt)

          // Design: Agentic Browser Verification — build agent browser executor if worker available
          val agentBrowserExecutor: Option[VerificationEngine.AgentBrowserExecutor] =
            workerManager.filter(_.isAlive).map { wm =>
              new AgentBrowserExecutorImpl(wm, currentCtx.repoRoot, agentConfig, currentRun.artifactRootPath)
            }

          val result = VerificationEngine.runVerification(
            currentRun.runId,
            attemptNumber,
            requirementResult.get,
            browserExecutor,
            inferenceService,
            resolvedConfig,
            authContext,
            agentBrowserExecutor,
          )
          verificationResult = Some(result)

          val completed = AttemptManager.completeAttempt(
            verifying,
            result.verdicts,
            result.aggregate.overallVerdict,
          )
          currentAttempt = Some(completed)
        },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)

      // --- Evaluate verdict ---
      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

      val verdict = verificationResult.get.aggregate.overallVerdict
      val agg = verificationResult.get.aggregate

      if (verdict == VerdictStatus.Pass || verdict == VerdictStatus.Flake) {
        // Success — teardown and transition to Succeeded
        // Spec §4.5: Flake counts as pass for control flow but is flagged
        try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
        val flakeNote = if (agg.flakeCount > 0) s" (${agg.flakeCount} flaky)" else ""
        val nonPassCount = agg.failCount + agg.errorCount + agg.timeoutCount
        val summaryText = if (nonPassCount == 0) {
          if (attemptNumber == 1) s"All ${agg.total} verifiers passed$flakeNote"
          else s"All ${agg.total} verifiers passed after ${attemptNumber - 1} repair(s)$flakeNote"
        } else {
          val repairNote = if (attemptNumber > 1) s" after ${attemptNumber - 1} repair(s)" else ""
          s"${agg.passCount}/${agg.total} verifiers passed$repairNote$flakeNote ($nonPassCount non-required failed)"
        }
        currentRun = RunTransitionManager.transitionToTerminal(
          currentCtx,
          RunStatus.Succeeded,
          summary = Some(summaryText),
          finalVerdict = Some(verdict),
        )
        currentCtx = currentCtx.copy(run = currentRun)
        SignalHandler.updateContext(currentCtx)
        reportTokenUsageIfAny(currentRun, accumulatedInputTokens, accumulatedOutputTokens)
        return currentRun
      }

      // Fail + no more attempts remaining
      if (attemptNumber >= currentRun.maxAttempts) {
        try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
        currentRun = RunTransitionManager.transitionToTerminal(
          currentCtx,
          RunStatus.Exhausted,
          summary = Some(s"Verification failed after $attemptNumber attempt(s): ${agg.failCount} failed, ${agg.errorCount} errors out of ${agg.total}"),
          finalVerdict = Some(VerdictStatus.Fail),
        )
        currentCtx = currentCtx.copy(run = currentRun)
        SignalHandler.updateContext(currentCtx)
        reportTokenUsageIfAny(currentRun, accumulatedInputTokens, accumulatedOutputTokens)
        return currentRun
      }

      // Fail + no repair backend (neither legacy nor agent)
      if (repairBackend.isEmpty && agentBackend.isEmpty) {
        try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
        currentRun = RunTransitionManager.transitionToTerminal(
          currentCtx,
          RunStatus.Exhausted,
          summary = Some(s"Verification failed: ${agg.failCount} failed, ${agg.errorCount} errors, ${agg.timeoutCount} timeouts out of ${agg.total}"),
          finalVerdict = Some(VerdictStatus.Fail),
        )
        currentCtx = currentCtx.copy(run = currentRun)
        SignalHandler.updateContext(currentCtx)
        reportTokenUsageIfAny(currentRun, accumulatedInputTokens, accumulatedOutputTokens)
        return currentRun
      }

      // --- Repair cycle (Fail + attempts remain + backend exists) ---
      // Design §8.2: When agentBackend is available AND worker manager is present, use it;
      // otherwise fall back to legacy.
      val useAgentBackend = agentBackend.isDefined && workerManager.isDefined
      val maxRepairRetriesPerAttempt = 2
      var repairRetryCount = 0
      var repairRetryLoop = true

      while (repairRetryLoop) {
      repairRetryLoop = false

      // Transition: → AnalyzingFailure
      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.AnalyzingFailure,
        sideEffect = { _ => },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)

      // Transition: → PlanningRepair
      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.PlanningRepair,
        sideEffect = { _ => },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)

      // Transition: → Repairing
      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

      // Design §8.2: Agent-backed repair path vs legacy repair path
      var repairOutcome: Option[RepairExecutor.RepairOutcome] = None
      var agentRepairResult: Option[AgentResult] = None

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.Repairing,
        sideEffect = { _ =>
          currentAttempt.foreach(a => RepairManager.markAttemptRepairing(a.attemptId))

          // Gap 5: Collect service logs after failed verification (best-effort)
          val collected = planResult.map(LogCollector.collectAfterVerification)
          val logsString = collected.flatMap(_.serialize())

          val repairContext = RepairManager.buildRepairContext(
            ctx = currentCtx,
            attemptNumber = attemptNumber,
            graph = requirementResult.get,
            verdicts = verificationResult.get.verdicts,
            inspectionReport = inspectionResult,
            runtimePlan = planResult,
            patchHistory = patchHistory,
            logs = logsString,
          )

          if (useAgentBackend) {
            // Design §8.2: Agent-backed repair — register tool handlers, then execute
            val agent = agentBackend.get
            val toolCtx = AgentToolRpcHandlers.AgentToolContext(
              runId = currentRun.runId,
              requirementGraph = requirementResult.get,
              runtimePlan = planResult,
              supervisor = supervisor,
              workerManager = workerManager.get,
              repoRoot = currentCtx.repoRoot,
              browserExecutor = browserExecutor,
              inferenceService = inferenceService,
              resolvedConfig = resolvedConfig,
              authContext = authContext,
            )
            AgentToolRpcHandlers.registerHandlers(toolCtx)

            // Thread policy attemptTimeoutMs to agent config so the agent has enough
            // time for long operations (e.g. Bazel rebuild after restart_service).
            val effectiveAgentConfig = resolvedConfig match {
              case Some(rc) if rc.policies.attemptTimeoutMs > agentConfig.timeoutMs =>
                agentConfig.copy(timeoutMs = rc.policies.attemptTimeoutMs - 30000)
              case _ => agentConfig
            }

            // Design: Agentic Browser Verification §11 — enable browser tools on repair agent
            // when the requirement graph contains frontend/browser verifiers
            val hasFrontendRequirements = requirementResult.get.nodes.exists(n =>
              n.category == RequirementCategory.UiFlow ||
              n.verifiers.exists(v =>
                v.verifierType == VerifierType.BrowserFlow ||
                v.verifierType == VerifierType.AgentBrowser
              )
            )
            val repairAgentConfig = if (hasFrontendRequirements) {
              effectiveAgentConfig.copy(enableBrowserTools = true)
            } else effectiveAgentConfig

            agentRepairResult = Some(agent.executeRepair(repairContext, repairAgentConfig))
          } else {
            // Legacy repair path
            val backend = repairBackend.get
            val failureInput = RepairManager.buildFailureInput(
              runId = currentRun.runId,
              attemptNumber = attemptNumber,
              taskText = currentRun.taskText,
              verdicts = verificationResult.get.verdicts,
              graph = requirementResult.get,
              inspectionReport = inspectionResult,
              runtimePlan = planResult,
              patchHistory = patchHistory,
              logs = logsString,
            )

            val sessionResult = RepairSession.executeWithSession(
              backend, currentCtx.worktreePath, failureInput, repairContext,
            )
            repairOutcome = Some(sessionResult.outcome)

            // Persist repair transcript as artifact (best-effort)
            try {
              val transcriptJson = RepairSession.serializeTranscript(sessionResult.session)
              RepairManager.persistRepairTranscript(
                currentRun.runId, attemptNumber, transcriptJson,
                sessionResult.session.preCommitSha, sessionResult.session.postCommitSha,
              )
            } catch { case _: Exception => /* best-effort */ }
          }
        },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)

      // Design §8.2: Handle agent result or legacy repair outcome
      // Common helper: perform environment reset based on changed files
      def performEnvironmentReset(filesChanged: List[String]): Option[TaskRun] = {
        val needsFullRebuild = InfraSensitiveDetector.requiresRebuild(filesChanged)

        if (needsFullRebuild) {
          if (SignalHandler.isInterrupted) return Some(handleInterrupt(currentCtx))

          var rebuildResult: Option[RuntimeSupervisor.BootResult] = None
          currentRun = RunTransitionManager.transition(
            currentCtx,
            RunStatus.RebuildingEnvironment,
            sideEffect = { _ =>
              try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
              rebuildResult = Some(supervisor.bootEnvironment(planResult.get, currentCtx.repoRoot))
            },
          )
          currentCtx = currentCtx.copy(run = currentRun)
          SignalHandler.updateContext(currentCtx)

          rebuildResult.get match {
            case RuntimeSupervisor.BootFailure(reason, partialSnapshot) =>
              partialSnapshot.foreach(RuntimeSnapshotRepo.insert(_))
              try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
              currentRun = RunTransitionManager.transitionToTerminal(
                currentCtx, RunStatus.Exhausted,
                summary = Some(s"Environment rebuild failed after repair: $reason"),
                finalVerdict = Some(VerdictStatus.Fail),
              )
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
              return Some(currentRun)
            case RuntimeSupervisor.BootSuccess(snap) =>
              RuntimeSnapshotRepo.insert(snap)
          }
        } else {
          if (SignalHandler.isInterrupted) return Some(handleInterrupt(currentCtx))

          var rebootResult: Option[RuntimeSupervisor.BootResult] = None
          currentRun = RunTransitionManager.transition(
            currentCtx,
            RunStatus.SoftResettingEnvironment,
            sideEffect = { _ =>
              rebootResult = Some(supervisor.restartEnvironment(planResult.get, currentCtx.repoRoot))
            },
          )
          currentCtx = currentCtx.copy(run = currentRun)
          SignalHandler.updateContext(currentCtx)

          rebootResult.get match {
            case RuntimeSupervisor.BootFailure(reason, partialSnapshot) =>
              partialSnapshot.foreach(RuntimeSnapshotRepo.insert(_))
              try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
              currentRun = RunTransitionManager.transitionToTerminal(
                currentCtx, RunStatus.Exhausted,
                summary = Some(s"Environment reboot failed after repair: $reason"),
                finalVerdict = Some(VerdictStatus.Fail),
              )
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
              return Some(currentRun)
            case RuntimeSupervisor.BootSuccess(rebootSnapshot) =>
              RuntimeSnapshotRepo.insert(rebootSnapshot)
          }
        }
        None // success — no early return
      }

      if (useAgentBackend) {
        // Design §8.2: Agent backend result handling
        agentRepairResult.get match {
          case completed: AgentCompleted =>
            System.err.println(s"[orchestrator] Agent completed: ${completed.summary.take(200)}")
            System.err.println(s"[orchestrator]   Files changed: ${completed.filesChanged.mkString(", ")}")
            System.err.println(s"[orchestrator]   Tokens: ${completed.inputTokens}in/${completed.outputTokens}out, cost=$$${completed.costUsd}")
            // Spec 05 §5.2: Accumulate real token usage for cloud reporting
            accumulatedInputTokens += completed.inputTokens
            accumulatedOutputTokens += completed.outputTokens

            // Design §8.2: Environment reset based on changed files
            performEnvironmentReset(completed.filesChanged).foreach(earlyReturn => return earlyReturn)

            // Transition: → ReadyToVerify
            if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
            currentRun = RunTransitionManager.transition(
              currentCtx, RunStatus.ReadyToVerify, sideEffect = { _ => })
            currentCtx = currentCtx.copy(run = currentRun)
            SignalHandler.updateContext(currentCtx)
            attemptNumber += 1

          case failed: AgentFailed =>
            System.err.println(s"[orchestrator] Agent failed: ${failed.reason}")
            accumulatedInputTokens += failed.inputTokens
            accumulatedOutputTokens += failed.outputTokens
            repairRetryCount += 1
            if (repairRetryCount <= maxRepairRetriesPerAttempt) {
              System.err.println(s"[orchestrator] Agent retry $repairRetryCount/$maxRepairRetriesPerAttempt")
              currentRun = RunTransitionManager.transition(
                currentCtx, RunStatus.RepairFailed, sideEffect = { _ => })
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
              repairRetryLoop = true
            } else {
              try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
              currentRun = RunTransitionManager.transitionToTerminal(
                currentCtx, RunStatus.Exhausted,
                summary = Some(s"Agent repair failed after $repairRetryCount retries: ${failed.reason}"),
                finalVerdict = Some(VerdictStatus.Fail))
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
              return currentRun
            }

          case timeout: AgentTimeout =>
            System.err.println(s"[orchestrator] Agent timed out after ${timeout.timeoutMs}ms")
            accumulatedInputTokens += timeout.inputTokens
            accumulatedOutputTokens += timeout.outputTokens
            try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
            currentRun = RunTransitionManager.transitionToTerminal(
              currentCtx, RunStatus.Exhausted,
              summary = Some(s"Agent timed out after ${timeout.timeoutMs}ms (cost=$$${timeout.costUsd})"),
              finalVerdict = Some(VerdictStatus.Fail))
            currentCtx = currentCtx.copy(run = currentRun)
            SignalHandler.updateContext(currentCtx)
            return currentRun

          case budget: AgentBudgetExceeded =>
            System.err.println(s"[orchestrator] Agent budget exceeded: $$${budget.actualCostUsd}")
            accumulatedInputTokens += budget.inputTokens
            accumulatedOutputTokens += budget.outputTokens
            try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
            currentRun = RunTransitionManager.transitionToTerminal(
              currentCtx, RunStatus.Exhausted,
              summary = Some(s"Agent budget exceeded: $$${budget.actualCostUsd}"),
              finalVerdict = Some(VerdictStatus.Fail))
            currentCtx = currentCtx.copy(run = currentRun)
            SignalHandler.updateContext(currentCtx)
            return currentRun
        }
      } else {
        // Legacy repair outcome handling
        repairOutcome.get match {
          case RepairExecutor.RepairApplied(packet, proposal, filesChanged) =>
            RepairManager.persistFailurePacket(packet)
            RepairManager.persistPatchRecord(proposal)
            currentAttempt.foreach(a =>
              RepairManager.markAttemptRepairSucceeded(
                a.attemptId, proposal.patchId, packet.failurePacketId, proposal.backendId))
            patchHistory = patchHistory :+ proposal

            performEnvironmentReset(filesChanged).foreach(earlyReturn => return earlyReturn)

            // Transition: → ReadyToVerify
            if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
            currentRun = RunTransitionManager.transition(
              currentCtx, RunStatus.ReadyToVerify, sideEffect = { _ => })
            currentCtx = currentCtx.copy(run = currentRun)
            SignalHandler.updateContext(currentCtx)
            attemptNumber += 1

          case RepairExecutor.RepairRejected(packet, reason) =>
            RepairManager.persistFailurePacket(packet)
            currentAttempt.foreach(a =>
              RepairManager.markAttemptRepairFailed(a.attemptId, packet.failurePacketId))

            repairRetryCount += 1
            if (repairRetryCount <= maxRepairRetriesPerAttempt) {
              System.err.println(s"[orchestrator] Repair failed (retry $repairRetryCount/$maxRepairRetriesPerAttempt): $reason")
              currentRun = RunTransitionManager.transition(
                currentCtx, RunStatus.RepairFailed, sideEffect = { _ => })
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
              repairRetryLoop = true
            } else {
              try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
              currentRun = RunTransitionManager.transitionToTerminal(
                currentCtx, RunStatus.Exhausted,
                summary = Some(s"Repair failed after $repairRetryCount retries: $reason"),
                finalVerdict = Some(VerdictStatus.Fail))
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
              return currentRun
            }
        }
      }

      } // end repair retry while
    } // end while

    // Safety: unreachable under normal control flow — every path inside the loop
    // either returns or increments attemptNumber past maxAttempts (which then
    // returns at the top-of-loop guard). This fallthrough exists as defensive coding.
    try { planResult.foreach(p => supervisor.teardown(p, currentCtx.repoRoot)) } catch { case _: Exception => }
    currentRun = RunTransitionManager.transitionToTerminal(
      currentCtx,
      RunStatus.Exhausted,
      summary = Some(s"Exhausted all ${currentRun.maxAttempts} attempts"),
      finalVerdict = Some(VerdictStatus.Fail),
    )
    currentCtx = currentCtx.copy(run = currentRun)
    SignalHandler.updateContext(currentCtx)

    currentRun
  }

  /** Remap service CWDs in a RuntimePlan from repoRoot to worktreePath.
   *  This ensures services run from the isolated worktree where patches are applied,
   *  not from the original repo which may have different file contents.
   */
  private def remapPlanCwds(plan: RuntimePlan, repoRoot: java.nio.file.Path, worktreePath: java.nio.file.Path): RuntimePlan = {
    val repoStr = repoRoot.toAbsolutePath.normalize().toString
    val worktreeStr = worktreePath.toAbsolutePath.normalize().toString
    if (repoStr == worktreeStr) return plan

    val remappedServices = plan.services.map { spec =>
      val newCwd = if (spec.cwd == repoStr || spec.cwd.startsWith(repoStr + "/")) {
        spec.cwd.replaceFirst(java.util.regex.Pattern.quote(repoStr), worktreeStr)
      } else spec.cwd
      spec.copy(cwd = newCwd)
    }
    val remappedFixtures = plan.fixtureSteps.map { step =>
      val newCwd = if (step.cwd == repoStr || step.cwd.startsWith(repoStr + "/")) {
        step.cwd.replaceFirst(java.util.regex.Pattern.quote(repoStr), worktreeStr)
      } else step.cwd
      step.copy(cwd = newCwd)
    }
    plan.copy(services = remappedServices, fixtureSteps = remappedFixtures)
  }

  /**
   * Spec 05 §5.2: Report token usage after a run reaches a terminal state.
   * Fire-and-forget — failures are silently ignored.
   */
  private def reportTokenUsageIfAny(run: TaskRun, inputTokens: Long = 0, outputTokens: Long = 0): Unit = {
    try {
      val licenseKey = CredentialStore.loadCredentials().map(_.licenseKey).getOrElse("")
      if (licenseKey.nonEmpty && (inputTokens > 0 || outputTokens > 0)) {
        // Spec 05 §5.2: Report accumulated token usage from agent interactions to the cloud.
        UsageReporter.reportTokenUsage(licenseKey, run.runId, inputTokens, outputTokens)
      }
    } catch {
      case _: Exception => // Best effort — never block run completion
    }
  }

  /** Handle interruption: persist Interrupted status. */
  private def handleInterrupt(ctx: RunContext, inputTokens: Long = 0, outputTokens: Long = 0): TaskRun = {
    val run = RunTransitionManager.transitionToTerminal(
      ctx,
      RunStatus.Interrupted,
      summary = Some("Run interrupted by signal"),
    )
    reportTokenUsageIfAny(run, inputTokens, outputTokens)
    run
  }
}
