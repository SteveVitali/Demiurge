package demiurge.persistence

import java.sql.Connection

// Spec §7.4: Transaction boundary helper
object TransactionManager {

  def atomic[T](conn: Connection)(block: Connection => T): T = {
    val autoCommit = conn.getAutoCommit
    conn.setAutoCommit(false)
    try {
      val result = block(conn)
      conn.commit()
      result
    } catch {
      case e: Exception =>
        conn.rollback()
        throw e
    } finally {
      conn.setAutoCommit(autoCommit)
    }
  }
}
