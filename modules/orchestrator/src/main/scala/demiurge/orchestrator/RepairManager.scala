package demiurge.orchestrator

import java.sql.Connection
import java.time.Instant

import io.circe.syntax._
import demiurge.model._
import demiurge.model.JsonCodecs._
import demiurge.persistence._
import demiurge.repair._

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
    logs: Option[String] = None,
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
      logs = logs,
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

  // Spec §10.6: Persist repair transcript as artifact and update patch record with commit SHAs
  def persistRepairTranscript(
    runId: String,
    attemptNumber: Int,
    transcriptJson: String,
    preCommitSha: Option[String],
    postCommitSha: Option[String],
  )(implicit conn: Connection): Unit = {
    // Write transcript artifact record
    val artifactId = java.util.UUID.randomUUID().toString
    ArtifactRecordRepo.insert(ArtifactRecord(
      artifactId = artifactId,
      runId = runId,
      attemptNumber = Some(attemptNumber),
      artifactType = ArtifactType.RepairTranscript,
      producerComponent = "repair_session",
      logicalScope = Some(s"attempt_$attemptNumber"),
      relativePath = s"$runId/attempt_$attemptNumber/repair_transcript.json",
      contentType = "application/json",
      sizeBytes = transcriptJson.length.toLong,
      checksumSha256 = "",
      compressed = false,
      compressionFormat = None,
      createdAt = Instant.now(),
      metadata = Map.empty,
    ))

    // Update matching patch record with commit SHAs and transcript artifact ID
    val ps = conn.prepareStatement(
      """UPDATE patch_records SET
        |  transcript_artifact_id = ?,
        |  pre_apply_commit_sha = COALESCE(?, pre_apply_commit_sha),
        |  post_apply_commit_sha = COALESCE(?, post_apply_commit_sha)
        |WHERE run_id = ? AND attempt_number = ?
      """.stripMargin)
    try {
      ps.setString(1, artifactId)
      ps.setString(2, preCommitSha.orNull)
      ps.setString(3, postCommitSha.orNull)
      ps.setString(4, runId)
      ps.setInt(5, attemptNumber)
      ps.executeUpdate()
    } finally {
      ps.close()
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
