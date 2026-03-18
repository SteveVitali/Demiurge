package lastmile.persistence

import java.sql.Connection
import io.circe.syntax._
import io.circe.parser._
import lastmile.model._
import lastmile.model.JsonCodecs._

// Spec §7.2: RepoInspectionReport persistence for Phase 3.
object RepoInspectionReportRepo {

  def insert(report: RepoInspectionReport)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO repo_inspection_reports (report_id, run_id, report_json, inspected_at)
        |VALUES (?, ?, ?, ?)""".stripMargin)
    try {
      ps.setString(1, report.reportId)
      ps.setString(2, report.runId)
      ps.setString(3, report.asJson.noSpaces)
      ps.setString(4, report.inspectedAt.toString)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def deleteByRunId(runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("DELETE FROM repo_inspection_reports WHERE run_id = ?")
    try { ps.setString(1, runId); ps.executeUpdate() }
    finally { ps.close() }
  }

  def getById(reportId: String)(implicit conn: Connection): Option[RepoInspectionReport] = {
    val ps = conn.prepareStatement("SELECT report_json FROM repo_inspection_reports WHERE report_id = ?")
    try {
      ps.setString(1, reportId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) decode[RepoInspectionReport](rs.getString("report_json")).toOption
        else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def getByRunId(runId: String)(implicit conn: Connection): Option[RepoInspectionReport] = {
    val ps = conn.prepareStatement(
      "SELECT report_json FROM repo_inspection_reports WHERE run_id = ? ORDER BY inspected_at DESC LIMIT 1")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) decode[RepoInspectionReport](rs.getString("report_json")).toOption
        else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }
}
