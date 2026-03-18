package lastmile.persistence

import java.nio.file.Path
import java.sql.{Connection, DriverManager}

// Spec §7.1: SQLite Configuration
object Database {

  def open(dbPath: Path): Connection = {
    Class.forName("org.sqlite.JDBC")
    val isNew = !dbPath.toFile.exists() || dbPath.toFile.length() == 0

    val conn = DriverManager.getConnection(s"jdbc:sqlite:${dbPath.toAbsolutePath}")

    // Spec §7.1: On database creation only (before any tables exist)
    if (isNew) {
      val stmt = conn.createStatement()
      try {
        stmt.execute("PRAGMA page_size=4096")
        stmt.execute("PRAGMA auto_vacuum=INCREMENTAL")
      } finally {
        stmt.close()
      }
    }

    // Spec §7.1: On every connection open
    val stmt = conn.createStatement()
    try {
      stmt.execute("PRAGMA journal_mode=WAL")
      stmt.execute("PRAGMA synchronous=NORMAL")
      stmt.execute("PRAGMA foreign_keys=ON")
      stmt.execute("PRAGMA busy_timeout=5000")
    } finally {
      stmt.close()
    }

    conn
  }
}
