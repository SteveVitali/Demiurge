package demiurge.repair

import java.nio.file.Path

import demiurge.model._

// Phase 5: Context provided to the repair backend alongside the FailurePacket.
// Contains the additional information needed for repair: task text, repo files,
// requirements, verifier failures, worktree path, and patch history.
// Phase B: Extended with generationMode, featureSpec, featurePlan for Build mode.
case class RepairContext(
  runId:              String,
  attemptNumber:      Int,
  taskText:           String,
  worktreePath:       Path,
  graph:              RequirementGraph,
  verdicts:           List[RequirementVerdict],
  inspectionReport:   Option[RepoInspectionReport],
  runtimePlan:        Option[RuntimePlan],
  patchHistory:       List[PatchProposal],
  generationMode:     GenerationMode = GenerationMode.Repair,
  featureSpec:        Option[String] = None,
  featurePlan:        Option[FeaturePlan] = None,
  logs:               Option[String] = None,
)
