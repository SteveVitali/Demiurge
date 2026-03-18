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

// Spec §4.1: Synchronous orchestrator loop for Phase 5.
// Executes the path: Created → InspectingRepo → CompilingRequirements →
// PlanningEnvironment → BootstrappingEnvironment → SeedingFixtures → ReadyToVerify →
// Verifying → Evaluating →
//   (Succeeded) or
//   (NeedsRepair → Repairing → ApplyingPatch → RebootingEnvironment →
//    ReadyToVerify → Verifying → Evaluating → Succeeded | Exhausted)
// Only ONE repair attempt allowed.
// All transitions enforce persist-before-side-effects (Spec §4.1).
object RunOrchestrator {

  /**
   * Execute the Phase 5 orchestration path with single repair attempt.
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
          val graph = compiler.compile(
            currentRun.runId,
            inspectionResult.get,
            currentRun.taskText,
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

          // --- Phase 5: Verification + single repair loop ---
          var repairAttempted = false
          var patchHistory: List[PatchProposal] = Nil

          // First verification attempt
          if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

          var verificationResult: Option[VerificationEngine.VerificationResult] = None
          var currentAttempt: Option[Attempt] = None

          currentRun = RunTransitionManager.transition(
            currentCtx,
            RunStatus.Verifying,
            sideEffect = { _ =>
              val attempt = AttemptManager.createAttempt(currentRun.runId, 1)
              val verifying = AttemptManager.startVerifying(attempt)

              val result = VerificationEngine.runVerification(
                currentRun.runId,
                1,
                requirementResult.get,
                browserExecutor,
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

          // --- Evaluate first verdict ---
          if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

          val verdict = verificationResult.get.aggregate.overallVerdict
          val agg = verificationResult.get.aggregate

          if (verdict == VerdictStatus.Pass) {
            // Success on first attempt
            try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
            currentRun = RunTransitionManager.transitionToTerminal(
              currentCtx,
              RunStatus.Succeeded,
              summary = Some(s"All ${agg.total} verifiers passed"),
              finalVerdict = Some(VerdictStatus.Pass),
            )
            currentCtx = currentCtx.copy(run = currentRun)
            SignalHandler.updateContext(currentCtx)
          } else if (repairBackend.isDefined && !repairAttempted) {
            // --- Phase 5: Single repair attempt ---
            repairAttempted = true
            val backend = repairBackend.get

            // Transition: Verifying → AnalyzingFailure (NeedsRepair)
            if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
            currentRun = RunTransitionManager.transition(
              currentCtx,
              RunStatus.AnalyzingFailure,
              sideEffect = { _ => },
            )
            currentCtx = currentCtx.copy(run = currentRun)
            SignalHandler.updateContext(currentCtx)

            // Transition: AnalyzingFailure → PlanningRepair
            if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
            currentRun = RunTransitionManager.transition(
              currentCtx,
              RunStatus.PlanningRepair,
              sideEffect = { _ => },
            )
            currentCtx = currentCtx.copy(run = currentRun)
            SignalHandler.updateContext(currentCtx)

            // Transition: PlanningRepair → Repairing
            if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

            var repairOutcome: Option[RepairExecutor.RepairOutcome] = None

            currentRun = RunTransitionManager.transition(
              currentCtx,
              RunStatus.Repairing,
              sideEffect = { _ =>
                // Mark attempt as repairing
                currentAttempt.foreach(a => RepairManager.markAttemptRepairing(a.attemptId))

                // Build repair inputs
                val failureInput = RepairManager.buildFailureInput(
                  runId = currentRun.runId,
                  attemptNumber = 1,
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
                  attemptNumber = 1,
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
              case RepairExecutor.RepairApplied(packet, proposal, filesChanged) =>
                // Persist failure packet and patch record
                RepairManager.persistFailurePacket(packet)
                RepairManager.persistPatchRecord(proposal)
                currentAttempt.foreach(a =>
                  RepairManager.markAttemptRepairSucceeded(
                    a.attemptId, proposal.patchId, packet.failurePacketId, proposal.backendId))
                patchHistory = patchHistory :+ proposal

                // --- Transition: Repairing → RebootingEnvironment (via SoftResettingEnvironment) ---
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

                    // Transition: back to ReadyToVerify
                    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
                    currentRun = RunTransitionManager.transition(
                      currentCtx,
                      RunStatus.ReadyToVerify,
                      sideEffect = { _ => },
                    )
                    currentCtx = currentCtx.copy(run = currentRun)
                    SignalHandler.updateContext(currentCtx)

                    // --- Rerun verification (attempt 2) ---
                    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

                    var rerunResult: Option[VerificationEngine.VerificationResult] = None
                    currentRun = RunTransitionManager.transition(
                      currentCtx,
                      RunStatus.Verifying,
                      sideEffect = { _ =>
                        val attempt2 = AttemptManager.createAttempt(currentRun.runId, 2)
                        val verifying2 = AttemptManager.startVerifying(attempt2)

                        val result2 = VerificationEngine.runVerification(
                          currentRun.runId,
                          2,
                          requirementResult.get,
                          browserExecutor,
                        )
                        rerunResult = Some(result2)

                        AttemptManager.completeAttempt(
                          verifying2,
                          result2.verdicts,
                          result2.aggregate.overallVerdict,
                        )
                      },
                    )
                    currentCtx = currentCtx.copy(run = currentRun)
                    SignalHandler.updateContext(currentCtx)

                    // --- Evaluate rerun verdict ---
                    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

                    val rerunVerdict = rerunResult.get.aggregate.overallVerdict
                    val rerunAgg = rerunResult.get.aggregate

                    try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }

                    if (rerunVerdict == VerdictStatus.Pass) {
                      currentRun = RunTransitionManager.transitionToTerminal(
                        currentCtx,
                        RunStatus.Succeeded,
                        summary = Some(s"All ${rerunAgg.total} verifiers passed after repair"),
                        finalVerdict = Some(VerdictStatus.Pass),
                      )
                    } else {
                      currentRun = RunTransitionManager.transitionToTerminal(
                        currentCtx,
                        RunStatus.Exhausted,
                        summary = Some(s"Verification failed after repair: ${rerunAgg.failCount} failed, ${rerunAgg.errorCount} errors out of ${rerunAgg.total}"),
                        finalVerdict = Some(VerdictStatus.Fail),
                      )
                    }
                    currentCtx = currentCtx.copy(run = currentRun)
                    SignalHandler.updateContext(currentCtx)
                }

              case RepairExecutor.RepairRejected(packet, reason) =>
                // Repair failed — persist packet and go to Exhausted
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
            }
          } else {
            // No repair backend or already repaired — go to Exhausted
            try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }
            currentRun = RunTransitionManager.transitionToTerminal(
              currentCtx,
              RunStatus.Exhausted,
              summary = Some(s"Verification failed: ${agg.failCount} failed, ${agg.errorCount} errors, ${agg.timeoutCount} timeouts out of ${agg.total}"),
              finalVerdict = Some(VerdictStatus.Fail),
            )
            currentCtx = currentCtx.copy(run = currentRun)
            SignalHandler.updateContext(currentCtx)
          }
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
