package demiurge.persistence

import java.sql.Connection
import java.time.Instant
import io.circe.Json
import io.circe.syntax._
import io.circe.parser._
import demiurge.model._
import demiurge.model.JsonCodecs._

object EventRepo {

  def insert(event: SystemEvent)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO events (
        |  event_id, run_id, attempt_number, event_type, component, severity,
        |  payload_json, correlation_fields_json, human_message, timestamp
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.stripMargin)
    try {
      ps.setString(1, event.eventId)
      ps.setString(2, event.runId)
      if (event.attemptNumber.isDefined) ps.setInt(3, event.attemptNumber.get)
      else ps.setNull(3, java.sql.Types.INTEGER)
      ps.setString(4, event.eventType)
      ps.setString(5, event.component)
      ps.setString(6, event.severity)
      ps.setString(7, event.payload.asJson.noSpaces)
      ps.setString(8, event.correlationFields.asJson.noSpaces)
      ps.setString(9, event.humanMessage)
      ps.setString(10, event.timestamp.toString)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def listByRunId(runId: String, limit: Int = 100)(implicit conn: Connection): List[SystemEvent] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM events WHERE run_id = ? ORDER BY timestamp ASC LIMIT ?")
    try {
      ps.setString(1, runId)
      ps.setInt(2, limit)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[SystemEvent]()
        while (rs.next()) {
          buf += rowToEvent(rs)
        }
        buf.toList
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def listByType(runId: String, eventType: String)(implicit conn: Connection): List[SystemEvent] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM events WHERE run_id = ? AND event_type = ? ORDER BY timestamp ASC")
    try {
      ps.setString(1, runId)
      ps.setString(2, eventType)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[SystemEvent]()
        while (rs.next()) {
          buf += rowToEvent(rs)
        }
        buf.toList
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  // Phase 7: Paginated event query for operator API
  def listByRunPaginated(runId: String, offset: Int, limit: Int)(implicit conn: Connection): List[SystemEvent] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM events WHERE run_id = ? ORDER BY timestamp ASC LIMIT ? OFFSET ?")
    try {
      ps.setString(1, runId)
      ps.setInt(2, limit)
      ps.setInt(3, offset)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[SystemEvent]()
        while (rs.next()) { buf += rowToEvent(rs) }
        buf.toList
      } finally { rs.close() }
    } finally { ps.close() }
  }

  // Phase 7: Count events for a run
  def countByRunId(runId: String)(implicit conn: Connection): Int = {
    val ps = conn.prepareStatement("SELECT COUNT(*) FROM events WHERE run_id = ?")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try { if (rs.next()) rs.getInt(1) else 0 }
      finally { rs.close() }
    } finally { ps.close() }
  }

  // Phase 7: Delete events for a run
  def deleteByRunId(runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("DELETE FROM events WHERE run_id = ?")
    try { ps.setString(1, runId); ps.executeUpdate() }
    finally { ps.close() }
  }

  private def rowToEvent(rs: java.sql.ResultSet): SystemEvent = {
    val payloadJson = rs.getString("payload_json")
    val payload: Map[String, Json] = decode[Map[String, Json]](payloadJson).getOrElse(Map.empty)

    val corrJson = Option(rs.getString("correlation_fields_json"))
    val correlationFields: Map[String, String] = corrJson
      .flatMap(j => decode[Map[String, String]](j).toOption)
      .getOrElse(Map.empty)

    val attemptNum = rs.getInt("attempt_number")
    val attemptNumber = if (rs.wasNull()) None else Some(attemptNum)

    SystemEvent(
      eventId = rs.getString("event_id"),
      runId = rs.getString("run_id"),
      attemptNumber = attemptNumber,
      eventType = rs.getString("event_type"),
      component = rs.getString("component"),
      severity = rs.getString("severity"),
      timestamp = Instant.parse(rs.getString("timestamp")),
      correlationFields = correlationFields,
      payload = payload,
      humanMessage = rs.getString("human_message"),
    )
  }
}
