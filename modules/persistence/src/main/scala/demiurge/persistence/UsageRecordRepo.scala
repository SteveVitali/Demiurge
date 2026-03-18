package demiurge.persistence

import java.sql.Connection
import java.time.Instant

import demiurge.model._

// Spec §5.9: Persist UsageRecords to SQLite usage_records table.
object UsageRecordRepo {

  def insert(record: UsageRecord)(implicit conn: Connection): Unit = {
    val sql =
      """INSERT INTO usage_records (
        |  usage_record_id, run_id, attempt_number, component, provider, model,
        |  input_tokens, output_tokens, total_tokens, duration_ms,
        |  estimated_cost_usd, request_count, cached_tokens, created_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin

    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, record.usageRecordId)
      stmt.setString(2, record.runId)
      record.attemptNumber match {
        case Some(n) => stmt.setInt(3, n)
        case None    => stmt.setNull(3, java.sql.Types.INTEGER)
      }
      stmt.setString(4, record.component)
      stmt.setString(5, record.provider.toString)
      stmt.setString(6, record.model)
      stmt.setLong(7, record.inputTokens)
      stmt.setLong(8, record.outputTokens)
      stmt.setLong(9, record.totalTokens)
      stmt.setLong(10, record.durationMs)
      record.estimatedCostUsd match {
        case Some(cost) => stmt.setDouble(11, cost)
        case None       => stmt.setNull(11, java.sql.Types.DOUBLE)
      }
      stmt.setInt(12, record.requestCount)
      stmt.setLong(13, record.cachedTokens)
      stmt.setString(14, record.createdAt.toString)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  def listByRunId(runId: String)(implicit conn: Connection): List[UsageRecord] = {
    val sql = "SELECT * FROM usage_records WHERE run_id = ? ORDER BY created_at"
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, runId)
      val rs = stmt.executeQuery()
      val buf = scala.collection.mutable.ListBuffer.empty[UsageRecord]
      while (rs.next()) {
        buf += UsageRecord(
          usageRecordId = rs.getString("usage_record_id"),
          runId = rs.getString("run_id"),
          attemptNumber = Option(rs.getObject("attempt_number")).map(_.asInstanceOf[Int]),
          component = rs.getString("component"),
          provider = InferenceProvider.values.find(_.toString == rs.getString("provider")).getOrElse(InferenceProvider.Mock),
          model = rs.getString("model"),
          inputTokens = rs.getLong("input_tokens"),
          outputTokens = rs.getLong("output_tokens"),
          totalTokens = rs.getLong("total_tokens"),
          durationMs = rs.getLong("duration_ms"),
          estimatedCostUsd = Option(rs.getObject("estimated_cost_usd")).map(_.asInstanceOf[Double]),
          requestCount = rs.getInt("request_count"),
          cachedTokens = rs.getLong("cached_tokens"),
          createdAt = Instant.parse(rs.getString("created_at")),
        )
      }
      buf.toList
    } finally {
      stmt.close()
    }
  }
}
