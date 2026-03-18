package demiurge.persistence

import java.sql.Connection
import java.time.Instant

import demiurge.model._

// Phase 6: ArtifactRecordRepo — persistence for artifact metadata in SQLite.
// Spec §12.3: Persist artifact metadata in SQLite immediately after successful writes.
object ArtifactRecordRepo {

  def insert(record: ArtifactRecord)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO artifact_records
        |(artifact_id, run_id, attempt_number, artifact_type, producer_component,
        | logical_scope, relative_path, content_type, size_bytes, checksum_sha256,
        | compressed, compression_format, created_at, metadata_json)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try {
      ps.setString(1, record.artifactId)
      ps.setString(2, record.runId)
      record.attemptNumber match {
        case Some(n) => ps.setInt(3, n)
        case None    => ps.setNull(3, java.sql.Types.INTEGER)
      }
      ps.setString(4, ArtifactTypeCodec.toString(record.artifactType))
      ps.setString(5, record.producerComponent)
      record.logicalScope match {
        case Some(s) => ps.setString(6, s)
        case None    => ps.setNull(6, java.sql.Types.VARCHAR)
      }
      ps.setString(7, record.relativePath)
      ps.setString(8, record.contentType)
      ps.setLong(9, record.sizeBytes)
      ps.setString(10, record.checksumSha256)
      ps.setInt(11, if (record.compressed) 1 else 0)
      record.compressionFormat match {
        case Some(f) => ps.setString(12, f)
        case None    => ps.setNull(12, java.sql.Types.VARCHAR)
      }
      ps.setString(13, record.createdAt.toString)
      val metadataJson = if (record.metadata.isEmpty) null
        else "{" + record.metadata.map { case (k, v) => s""""$k":"$v"""" }.mkString(",") + "}"
      ps.setString(14, metadataJson)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def insertAll(records: List[ArtifactRecord])(implicit conn: Connection): Unit = {
    records.foreach(insert)
  }

  def getById(artifactId: String)(implicit conn: Connection): Option[ArtifactRecord] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM artifact_records WHERE artifact_id = ?"
    )
    try {
      ps.setString(1, artifactId)
      val rs = ps.executeQuery()
      if (rs.next()) Some(readRow(rs)) else None
    } finally {
      ps.close()
    }
  }

  def listByRunId(runId: String)(implicit conn: Connection): List[ArtifactRecord] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM artifact_records WHERE run_id = ? ORDER BY created_at"
    )
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer[ArtifactRecord]()
      while (rs.next()) buf += readRow(rs)
      buf.toList
    } finally {
      ps.close()
    }
  }

  def listByRunAndAttempt(runId: String, attemptNumber: Int)(implicit conn: Connection): List[ArtifactRecord] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM artifact_records WHERE run_id = ? AND attempt_number = ? ORDER BY created_at"
    )
    try {
      ps.setString(1, runId)
      ps.setInt(2, attemptNumber)
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer[ArtifactRecord]()
      while (rs.next()) buf += readRow(rs)
      buf.toList
    } finally {
      ps.close()
    }
  }

  def listByRunAndType(runId: String, artifactType: ArtifactType)(implicit conn: Connection): List[ArtifactRecord] = {
    val ps = conn.prepareStatement(
      "SELECT * FROM artifact_records WHERE run_id = ? AND artifact_type = ? ORDER BY created_at"
    )
    try {
      ps.setString(1, runId)
      ps.setString(2, ArtifactTypeCodec.toString(artifactType))
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer[ArtifactRecord]()
      while (rs.next()) buf += readRow(rs)
      buf.toList
    } finally {
      ps.close()
    }
  }

  // Phase 7: Paginated artifact listing for operator API
  def listByRunPaginated(runId: String, offset: Int, limit: Int, artifactType: Option[ArtifactType] = None, attemptNumber: Option[Int] = None)(implicit conn: Connection): List[ArtifactRecord] = {
    val conditions = scala.collection.mutable.ListBuffer[String]("run_id = ?")
    artifactType.foreach(_ => conditions += "artifact_type = ?")
    attemptNumber.foreach(_ => conditions += "attempt_number = ?")
    val sql = s"SELECT * FROM artifact_records WHERE ${conditions.mkString(" AND ")} ORDER BY created_at LIMIT ? OFFSET ?"
    val ps = conn.prepareStatement(sql)
    try {
      var idx = 1
      ps.setString(idx, runId); idx += 1
      artifactType.foreach { t => ps.setString(idx, ArtifactTypeCodec.toString(t)); idx += 1 }
      attemptNumber.foreach { n => ps.setInt(idx, n); idx += 1 }
      ps.setInt(idx, limit); idx += 1
      ps.setInt(idx, offset)
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer[ArtifactRecord]()
      while (rs.next()) buf += readRow(rs)
      buf.toList
    } finally { ps.close() }
  }

  // Phase 7: Count artifacts for a run
  def countByRunId(runId: String)(implicit conn: Connection): Int = {
    val ps = conn.prepareStatement("SELECT COUNT(*) FROM artifact_records WHERE run_id = ?")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try { if (rs.next()) rs.getInt(1) else 0 }
      finally { rs.close() }
    } finally { ps.close() }
  }

  // Phase 7: Delete artifact records for a run
  def deleteByRunId(runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("DELETE FROM artifact_records WHERE run_id = ?")
    try { ps.setString(1, runId); ps.executeUpdate() }
    finally { ps.close() }
  }

  private def readRow(rs: java.sql.ResultSet): ArtifactRecord = {
    val attemptNum = rs.getInt("attempt_number")
    val attemptNumberOpt = if (rs.wasNull()) None else Some(attemptNum)
    val metadataJson = rs.getString("metadata_json")
    val metadata: Map[String, String] = if (metadataJson == null || metadataJson.isEmpty) Map.empty
      else parseSimpleJsonMap(metadataJson)

    ArtifactRecord(
      artifactId        = rs.getString("artifact_id"),
      runId             = rs.getString("run_id"),
      attemptNumber     = attemptNumberOpt,
      artifactType      = ArtifactTypeCodec.fromString(rs.getString("artifact_type")),
      producerComponent = rs.getString("producer_component"),
      logicalScope      = Option(rs.getString("logical_scope")),
      relativePath      = rs.getString("relative_path"),
      contentType       = rs.getString("content_type"),
      sizeBytes         = rs.getLong("size_bytes"),
      checksumSha256    = rs.getString("checksum_sha256"),
      compressed        = rs.getInt("compressed") == 1,
      compressionFormat = Option(rs.getString("compression_format")),
      createdAt         = Instant.parse(rs.getString("created_at")),
      metadata          = metadata,
    )
  }

  private def parseSimpleJsonMap(json: String): Map[String, String] = {
    // Minimal JSON map parser for {"key":"value",...} format
    val trimmed = json.trim
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return Map.empty
    val inner = trimmed.drop(1).dropRight(1).trim
    if (inner.isEmpty) return Map.empty
    inner.split(",").flatMap { pair =>
      val parts = pair.split(":", 2)
      if (parts.length == 2) {
        val k = parts(0).trim.stripPrefix("\"").stripSuffix("\"")
        val v = parts(1).trim.stripPrefix("\"").stripSuffix("\"")
        Some(k -> v)
      } else None
    }.toMap
  }

}
