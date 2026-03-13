package lastmile.orchestrator

import java.nio.file.{Files, Paths}
import java.time.Instant
import lastmile.model._
import lastmile.persistence._

object Main {

  def main(args: Array[String]): Unit = {
    println("Demiurge Phase 1 — Smoke Test")

    // Create temp DB file
    val tmpDir = Files.createTempDirectory("demiurge-test")
    val dbPath = tmpDir.resolve("lastmile.db")
    println(s"DB path: $dbPath")

    val conn = Database.open(dbPath)
    try {
      // Run migrations
      Migrator.migrate(conn)
      println(s"Schema version: ${Migrator.currentVersion(conn)}")

      // Create a default PolicySnapshot (budget only — no state machine logic)
      val budget = ExecutionBudgetDefaults.defaults
      println(s"Default budget: maxAttempts=${budget.maxAttempts}, runTimeoutMs=${budget.runTimeoutMs}")

      // Insert a test TaskRun
      implicit val c: java.sql.Connection = conn
      val run = TaskRun(
        runId = "test-run-001",
        repoPath = Paths.get("/tmp/test-repo"),
        worktreePath = Paths.get("/tmp/test-worktree"),
        gitRef = Some("HEAD"),
        taskText = "Phase 1 smoke test",
        changedFiles = Some(List("README.md")),
        status = RunStatus.Created,
        runMode = RunMode.Full,
        createdAt = Instant.now(),
        startedAt = None,
        endedAt = None,
        maxAttempts = budget.maxAttempts,
        attemptCount = 0,
        envBootAttempts = 0,
        currentAttemptId = None,
        finalVerdict = None,
        finalSummary = None,
        policySnapshotId = "ps-test-001",
        lockFilePath = Paths.get("/tmp/test-repo/.lastmile/run.lock"),
        artifactRootPath = Paths.get("/tmp/test-repo/.runs/test-run-001"),
      )

      TaskRunRepo.insert(run)
      println("Inserted TaskRun: test-run-001")

      // Read it back
      val loaded = TaskRunRepo.getById("test-run-001")
      loaded match {
        case Some(r) =>
          println(s"Read back: runId=${r.runId}, status=${r.status}, taskText=${r.taskText}")
          assert(r.runId == run.runId)
          assert(r.status == RunStatus.Created)
          assert(r.taskText == "Phase 1 smoke test")
          println("All assertions passed!")
        case None =>
          System.err.println("ERROR: Failed to read back TaskRun!")
          System.exit(1)
      }

      println("Phase 1 smoke test completed successfully.")
    } finally {
      conn.close()
      // Cleanup
      Files.deleteIfExists(dbPath)
      Files.deleteIfExists(dbPath.resolveSibling(dbPath.getFileName.toString + "-wal"))
      Files.deleteIfExists(dbPath.resolveSibling(dbPath.getFileName.toString + "-shm"))
      Files.deleteIfExists(tmpDir)
    }
  }
}
