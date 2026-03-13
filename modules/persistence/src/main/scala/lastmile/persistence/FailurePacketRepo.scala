package lastmile.persistence

import java.sql.Connection
import io.circe.syntax._
import io.circe.parser._
import lastmile.model._
import lastmile.model.JsonCodecs._

// Phase 5: Persistence for FailurePacket records
object FailurePacketRepo {

  def insert(packet: FailurePacket)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO failure_packets (
        |  failure_packet_id, run_id, attempt_number, packet_json, produced_at
        |) VALUES (?, ?, ?, ?, ?)
      """.stripMargin)
    try {
      ps.setString(1, packet.failurePacketId)
      ps.setString(2, packet.runId)
      ps.setInt(3, packet.attemptNumber)
      ps.setString(4, packet.asJson.noSpaces)
      ps.setString(5, packet.producedAt.toString)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def getById(failurePacketId: String)(implicit conn: Connection): Option[FailurePacket] = {
    val ps = conn.prepareStatement("SELECT packet_json FROM failure_packets WHERE failure_packet_id = ?")
    try {
      ps.setString(1, failurePacketId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) {
          decode[FailurePacket](rs.getString("packet_json")).toOption
        } else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def getByRunAndAttempt(runId: String, attemptNumber: Int)(implicit conn: Connection): Option[FailurePacket] = {
    val ps = conn.prepareStatement(
      "SELECT packet_json FROM failure_packets WHERE run_id = ? AND attempt_number = ? ORDER BY produced_at DESC LIMIT 1")
    try {
      ps.setString(1, runId)
      ps.setInt(2, attemptNumber)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) {
          decode[FailurePacket](rs.getString("packet_json")).toOption
        } else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }
}
