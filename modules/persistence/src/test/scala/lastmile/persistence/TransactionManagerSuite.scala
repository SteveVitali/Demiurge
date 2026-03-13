package lastmile.persistence

import munit.FunSuite
import java.nio.file.{Files, Paths}
import java.time.Instant
import lastmile.model._

class TransactionManagerSuite extends FunSuite {

  private def withDb(testFn: java.sql.Connection => Unit): Unit = {
    val tmp = Files.createTempFile("lastmile-test-", ".db")
    Files.delete(tmp)
    val conn = Database.open(tmp)
    try {
      Migrator.migrate(conn)
      testFn(conn)
    } finally {
      conn.close()
      Files.deleteIfExists(tmp)
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-wal"))
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-shm"))
    }
  }

  private def makeRun(id: String): TaskRun = TaskRun(
    runId = id,
    repoPath = Paths.get("/tmp/repo"),
    worktreePath = Paths.get(s"/tmp/worktree/$id"),
    gitRef = None,
    taskText = "test",
    changedFiles = None,
    status = RunStatus.Created,
    runMode = RunMode.Full,
    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    startedAt = None,
    endedAt = None,
    maxAttempts = 5,
    attemptCount = 0,
    envBootAttempts = 0,
    currentAttemptId = None,
    finalVerdict = None,
    finalSummary = None,
    policySnapshotId = "ps-001",
    lockFilePath = Paths.get("/tmp/repo/.lastmile/run.lock"),
    artifactRootPath = Paths.get(s"/tmp/repo/.runs/$id"),
  )

  test("TransactionManager.atomic commits on success") {
    withDb { conn =>
      TransactionManager.atomic(conn) { implicit c =>
        TaskRunRepo.insert(makeRun("tx-commit"))
      }
      implicit val c: java.sql.Connection = conn
      val loaded = TaskRunRepo.getById("tx-commit")
      assert(loaded.isDefined)
      assertEquals(loaded.get.runId, "tx-commit")
    }
  }

  test("TransactionManager.atomic rolls back on exception") {
    withDb { conn =>
      val caught = intercept[RuntimeException] {
        TransactionManager.atomic(conn) { implicit c =>
          TaskRunRepo.insert(makeRun("tx-rollback"))
          throw new RuntimeException("simulated failure")
        }
      }
      assertEquals(caught.getMessage, "simulated failure")

      implicit val c: java.sql.Connection = conn
      val loaded = TaskRunRepo.getById("tx-rollback")
      assert(loaded.isEmpty, "Row should not exist after rollback")
    }
  }
}
