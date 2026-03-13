package lastmile.persistence

import java.sql.Connection
import java.time.Instant
import scala.io.Source

// Spec §7.3: Schema Migration Strategy
object Migrator {

  def currentVersion(implicit conn: Connection): Int = {
    val stmt = conn.createStatement()
    try {
      val rs = stmt.executeQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'"
      )
      val tableExists = rs.next()
      rs.close()
      if (!tableExists) return 0

      val vrs = stmt.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")
      val version = if (vrs.next()) vrs.getInt(1) else 0
      vrs.close()
      version
    } finally {
      stmt.close()
    }
  }

  def migrate(implicit conn: Connection): Unit = {
    val current = currentVersion
    val migrations = availableMigrations()

    migrations.filter(_._1 > current).sortBy(_._1).foreach { case (version, sql) =>
      val autoCommit = conn.getAutoCommit
      conn.setAutoCommit(false)
      try {
        val stmt = conn.createStatement()
        try {
          // Split on semicolons and execute each statement
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { statement =>
            stmt.execute(statement)
          }
          // Record the migration
          val ps = conn.prepareStatement("INSERT INTO schema_version (version, applied_at) VALUES (?, ?)")
          try {
            ps.setInt(1, version)
            ps.setString(2, Instant.now().toString)
            ps.executeUpdate()
          } finally {
            ps.close()
          }
        } finally {
          stmt.close()
        }
        conn.commit()
      } catch {
        case e: Exception =>
          conn.rollback()
          throw new RuntimeException(s"Migration V${"%03d".format(version)} failed", e)
      } finally {
        conn.setAutoCommit(autoCommit)
      }
    }
  }

  private def availableMigrations(): List[(Int, String)] = {
    val resourceDir = "migrations/"

    // Read the index of migration files
    val migrations = scala.collection.mutable.ListBuffer[(Int, String)]()

    // Try to load known migrations
    var version = 1
    var found = true
    while (found) {
      val prefix = s"V${"%03d".format(version)}"
      val resourcePath = s"${resourceDir}${prefix}__initial.sql"
      val stream = getClass.getClassLoader.getResourceAsStream(resourcePath)
      if (stream != null) {
        try {
          val sql = Source.fromInputStream(stream, "UTF-8").mkString
          migrations += ((version, sql))
        } finally {
          stream.close()
        }
        version += 1
      } else {
        found = false
      }
    }

    migrations.toList
  }
}
