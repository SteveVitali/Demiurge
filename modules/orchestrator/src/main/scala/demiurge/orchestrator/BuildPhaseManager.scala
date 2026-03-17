package demiurge.orchestrator

import java.sql.Connection
import java.time.Instant
import java.util.UUID

import demiurge.model._
import demiurge.persistence._
import demiurge.repair._

// Phase B: BuildPhaseManager handles the PlanningFeature → GeneratingCode states
// in Build mode. Uses the RepairBackend for initial code generation (same interface,
// different prompt context via GenerationMode.InitialBuild).
object BuildPhaseManager {

  /**
   * Plan the feature implementation via LLM.
   * Returns a FeaturePlan describing what files to create/modify/delete.
   *
   * Creates a plan from the RequirementGraph structure. In the future,
   * this will use the LLM for richer planning.
   */
  def planFeature(
    runId: String,
    taskText: String,
    inspection: RepoInspectionReport,
    graph: RequirementGraph,
  )(implicit conn: Connection): FeaturePlan = {
    val plan = FeaturePlan(
      planId = s"fplan-$runId-${UUID.randomUUID().toString.take(8)}",
      runId = runId,
      taskText = taskText,
      summary = s"Implementation plan for: $taskText",
      filesToCreate = graph.nodes.flatMap { node =>
        node.category match {
          case RequirementCategory.ApiContract =>
            List(PlannedFile(
              relativePath = s"src/api/${node.requirementId}.ts",
              description = node.humanDescription,
              category = "route",
            ))
          case RequirementCategory.UiFlow =>
            List(PlannedFile(
              relativePath = s"src/pages/${node.requirementId}.tsx",
              description = node.humanDescription,
              category = "component",
            ))
          case _ => Nil
        }
      },
      filesToModify = Nil,
      filesToDelete = Nil,
      requiresNewDeps = Nil,
      requiresMigration = graph.nodes.exists(_.category == RequirementCategory.PersistenceState),
      estimatedComplexity = if (graph.nodes.size <= 3) "small" else if (graph.nodes.size <= 6) "medium" else "large",
      createdAt = Instant.now(),
    )

    FeaturePlanRepo.insert(plan)
    plan
  }

  /**
   * Generate initial code for the feature via the repair backend.
   * Uses GenerationMode.InitialBuild to select the appropriate prompt.
   * Returns the RepairExecutor outcome (patch applied or rejected).
   */
  def generateCode(
    ctx: RunContext,
    backend: RepairBackend,
    taskText: String,
    featurePlan: FeaturePlan,
    inspection: RepoInspectionReport,
    graph: RequirementGraph,
    runtimePlan: Option[RuntimePlan],
  ): RepairExecutor.RepairOutcome = {
    // Build a FailurePacketInput that describes what needs to be built
    val buildInput = FailurePacketBuilder.FailurePacketInput(
      runId = ctx.run.runId,
      attemptNumber = 0,
      taskText = taskText,
      verdicts = Nil,
      graph = graph,
      inspectionReport = Some(inspection),
      runtimePlan = runtimePlan,
      patchHistory = Nil,
      logs = None,
    )

    // Build repair context with GenerationMode.InitialBuild
    val repairContext = RepairContext(
      runId = ctx.run.runId,
      attemptNumber = 0,
      taskText = taskText,
      worktreePath = ctx.worktreePath,
      graph = graph,
      verdicts = Nil,
      inspectionReport = Some(inspection),
      runtimePlan = runtimePlan,
      patchHistory = Nil,
      generationMode = GenerationMode.InitialBuild,
      featureSpec = Some(taskText),
      featurePlan = Some(featurePlan),
    )

    RepairExecutor.executeRepair(backend, ctx.worktreePath, buildInput, repairContext)
  }
}
