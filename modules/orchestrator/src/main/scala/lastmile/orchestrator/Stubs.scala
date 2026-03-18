package lastmile.orchestrator

import java.nio.file.Path
import java.time.Instant

import lastmile.model._
import lastmile.inspector.RepoInspector
import lastmile.compiler.RequirementCompiler
import lastmile.planner.EnvironmentPlanner
import lastmile.runtime.RuntimeSupervisor

// Spec §5/§6/§8: Stub implementations for Phase 2/3.
// These return deterministic trivial results sufficient for the orchestrator
// to progress through the full state machine path.
// All timestamps use a fixed epoch to guarantee determinism.

object StubRepoInspector extends RepoInspector {
  override def inspect(runId: String, repoRoot: Path, changedFiles: Option[List[String]]): RepoInspectionReport = {
    RepoInspectionReport(
      reportId = s"stub-inspection-$runId",
      runId = runId,
      inspectedAt = Instant.EPOCH,
      repoRoot = repoRoot,
      languages = List(ScoredInference("unknown", 0.5, "stub")),
      frameworks = Nil,
      candidateServices = Nil,
      startupCommands = Nil,
      healthEndpointHints = Nil,
      dbDependencies = Nil,
      queueDependencies = Nil,
      frontendEntrypoints = Nil,
      apiBasePaths = Nil,
      testFrameworkHints = Nil,
      authHints = Nil,
      changedSurfaceMap = None,
      manifestsFound = Nil,
      warnings = List("Stub inspection — no real analysis performed"),
    )
  }
}

object StubRequirementCompiler extends RequirementCompiler {
  override def compile(runId: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph = {
    RequirementGraph(
      graphId = s"stub-graph-$runId",
      runId = runId,
      nodes = Nil,
      edges = Nil,
      generatedAt = Instant.EPOCH,
      inferenceRequestId = None,
      warnings = List(GraphWarning(
        code = "STUB",
        message = "Stub compilation — no real requirements produced",
        affectedNodeIds = Nil,
      )),
    )
  }
}

object StubEnvironmentPlanner extends EnvironmentPlanner {
  override def plan(runId: String, inspection: RepoInspectionReport, requirements: RequirementGraph): RuntimePlan = {
    RuntimePlan(
      planId = s"stub-plan-$runId",
      runId = runId,
      services = Nil,
      fixtureSteps = Nil,
      authBootstrapPlan = None,
      resetStrategy = ResetStrategy.SoftReset,
      teardownOrder = Nil,
      observabilityTaps = Nil,
      generatedAt = Instant.EPOCH,
      warnings = List("Stub plan — no real environment planning performed"),
    )
  }
}

object StubRuntimeSupervisor extends RuntimeSupervisor {
  override def bootEnvironment(plan: RuntimePlan, repoRoot: Path): RuntimeSupervisor.BootResult = {
    val snapshot = RuntimeSnapshot(
      snapshotId = s"stub-snapshot-${plan.runId}",
      runId = plan.runId,
      capturedAt = Instant.EPOCH,
      environmentStatus = EnvironmentStatus.Ready,
      services = Nil,
      activePortMappings = Map.empty,
      resolvedUrls = Map.empty,
      uptimeMs = 0L,
    )
    RuntimeSupervisor.BootSuccess(snapshot)
  }

  override def teardown(plan: RuntimePlan, repoRoot: Path): Unit = {
    // Stub: nothing to tear down
  }
}
