package demiurge.persistence

import munit.FunSuite
import java.nio.file.Files

class MigratorSuite extends FunSuite {

  private def withDb(testFn: java.sql.Connection => Unit): Unit = {
    val tmp = Files.createTempFile("demiurge-test-", ".db")
    Files.delete(tmp)
    val conn = Database.open(tmp)
    try {
      testFn(conn)
    } finally {
      conn.close()
      Files.deleteIfExists(tmp)
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-wal"))
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-shm"))
    }
  }

  test("Migration creates all 16 tables") {
    withDb { implicit conn =>
      Migrator.migrate

      val stmt = conn.createStatement()
      val rs = stmt.executeQuery(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
      val tables = scala.collection.mutable.ListBuffer[String]()
      while (rs.next()) {
        tables += rs.getString(1)
      }
      rs.close()
      stmt.close()

      val expected = List(
        "artifact_records",
        "attempts",
        "events",
        "failure_packets",
        "feature_plans",
        "inference_cache",
        "patch_records",
        "policy_snapshots",
        "repo_inspection_reports",
        "requirement_graphs",
        "requirement_verdicts",
        "rerun_plans",
        "runtime_plans",
        "runtime_snapshots",
        "schema_version",
        "task_runs",
        "usage_records",
      ).sorted

      assertEquals(tables.toList.sorted, expected)
    }
  }

  test("Schema version is 1 after migration") {
    withDb { implicit conn =>
      Migrator.migrate
      val version = Migrator.currentVersion
      assertEquals(version, 2)
    }
  }
}
