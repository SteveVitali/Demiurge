package lastmile.repair

import java.nio.file.Path

import lastmile.model._

// Phase 5: Context provided to the repair backend alongside the FailurePacket.
// Contains the additional information needed for repair: task text, repo files,
// requirements, verifier failures, worktree path, and patch history.
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
)
