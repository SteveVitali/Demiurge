package demiurge.persistence

import java.sql.Connection
import java.time.Instant

// Phase 5: Persistence for patch records (repair patches applied to worktree)
object PatchRepo {

  case class PatchRecord(
    patchRecordId:        String,
    runId:                String,
    attemptNumber:        Int,
    diffArtifactId:       Option[String] = None,
    filesChangedJson:     String,
    totalLinesAdded:      Int,
    totalLinesRemoved:    Int,
    repairBackend:        String,
    repairSummary:        String,
    hypothesesJson:       String,
    requiresEnvRebuild:   Boolean,
    infraSensitiveFiles:  List[String] = Nil,
    transcriptArtifactId: Option[String] = None,
    usageRecordId:        Option[String] = None,
    appliedAt:            Instant,
    patchApplicationMethod: String = "direct_write",
    preApplyCommitSha:    Option[String] = None,
    postApplyCommitSha:   Option[String] = None,
  )

  def insert(record: PatchRecord)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO patch_records (
        |  patch_record_id, run_id, attempt_number, diff_artifact_id,
        |  files_changed_json, total_lines_added, total_lines_removed,
        |  repair_backend, repair_summary, hypotheses_json,
        |  requires_env_rebuild, infra_sensitive_files_json,
        |  transcript_artifact_id, usage_record_id, applied_at,
        |  patch_application_method, pre_apply_commit_sha, post_apply_commit_sha
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.stripMargin)
    try {
      ps.setString(1, record.patchRecordId)
      ps.setString(2, record.runId)
      ps.setInt(3, record.attemptNumber)
      ps.setString(4, record.diffArtifactId.getOrElse(""))
      ps.setString(5, record.filesChangedJson)
      ps.setInt(6, record.totalLinesAdded)
      ps.setInt(7, record.totalLinesRemoved)
      ps.setString(8, record.repairBackend)
      ps.setString(9, record.repairSummary)
      ps.setString(10, record.hypothesesJson)
      ps.setInt(11, if (record.requiresEnvRebuild) 1 else 0)
      ps.setString(12, infraSensitiveToJson(record.infraSensitiveFiles))
      ps.setString(13, record.transcriptArtifactId.orNull)
      ps.setString(14, record.usageRecordId.getOrElse(""))
      ps.setString(15, record.appliedAt.toString)
      ps.setString(16, record.patchApplicationMethod)
      ps.setString(17, record.preApplyCommitSha.getOrElse(""))
      ps.setString(18, record.postApplyCommitSha.orNull)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def deleteByRunId(runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("DELETE FROM patch_records WHERE run_id = ?")
    try { ps.setString(1, runId); ps.executeUpdate() }
    finally { ps.close() }
  }

  def getById(patchRecordId: String)(implicit conn: Connection): Option[PatchRecord] = {
    val ps = conn.prepareStatement("SELECT * FROM patch_records WHERE patch_record_id = ?")
    try {
      ps.setString(1, patchRecordId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(rowToRecord(rs)) else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def listByRunId(runId: String)(implicit conn: Connection): List[PatchRecord] = {
    val ps = conn.prepareStatement("SELECT * FROM patch_records WHERE run_id = ? ORDER BY applied_at ASC")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[PatchRecord]()
        while (rs.next()) {
          buf += rowToRecord(rs)
        }
        buf.toList
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  // Desktop Phase 2: List patches for a specific run + attempt
  def listByRunAndAttempt(runId: String, attemptNumber: Int)(implicit conn: Connection): List[PatchRecord] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM patch_records WHERE run_id = ? AND attempt_number = ? ORDER BY applied_at ASC")
    try {
      ps.setString(1, runId)
      ps.setInt(2, attemptNumber)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[PatchRecord]()
        while (rs.next()) {
          buf += rowToRecord(rs)
        }
        buf.toList
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  private def rowToRecord(rs: java.sql.ResultSet): PatchRecord = {
    PatchRecord(
      patchRecordId = rs.getString("patch_record_id"),
      runId = rs.getString("run_id"),
      attemptNumber = rs.getInt("attempt_number"),
      diffArtifactId = Option(rs.getString("diff_artifact_id")).filter(_.nonEmpty),
      filesChangedJson = rs.getString("files_changed_json"),
      totalLinesAdded = rs.getInt("total_lines_added"),
      totalLinesRemoved = rs.getInt("total_lines_removed"),
      repairBackend = rs.getString("repair_backend"),
      repairSummary = rs.getString("repair_summary"),
      hypothesesJson = rs.getString("hypotheses_json"),
      requiresEnvRebuild = rs.getInt("requires_env_rebuild") != 0,
      infraSensitiveFiles = jsonToInfraSensitive(rs.getString("infra_sensitive_files_json")),
      transcriptArtifactId = Option(rs.getString("transcript_artifact_id")),
      usageRecordId = Option(rs.getString("usage_record_id")).filter(_.nonEmpty),
      appliedAt = Instant.parse(rs.getString("applied_at")),
      patchApplicationMethod = Option(rs.getString("patch_application_method")).getOrElse("direct_write"),
      preApplyCommitSha = Option(rs.getString("pre_apply_commit_sha")).filter(_.nonEmpty),
      postApplyCommitSha = Option(rs.getString("post_apply_commit_sha")),
    )
  }

  private def infraSensitiveToJson(files: List[String]): String = {
    if (files.isEmpty) "[]"
    else files.map(f => s""""$f"""").mkString("[", ",", "]")
  }

  private def jsonToInfraSensitive(json: String): List[String] = {
    if (json == null || json.isEmpty || json == "[]") Nil
    else {
      // Simple JSON array parsing
      json.stripPrefix("[").stripSuffix("]").split(",")
        .map(_.trim.stripPrefix("\"").stripSuffix("\""))
        .filter(_.nonEmpty).toList
    }
  }
}
