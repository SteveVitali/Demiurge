package lastmile.orchestrator

import java.sql.Connection
import java.time.Instant

import io.circe.syntax._
import lastmile.model._
import lastmile.model.JsonCodecs._
import lastmile.persistence._
import lastmile.repair._

// Phase 5: RepairManager — manages the single repair attempt within a run.
// Builds failure packets, invokes repair backend, persists patch records,
// and updates attempt state.
object RepairManager {

  // Build a FailurePacketBuilder.FailurePacketInput from run context
  def buildFailureInput(
    runId: String,
    attemptNumber: Int,
    taskText: String,
    verdicts: List[RequirementVerdict],
    graph: RequirementGraph,
    inspectionReport: Option[RepoInspectionReport],
    runtimePlan: Option[RuntimePlan],
    patchHistory: List[PatchProposal],
    logs: Option[String],
  ): FailurePacketBuilder.FailurePacketInput = {
    FailurePacketBuilder.FailurePacketInput(
      runId = runId,
      attemptNumber = attemptNumber,
      taskText = taskText,
      verdicts = verdicts,
      graph = graph,
      inspectionReport = inspectionReport,
      runtimePlan = runtimePlan,
      patchHistory = patchHistory,
      logs = logs,
    )
  }

  // Build a RepairContext from run context
  def buildRepairContext(
    ctx: RunContext,
    attemptNumber: Int,
    graph: RequirementGraph,
    verdicts: List[RequirementVerdict],
    inspectionReport: Option[RepoInspectionReport],
    runtimePlan: Option[RuntimePlan],
    patchHistory: List[PatchProposal],
  ): RepairContext = {
    RepairContext(
      runId = ctx.run.runId,
      attemptNumber = attemptNumber,
      taskText = ctx.run.taskText,
      worktreePath = ctx.worktreePath,
      graph = graph,
      verdicts = verdicts,
      inspectionReport = inspectionReport,
      runtimePlan = runtimePlan,
      patchHistory = patchHistory,
    )
  }

  // Persist a failure packet
  def persistFailurePacket(packet: FailurePacket)(implicit conn: Connection): Unit = {
    FailurePacketRepo.insert(packet)
  }

  // Persist a patch record from a PatchProposal
  def persistPatchRecord(proposal: PatchProposal)(implicit conn: Connection): PatchRepo.PatchRecord = {
    val record = PatchRepo.PatchRecord(
      patchRecordId = proposal.patchId,
      runId = proposal.runId,
      attemptNumber = proposal.attemptNumber,
      filesChangedJson = proposal.filesChanged.asJson.noSpaces,
      totalLinesAdded = proposal.totalLinesAdded,
      totalLinesRemoved = proposal.totalLinesRemoved,
      repairBackend = proposal.backendId,
      repairSummary = proposal.summary,
      hypothesesJson = proposal.hypotheses.asJson.noSpaces,
      requiresEnvRebuild = false,
      appliedAt = Instant.now(),
    )
    PatchRepo.insert(record)
    record
  }

  // Update attempt with repair info
  def markAttemptRepairing(attemptId: String)(implicit conn: Connection): Unit = {
    AttemptRepo.updateStatus(attemptId, AttemptStatus.Repairing)
  }

  // Update attempt with repair success
  def markAttemptRepairSucceeded(
    attemptId: String,
    patchRecordId: String,
    failurePacketId: String,
    repairBackendId: String,
  )(implicit conn: Connection): Unit = {
    TransactionManager.atomic(conn) { txn =>
      AttemptRepo.updateStatus(attemptId, AttemptStatus.RepairSucceeded)(txn)
      val ps = txn.prepareStatement(
        "UPDATE attempts SET patch_record_id = ?, failure_packet_id = ?, repair_backend = ? WHERE attempt_id = ?")
      try {
        ps.setString(1, patchRecordId)
        ps.setString(2, failurePacketId)
        ps.setString(3, repairBackendId)
        ps.setString(4, attemptId)
        ps.executeUpdate()
      } finally {
        ps.close()
      }
    }
  }

  // Update attempt with repair failure
  def markAttemptRepairFailed(attemptId: String, failurePacketId: String)(implicit conn: Connection): Unit = {
    TransactionManager.atomic(conn) { txn =>
      AttemptRepo.updateStatus(attemptId, AttemptStatus.RepairFailed, endedAt = Some(Instant.now()))(txn)
      val ps = txn.prepareStatement(
        "UPDATE attempts SET failure_packet_id = ? WHERE attempt_id = ?")
      try {
        ps.setString(1, failurePacketId)
        ps.setString(2, attemptId)
        ps.executeUpdate()
      } finally {
        ps.close()
      }
    }
  }
}
