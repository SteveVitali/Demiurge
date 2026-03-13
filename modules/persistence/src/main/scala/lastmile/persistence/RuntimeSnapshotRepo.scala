package lastmile.persistence

import java.sql.Connection
import io.circe.syntax._
import io.circe.parser._
import lastmile.model._
import lastmile.model.JsonCodecs._

// Spec §7.2: RuntimeSnapshot persistence for Phase 3.
object RuntimeSnapshotRepo {

  def insert(snapshot: RuntimeSnapshot)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO runtime_snapshots (snapshot_id, run_id, snapshot_json, captured_at)
        |VALUES (?, ?, ?, ?)""".stripMargin)
    try {
      ps.setString(1, snapshot.snapshotId)
      ps.setString(2, snapshot.runId)
      ps.setString(3, snapshot.asJson.noSpaces)
      ps.setString(4, snapshot.capturedAt.toString)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def getById(snapshotId: String)(implicit conn: Connection): Option[RuntimeSnapshot] = {
    val ps = conn.prepareStatement("SELECT snapshot_json FROM runtime_snapshots WHERE snapshot_id = ?")
    try {
      ps.setString(1, snapshotId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) decode[RuntimeSnapshot](rs.getString("snapshot_json")).toOption
        else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def getByRunId(runId: String)(implicit conn: Connection): List[RuntimeSnapshot] = {
    val ps = conn.prepareStatement(
      "SELECT snapshot_json FROM runtime_snapshots WHERE run_id = ? ORDER BY captured_at ASC")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[RuntimeSnapshot]()
        while (rs.next()) {
          decode[RuntimeSnapshot](rs.getString("snapshot_json")).foreach(buf += _)
        }
        buf.toList
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }
}
