package lastmile.orchestrator

import java.sql.Connection

import lastmile.model._
import lastmile.inspector.RepoInspector
import lastmile.compiler.RequirementCompiler
import lastmile.planner.EnvironmentPlanner
import lastmile.runtime.RuntimeSupervisor
import lastmile.verification.VerificationEngine
import lastmile.persistence._

// Spec §4.1: Synchronous orchestrator loop for Phase 4.
// Executes the path: Created → InspectingRepo → CompilingRequirements →
// PlanningEnvironment → BootstrappingEnvironment → SeedingFixtures → ReadyToVerify →
// Verifying → (Succeeded | Exhausted)
// All transitions enforce persist-before-side-effects (Spec §4.1).
object RunOrchestrator {

  /**
   * Execute the Phase 4 orchestration path.
   * Returns the final TaskRun state after reaching a terminal status.
   */
  def execute(
    ctx: RunContext,
    inspector: RepoInspector,
    compiler: RequirementCompiler,
    planner: EnvironmentPlanner,
    supervisor: RuntimeSupervisor,
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

          // --- Phase 4: Verification loop (single attempt, no repair) ---
          if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

          // Transition: ReadyToVerify → Verifying
          var verificationResult: Option[VerificationEngine.VerificationResult] = None

          currentRun = RunTransitionManager.transition(
            currentCtx,
            RunStatus.Verifying,
            sideEffect = { _ =>
              // Create attempt
              val attempt = AttemptManager.createAttempt(currentRun.runId, 1)
              val verifying = AttemptManager.startVerifying(attempt)

              // Execute verifiers
              val result = VerificationEngine.runVerification(
                currentRun.runId,
                1,
                requirementResult.get,
              )
              verificationResult = Some(result)

              // Complete the attempt
              AttemptManager.completeAttempt(
                verifying,
                result.verdicts,
                result.aggregate.overallVerdict,
              )
            },
          )
          currentCtx = currentCtx.copy(run = currentRun)
          SignalHandler.updateContext(currentCtx)

          // --- Evaluate verdict ---
          if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

          val verdict = verificationResult.get.aggregate.overallVerdict
          val agg = verificationResult.get.aggregate

          try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }

          if (verdict == VerdictStatus.Pass) {
            currentRun = RunTransitionManager.transitionToTerminal(
              currentCtx,
              RunStatus.Succeeded,
              summary = Some(s"All ${agg.total} verifiers passed"),
              finalVerdict = Some(VerdictStatus.Pass),
            )
          } else {
            // Phase 4: no repair — go straight to Exhausted
            currentRun = RunTransitionManager.transitionToTerminal(
              currentCtx,
              RunStatus.Exhausted,
              summary = Some(s"Verification failed: ${agg.failCount} failed, ${agg.errorCount} errors, ${agg.timeoutCount} timeouts out of ${agg.total}"),
              finalVerdict = Some(VerdictStatus.Fail),
            )
          }
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
