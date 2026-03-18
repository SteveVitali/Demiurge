package lastmile.orchestrator

import lastmile.model._
import lastmile.inspector.RepoInspector
import lastmile.compiler.RequirementCompiler
import lastmile.planner.EnvironmentPlanner

// Spec §4.1: Minimal synchronous orchestrator loop for Phase 2.
// Executes the path: Created → InspectingRepo → CompilingRequirements →
// PlanningEnvironment → Exhausted using stubs for actual work.
// All transitions enforce persist-before-side-effects (Spec §4.1).
object RunOrchestrator {

  /**
   * Execute the minimal Phase 2 orchestration path.
   * Returns the final TaskRun state after reaching a terminal status.
   */
  def execute(
    ctx: RunContext,
    inspector: RepoInspector,
    compiler: RequirementCompiler,
    planner: EnvironmentPlanner,
  ): TaskRun = {
    // Register signal handler for interruption persistence (Spec §4.4)
    SignalHandler.register(ctx, ctx.repoRoot)

    var currentRun = ctx.run
    var currentCtx = ctx

    // Spec §4.1: State machine — Phase 2 minimal path
    // Created → InspectingRepo → CompilingRequirements → PlanningEnvironment → Exhausted

    // --- Transition: Created → InspectingRepo ---
    if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)
    if (currentRun.status == RunStatus.Created) {
      var inspectionResult: Option[RepoInspectionReport] = None

      currentRun = RunTransitionManager.transition(
        currentCtx,
        RunStatus.InspectingRepo,
        sideEffect = { updatedCtx =>
          inspectionResult = Some(inspector.inspect(
            currentRun.runId,
            currentCtx.repoRoot,
            currentRun.changedFiles,
          ))
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
          planResult = Some(planner.plan(
            currentRun.runId,
            inspectionResult.get,
            requirementResult.get,
          ))
        },
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)

      // --- Transition: PlanningEnvironment → Exhausted ---
      // Phase 2: No real environment boot or verification — go directly to Exhausted.
      if (SignalHandler.isInterrupted) return handleInterrupt(currentCtx)

      currentRun = RunTransitionManager.transitionToTerminal(
        currentCtx,
        RunStatus.Exhausted,
        summary = Some("Phase 2 stub run — no real verification performed"),
      )
      currentCtx = currentCtx.copy(run = currentRun)
      SignalHandler.updateContext(currentCtx)
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
