package demiurge.orchestrator

import java.sql.Connection

import demiurge.model._
import demiurge.persistence._
import demiurge.repair.PatchProposal

// Gap 6: Loads persisted run state for resume.
// When resuming from an intermediate state, the orchestrator needs access
// to previously computed artifacts (inspection, graph, plan, patches, etc.).
object ResumeDataLoader {

  case class ResumeData(
    inspection:        Option[RepoInspectionReport],
    graph:             Option[RequirementGraph],
    plan:              Option[RuntimePlan],
    patchHistory:      List[PatchProposal],
    lastAttemptNumber: Int,
  )

  def load(runId: String)(implicit conn: Connection): ResumeData = {
    val inspection = RepoInspectionReportRepo.getByRunId(runId)
    val graph = RequirementGraphRepo.getByRunId(runId)
    val plan = RuntimePlanRepo.getByRunId(runId)

    // Reconstruct minimal PatchProposal stubs from persisted PatchRecords.
    // Full PatchProposal content (edits, newFiles, deletions) is not stored
    // in PatchRepo — only metadata. For resume purposes the orchestrator
    // needs the list length and attempt numbers, not the actual diffs.
    val patchRecords = PatchRepo.listByRunId(runId)
    val patchHistory = patchRecords.map { rec =>
      PatchProposal(
        patchId = rec.patchRecordId,
        runId = rec.runId,
        attemptNumber = rec.attemptNumber,
        backendId = rec.repairBackend,
        edits = Nil,
        newFiles = Nil,
        deletions = Nil,
        summary = rec.repairSummary,
        hypotheses = Nil,
        createdAt = rec.appliedAt,
      )
    }

    val attempts = AttemptRepo.listByRunId(runId)
    val lastAttemptNumber = if (attempts.nonEmpty) attempts.map(_.attemptNumber).max else 0

    ResumeData(
      inspection = inspection,
      graph = graph,
      plan = plan,
      patchHistory = patchHistory,
      lastAttemptNumber = lastAttemptNumber,
    )
  }
}
