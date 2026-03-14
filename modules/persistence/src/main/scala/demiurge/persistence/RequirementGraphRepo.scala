package demiurge.persistence

import java.sql.Connection
import io.circe.syntax._
import io.circe.parser._
import demiurge.model._
import demiurge.model.JsonCodecs._

// Phase 4: RequirementGraph persistence
object RequirementGraphRepo {

  def insert(graph: RequirementGraph)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO requirement_graphs (graph_id, run_id, graph_json, generated_at, inference_request_id)
        |VALUES (?, ?, ?, ?, ?)""".stripMargin)
    try {
      ps.setString(1, graph.graphId)
      ps.setString(2, graph.runId)
      ps.setString(3, graph.asJson.noSpaces)
      ps.setString(4, graph.generatedAt.toString)
      ps.setString(5, graph.inferenceRequestId.orNull)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def deleteByRunId(runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("DELETE FROM requirement_graphs WHERE run_id = ?")
    try { ps.setString(1, runId); ps.executeUpdate() }
    finally { ps.close() }
  }

  def getByRunId(runId: String)(implicit conn: Connection): Option[RequirementGraph] = {
    val ps = conn.prepareStatement(
      "SELECT graph_json FROM requirement_graphs WHERE run_id = ? ORDER BY generated_at DESC LIMIT 1")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) decode[RequirementGraph](rs.getString("graph_json")).toOption
        else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }
}
