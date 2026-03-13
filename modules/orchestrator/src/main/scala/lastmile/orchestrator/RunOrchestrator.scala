package lastmile.orchestrator

import java.sql.Connection

import lastmile.model._
import lastmile.inspector.RepoInspector
import lastmile.compiler.RequirementCompiler
import lastmile.planner.EnvironmentPlanner
import lastmile.runtime.RuntimeSupervisor
import lastmile.persistence._

// Spec §4.1: Synchronous orchestrator loop for Phase 3.
// Executes the path: Created → InspectingRepo → CompilingRequirements →
// PlanningEnvironment → BootstrappingEnvironment → SeedingFixtures → ReadyToVerify → Exhausted
// All transitions enforce persist-before-side-effects (Spec §4.1).
object RunOrchestrator {

  /**
   * Execute the Phase 3 orchestration path.
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

    // Spec §4.1: State machine — Phase 3 path
    // Created → InspectingRepo → CompilingRequirements → PlanningEnvironment →
    // BootstrappingEnvironment → SeedingFixtures → ReadyToVerify → Exhausted

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
          requirementResult = Some(compiler.compile(
            currentRun.runId,
            inspectionResult.get,
            currentRun.taskText,
          ))
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
          // Fixtures already ran inside bootEnvironment; this state records completion.
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
            sideEffect = { _ => /* Phase 3: verification not yet implemented */ },
          )
          currentCtx = currentCtx.copy(run = currentRun)
          SignalHandler.updateContext(currentCtx)

          // Phase 3 terminal: teardown environment and mark Exhausted
          if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

          try { supervisor.teardown(planResult.get, currentCtx.repoRoot) } catch { case _: Exception => }

          currentRun = RunTransitionManager.transitionToTerminal(
            currentCtx,
            RunStatus.Exhausted,
            summary = Some("Phase 3 completed: environment ready, verification not yet implemented"),
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
