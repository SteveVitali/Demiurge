package lastmile.persistence

import java.sql.Connection
import java.time.Instant
import io.circe.syntax._
import io.circe.parser._
import lastmile.model._
import lastmile.model.JsonCodecs._

object AttemptRepo {

  def insert(attempt: Attempt)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO attempts (
        |  attempt_id, run_id, attempt_number, status, started_at, ended_at,
        |  repair_backend, patch_record_id, failure_packet_id, rerun_plan_id,
        |  repair_retries_used, verdict_summary_json
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.stripMargin)
    try {
      ps.setString(1, attempt.attemptId)
      ps.setString(2, attempt.runId)
      ps.setInt(3, attempt.attemptNumber)
      ps.setString(4, attempt.status.toString)
      ps.setString(5, attempt.startedAt.toString)
      ps.setString(6, attempt.endedAt.map(_.toString).orNull)
      ps.setString(7, attempt.repairBackend.orNull)
      ps.setString(8, attempt.patchRecordId.orNull)
      ps.setString(9, attempt.failurePacketId.orNull)
      ps.setString(10, attempt.rerunPlanId.orNull)
      ps.setInt(11, attempt.repairRetriesUsed)
      ps.setString(12, attempt.verdictSummary.map(_.asJson.noSpaces).orNull)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def getById(attemptId: String)(implicit conn: Connection): Option[Attempt] = {
    val ps = conn.prepareStatement("SELECT * FROM attempts WHERE attempt_id = ?")
    try {
      ps.setString(1, attemptId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(rowToAttempt(rs)) else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def getByRunAndNumber(runId: String, attemptNumber: Int)(implicit conn: Connection): Option[Attempt] = {
    val ps = conn.prepareStatement("SELECT * FROM attempts WHERE run_id = ? AND attempt_number = ?")
    try {
      ps.setString(1, runId)
      ps.setInt(2, attemptNumber)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(rowToAttempt(rs)) else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def updateStatus(attemptId: String, status: AttemptStatus, endedAt: Option[Instant] = None)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("UPDATE attempts SET status = ?, ended_at = ? WHERE attempt_id = ?")
    try {
      ps.setString(1, status.toString)
      ps.setString(2, endedAt.map(_.toString).orNull)
      ps.setString(3, attemptId)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  private def rowToAttempt(rs: java.sql.ResultSet): Attempt = {
    val summaryJson = Option(rs.getString("verdict_summary_json"))
    val verdictSummary = summaryJson.flatMap { json =>
      decode[AttemptVerdictSummary](json).toOption
    }

    Attempt(
      attemptId = rs.getString("attempt_id"),
      runId = rs.getString("run_id"),
      attemptNumber = rs.getInt("attempt_number"),
      status = {
        val s = rs.getString("status")
        AttemptStatus.values.find(_.toString == s)
          .getOrElse(throw new IllegalStateException(s"Unknown AttemptStatus in DB: $s"))
      },
      startedAt = Instant.parse(rs.getString("started_at")),
      endedAt = Option(rs.getString("ended_at")).map(Instant.parse),
      repairBackend = Option(rs.getString("repair_backend")),
      patchRecordId = Option(rs.getString("patch_record_id")),
      failurePacketId = Option(rs.getString("failure_packet_id")),
      rerunPlanId = Option(rs.getString("rerun_plan_id")),
      repairRetriesUsed = rs.getInt("repair_retries_used"),
      verdictSummary = verdictSummary,
    )
  }
}
