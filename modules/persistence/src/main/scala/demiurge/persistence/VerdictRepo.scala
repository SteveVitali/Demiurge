package demiurge.persistence

import java.sql.Connection
import java.time.Instant
import io.circe.syntax._
import io.circe.parser._
import demiurge.model._
import demiurge.model.JsonCodecs._

// Phase 4: Persistence for RequirementVerdict records
object VerdictRepo {

  def insert(verdict: RequirementVerdict)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO requirement_verdicts (
        |  verdict_id, run_id, attempt_number, requirement_id, verifier_id,
        |  status, execution_duration_ms, retry_count, observations_json,
        |  evidence_refs_json, failure_class, failure_message,
        |  suggested_rerun_scope_json, confidence, produced_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.stripMargin)
    try {
      ps.setString(1, verdict.verdictId)
      ps.setString(2, verdict.runId)
      ps.setInt(3, verdict.attemptNumber)
      ps.setString(4, verdict.requirementId)
      ps.setString(5, verdict.verifierId)
      ps.setString(6, verdict.status.toString)
      ps.setLong(7, verdict.executionDurationMs)
      ps.setInt(8, verdict.retryCount)
      ps.setString(9, verdict.observations.asJson.noSpaces)
      ps.setString(10, verdict.evidenceRefs.asJson.noSpaces)
      ps.setString(11, verdict.failureClass.map(_.toString).orNull)
      ps.setString(12, verdict.failureMessage.orNull)
      ps.setString(13, verdict.suggestedRerunScope.map(_.asJson.noSpaces).orNull)
      ps.setDouble(14, verdict.confidence)
      ps.setString(15, verdict.producedAt.toString)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def insertAll(verdicts: List[RequirementVerdict])(implicit conn: Connection): Unit = {
    verdicts.foreach(insert)
  }

  def listByRunAndAttempt(runId: String, attemptNumber: Int)(implicit conn: Connection): List[RequirementVerdict] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM requirement_verdicts WHERE run_id = ? AND attempt_number = ? ORDER BY produced_at ASC")
    try {
      ps.setString(1, runId)
      ps.setInt(2, attemptNumber)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[RequirementVerdict]()
        while (rs.next()) {
          buf += rowToVerdict(rs)
        }
        buf.toList
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def listByRunId(runId: String)(implicit conn: Connection): List[RequirementVerdict] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM requirement_verdicts WHERE run_id = ? ORDER BY produced_at ASC")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[RequirementVerdict]()
        while (rs.next()) {
          buf += rowToVerdict(rs)
        }
        buf.toList
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  // Phase 7: Delete all verdicts for a run
  def deleteByRunId(runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("DELETE FROM requirement_verdicts WHERE run_id = ?")
    try { ps.setString(1, runId); ps.executeUpdate() }
    finally { ps.close() }
  }

  private def rowToVerdict(rs: java.sql.ResultSet): RequirementVerdict = {
    val observationsJson = rs.getString("observations_json")
    val observations = decode[List[Observation]](observationsJson).getOrElse(Nil)

    val evidenceRefsJson = rs.getString("evidence_refs_json")
    val evidenceRefs = decode[List[String]](evidenceRefsJson).getOrElse(Nil)

    val failureClassStr = Option(rs.getString("failure_class"))
    val failureClass = failureClassStr.flatMap(s => FailureClass.values.find(_.toString == s))

    val suggestedRerunJson = Option(rs.getString("suggested_rerun_scope_json"))
    val suggestedRerun = suggestedRerunJson.flatMap(j => decode[List[String]](j).toOption)

    RequirementVerdict(
      verdictId = rs.getString("verdict_id"),
      runId = rs.getString("run_id"),
      attemptNumber = rs.getInt("attempt_number"),
      requirementId = rs.getString("requirement_id"),
      verifierId = rs.getString("verifier_id"),
      status = {
        val s = rs.getString("status")
        VerdictStatus.values.find(_.toString == s)
          .getOrElse(throw new IllegalStateException(s"Unknown VerdictStatus in DB: $s"))
      },
      executionDurationMs = rs.getLong("execution_duration_ms"),
      retryCount = rs.getInt("retry_count"),
      observations = observations,
      evidenceRefs = evidenceRefs,
      failureClass = failureClass,
      failureMessage = Option(rs.getString("failure_message")),
      suggestedRerunScope = suggestedRerun,
      confidence = rs.getDouble("confidence"),
      producedAt = Instant.parse(rs.getString("produced_at")),
    )
  }
}
