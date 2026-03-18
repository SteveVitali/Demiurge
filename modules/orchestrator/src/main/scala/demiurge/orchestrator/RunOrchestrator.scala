package demiurge.orchestrator

import java.sql.Connection

import demiurge.model._
import demiurge.inspector.RepoInspector
import demiurge.compiler.RequirementCompiler
import demiurge.planner.EnvironmentPlanner
import demiurge.runtime.RuntimeSupervisor
import demiurge.verification.{VerificationEngine, BrowserFlowVerifier, BrowserVerifierResult}
import demiurge.persistence._
import demiurge.repair._
import demiurge.config.{ConfigResolver, ConfigResolverImpl}
import demiurge.inference.InferenceService

// Spec §4.1: Synchronous orchestrator loop.
// Executes the path: Created → InspectingRepo → CompilingRequirements →
// PlanningEnvironment → BootstrappingEnvironment → SeedingFixtures → ReadyToVerify →
// [Verification + Repair Loop up to maxAttempts]:
//   Verifying → (Pass → Succeeded) | (Fail → AnalyzingFailure → PlanningRepair →
//     Repairing → SoftResettingEnvironment → ReadyToVerify → next attempt)
// All transitions enforce persist-before-side-effects (Spec §4.1).
object RunOrchestrator {

  /**
   * Execute the orchestration path with bounded multi-attempt repair loop.
   * Iterates up to maxAttempts (default 5, build mode 8).
   * Returns the final TaskRun state after reaching a terminal status.
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
  ): TaskRun = {
    // Register signal handler for interruption persistence (Spec §4.4)
    SignalHandler.register(ctx, ctx.repoRoot)

    var currentRun = ctx.run
    var currentCtx = ctx
    implicit val conn: Connection = ctx.conn

    // --- Transition: Created → InspectingRepo ---
    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
    if (currentRun.status == RunStatus.Created) {
      var inspectionResult: Option[RepoInspectionReport] = None
      var resolvedConfig: Option[ResolvedConfig] = None

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.InspectingRepo,
        sideEffect = { updatedCtx =>
          val report = inspector.inspect(
            currentRun.runId,
            currentCtx.repoRoot,
            currentRun.changedFiles,
          )
          // Persist inspection report (Spec §7.2)
          RepoInspectionReportRepo.insert(report)
          inspectionResult = Some(report)

          // Phase E: Resolve config (explicit YAML → cached → inferred)
          resolvedConfig = configResolver.map(_.resolve(
            repoPath = currentCtx.repoRoot,
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

      // --- Transition: InspectingRepo → CompilingRequirements ---
      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
      var requirementResult: Option[RequirementGraph] = None

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

      // --- Transition: CompilingRequirements → PlanningEnvironment ---
      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
      var planResult: Option[RuntimePlan] = None

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.PlanningEnvironment,
        sideEffect = { updatedCtx =>
          val plan = planner.plan(
            currentRun.runId,
            inspectionResult.get,
            requirementResult.get,
          )
          // Persist runtime plan (Spec §7.2)
          RuntimePlanRepo.insert(plan)
          planResult = Some(plan)
        },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)

      // --- Transition: PlanningEnvironment → BootstrappingEnvironment ---
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
          // Persist partial snapshot if available
          partialSnapshot.foreach(RuntimeSnapshotRepo.insert(_))
          // Teardown on failure
          try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }

          currentRun = RunTransitionManager.transitionToTerminal(
            currentCtx,
            RunStatus.Exhausted,
            summary = Some(s"Environment bootstrap failed: $reason"),
          )
          currentCtx = currentCtx.copy(run = currentRun)
          SignalHandler.updateContext(currentCtx)
          return currentRun

        case RuntimeSupervisor.BootSuccess(snapshot) =>
          // Persist runtime snapshot (Spec §7.2)
          RuntimeSnapshotRepo.insert(snapshot)

          // --- Transition: BootstrappingEnvironment → SeedingFixtures ---
          if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

          currentRun = RunTransitionManager.transition(
            currentCtx,
            RunStatus.SeedingFixtures,
            sideEffect = { _ => /* fixtures already executed in bootEnvironment */ },
          )
          currentCtx = currentCtx.copy(run = currentRun)
          SignalHandler.updateContext(currentCtx)

          // --- Transition: SeedingFixtures → ReadyToVerify ---
          if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

          currentRun = RunTransitionManager.transition(
            currentCtx,
            RunStatus.ReadyToVerify,
            sideEffect = { _ => },
          )
          currentCtx = currentCtx.copy(run = currentRun)
          SignalHandler.updateContext(currentCtx)

          // --- Phase 5: Verification + multi-attempt repair loop ---
          var attemptNumber = 1
          var patchHistory: List[PatchProposal] = Nil
          // Note: if there are any patchHistory entries from build mode code generation
          // (Gap 3, not yet implemented), they would be passed in. For now, start empty.

          while (attemptNumber <= currentRun.maxAttempts) {

            // --- Check for interrupt at top of loop ---
            if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

            // --- Transition: → Verifying ---
            var verificationResult: Option[VerificationEngine.VerificationResult] = None
            var currentAttempt: Option[Attempt] = None

            currentRun = RunTransitionManager.transition(
              currentCtx,
              RunStatus.Verifying,
              sideEffect = { _ =>
                val attempt = AttemptManager.createAttempt(currentRun.runId, attemptNumber)
                val verifying = AttemptManager.startVerifying(attempt)

                val result = VerificationEngine.runVerification(
                  currentRun.runId,
                  attemptNumber,
                  requirementResult.get,
                  browserExecutor,
                  inferenceService,
                  resolvedConfig,
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

            if (verdict == VerdictStatus.Pass) {
              // Success — teardown and transition to Succeeded
              try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
              currentRun = RunTransitionManager.transitionToTerminal(
                currentCtx,
                RunStatus.Succeeded,
                summary = Some(
                  if (attemptNumber == 1) s"All ${agg.total} verifiers passed"
                  else s"All ${agg.total} verifiers passed after ${attemptNumber - 1} repair(s)"
                ),
                finalVerdict = Some(VerdictStatus.Pass),
              )
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
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
              return currentRun
            }

            // Fail + no repair backend
            if (repairBackend.isEmpty) {
              try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
              currentRun = RunTransitionManager.transitionToTerminal(
                currentCtx,
                RunStatus.Exhausted,
                summary = Some(s"Verification failed: ${agg.failCount} failed, ${agg.errorCount} errors, ${agg.timeoutCount} timeouts out of ${agg.total}"),
                finalVerdict = Some(VerdictStatus.Fail),
              )
              currentCtx = currentCtx.copy(run = currentRun)
              SignalHandler.updateContext(currentCtx)
              return currentRun
            }

            // --- Repair cycle (Fail + attempts remain + repairBackend exists) ---
            val backend = repairBackend.get

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

            var repairOutcome: Option[RepairExecutor.RepairOutcome] = None

            currentRun = RunTransitionManager.transition(
              currentCtx,
              RunStatus.Repairing,
              sideEffect = { _ =>
                currentAttempt.foreach(a => RepairManager.markAttemptRepairing(a.attemptId))

                val failureInput = RepairManager.buildFailureInput(
                  runId = currentRun.runId,
                  attemptNumber = attemptNumber,
                  taskText = currentRun.taskText,
                  verdicts = verificationResult.get.verdicts,
                  graph = requirementResult.get,
                  inspectionReport = inspectionResult,
                  runtimePlan = planResult,
                  patchHistory = patchHistory,
                  logs = None,
                )

                val repairContext = RepairManager.buildRepairContext(
                  ctx = currentCtx,
                  attemptNumber = attemptNumber,
                  graph = requirementResult.get,
                  verdicts = verificationResult.get.verdicts,
                  inspectionReport = inspectionResult,
                  runtimePlan = planResult,
                  patchHistory = patchHistory,
                )

                // Execute repair
                repairOutcome = Some(RepairExecutor.executeRepair(
                  backend, currentCtx.worktreePath, failureInput, repairContext,
                ))
              },
            )
            currentCtx = currentCtx.copy(run = currentRun)
            SignalHandler.updateContext(currentCtx)

            repairOutcome.get match {
              case RepairExecutor.RepairApplied(packet, proposal, _) =>
                // Persist failure packet and patch record
                RepairManager.persistFailurePacket(packet)
                RepairManager.persistPatchRecord(proposal)
                currentAttempt.foreach(a =>
                  RepairManager.markAttemptRepairSucceeded(
                    a.attemptId, proposal.patchId, packet.failurePacketId, proposal.backendId))
                patchHistory = patchHistory :+ proposal

                // --- Transition: → SoftResettingEnvironment ---
                if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

                var rebootResult: Option[RuntimeSupervisor.BootResult] = None
                currentRun = RunTransitionManager.transition(
                  currentCtx,
                  RunStatus.SoftResettingEnvironment,
                  sideEffect = { _ =>
                    rebootResult = Some(supervisor.restartEnvironment(
                      planResult.get, currentCtx.repoRoot))
                  },
                )
                currentCtx = currentCtx.copy(run = currentRun)
                SignalHandler.updateContext(currentCtx)

                rebootResult.get match {
                  case RuntimeSupervisor.BootFailure(reason, partialSnapshot) =>
                    partialSnapshot.foreach(RuntimeSnapshotRepo.insert(_))
                    try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
                    currentRun = RunTransitionManager.transitionToTerminal(
                      currentCtx,
                      RunStatus.Exhausted,
                      summary = Some(s"Environment reboot failed after repair: $reason"),
                      finalVerdict = Some(VerdictStatus.Fail),
                    )
                    currentCtx = currentCtx.copy(run = currentRun)
                    SignalHandler.updateContext(currentCtx)
                    return currentRun

                  case RuntimeSupervisor.BootSuccess(rebootSnapshot) =>
                    RuntimeSnapshotRepo.insert(rebootSnapshot)

                    // Transition: → ReadyToVerify
                    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
                    currentRun = RunTransitionManager.transition(
                      currentCtx,
                      RunStatus.ReadyToVerify,
                      sideEffect = { _ => },
                    )
                    currentCtx = currentCtx.copy(run = currentRun)
                    SignalHandler.updateContext(currentCtx)

                    attemptNumber += 1
                    // continue loop
                }

              case RepairExecutor.RepairRejected(packet, reason) =>
                // Repair failed — persist packet and transition to Exhausted
                RepairManager.persistFailurePacket(packet)
                currentAttempt.foreach(a =>
                  RepairManager.markAttemptRepairFailed(a.attemptId, packet.failurePacketId))

                try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }

                currentRun = RunTransitionManager.transitionToTerminal(
                  currentCtx,
                  RunStatus.Exhausted,
                  summary = Some(s"Repair failed: $reason"),
                  finalVerdict = Some(VerdictStatus.Fail),
                )
                currentCtx = currentCtx.copy(run = currentRun)
                SignalHandler.updateContext(currentCtx)
                return currentRun
            }
          } // end while

          // Safety: unreachable under normal control flow — every path inside the loop
          // either returns or increments attemptNumber past maxAttempts (which then
          // returns at the top-of-loop guard). This fallthrough exists as defensive coding.
          try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
          currentRun = RunTransitionManager.transitionToTerminal(
            currentCtx,
            RunStatus.Exhausted,
            summary = Some(s"Exhausted all ${currentRun.maxAttempts} attempts"),
            finalVerdict = Some(VerdictStatus.Fail),
          )
          currentCtx = currentCtx.copy(run = currentRun)
          SignalHandler.updateContext(currentCtx)
      }
    }

    currentRun
  }

  /** Handle interruption: persist Interrupted status. */
  private def handleInterrupt(ctx: RunContext): TaskRun = {
    RunTransitionManager.transitionToTerminal(
      ctx,
      RunStatus.Interrupted,
      summary = Some("Run interrupted by signal"),
    )
  }
}
