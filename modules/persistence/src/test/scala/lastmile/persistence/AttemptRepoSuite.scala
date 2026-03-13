package lastmile.persistence

import munit.FunSuite
import java.nio.file.{Files, Paths}
import java.time.Instant
import lastmile.model._

class AttemptRepoSuite extends FunSuite {

  private def withDb(testFn: java.sql.Connection => Unit): Unit = {
    val tmp = Files.createTempFile("lastmile-test-", ".db")
    Files.delete(tmp)
    val conn = Database.open(tmp)
    try {
      Migrator.migrate(conn)
      // Insert a parent TaskRun (FK constraint)
      TaskRunRepo.insert(TaskRun(
        runId = "run-001",
        repoPath = Paths.get("/home/user/project"),
        worktreePath = Paths.get("/home/user/.lastmile/worktrees/run-001"),
        gitRef = None,
        taskText = "test task",
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
        lockFilePath = Paths.get("/home/user/project/.lastmile/run.lock"),
        artifactRootPath = Paths.get("/home/user/project/.runs/run-001"),
      ))(conn)
      testFn(conn)
    } finally {
      conn.close()
      Files.deleteIfExists(tmp)
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-wal"))
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-shm"))
    }
  }

  private def makeAttempt(id: String, number: Int): Attempt = Attempt(
    attemptId = id,
    runId = "run-001",
    attemptNumber = number,
    status = AttemptStatus.Created,
    startedAt = Instant.parse("2025-01-01T00:01:00Z"),
    endedAt = None,
    repairBackend = Some("claude-agent-sdk"),
    patchRecordId = None,
    failurePacketId = None,
    rerunPlanId = None,
    repairRetriesUsed = 0,
    verdictSummary = Some(AttemptVerdictSummary(
      totalRequired = 3, passCount = 1, failCount = 1,
      inconclusiveCount = 0, blockedCount = 1, timeoutCount = 0, flakeCount = 0,
    )),
  )

  test("AttemptRepo insert and read by ID") {
    withDb { implicit conn =>
      val att = makeAttempt("att-001", 1)
      AttemptRepo.insert(att)

      val loaded = AttemptRepo.getById("att-001")
      assert(loaded.isDefined)
      assertEquals(loaded.get.attemptId, att.attemptId)
      assertEquals(loaded.get.runId, att.runId)
      assertEquals(loaded.get.attemptNumber, 1)
      assertEquals(loaded.get.status, AttemptStatus.Created)
      assertEquals(loaded.get.repairBackend, Some("claude-agent-sdk"))
      assert(loaded.get.verdictSummary.isDefined)
      assertEquals(loaded.get.verdictSummary.get.totalRequired, 3)
      assertEquals(loaded.get.verdictSummary.get.failCount, 1)
    }
  }

  test("AttemptRepo getByRunAndNumber") {
    withDb { implicit conn =>
      AttemptRepo.insert(makeAttempt("att-001", 1))
      AttemptRepo.insert(makeAttempt("att-002", 2))

      val loaded = AttemptRepo.getByRunAndNumber("run-001", 2)
      assert(loaded.isDefined)
      assertEquals(loaded.get.attemptId, "att-002")
      assertEquals(loaded.get.attemptNumber, 2)

      val missing = AttemptRepo.getByRunAndNumber("run-001", 99)
      assert(missing.isEmpty)
    }
  }

  test("AttemptRepo updateStatus") {
    withDb { implicit conn =>
      AttemptRepo.insert(makeAttempt("att-003", 1))

      val endTime = Instant.parse("2025-01-01T00:10:00Z")
      AttemptRepo.updateStatus("att-003", AttemptStatus.VerificationPassed, Some(endTime))

      val loaded = AttemptRepo.getById("att-003")
      assert(loaded.isDefined)
      assertEquals(loaded.get.status, AttemptStatus.VerificationPassed)
      assertEquals(loaded.get.endedAt, Some(endTime))
    }
  }

  test("AttemptRepo listByRunId returns all attempts in order") {
    withDb { implicit conn =>
      AttemptRepo.insert(makeAttempt("att-list-2", 2))
      AttemptRepo.insert(makeAttempt("att-list-1", 1))
      AttemptRepo.insert(makeAttempt("att-list-3", 3))

      val attempts = AttemptRepo.listByRunId("run-001")
      assertEquals(attempts.size, 3)
      assertEquals(attempts.map(_.attemptNumber), List(1, 2, 3))
      assertEquals(attempts.map(_.attemptId), List("att-list-1", "att-list-2", "att-list-3"))
    }
  }

  test("AttemptRepo listByRunId returns empty for unknown run") {
    withDb { implicit conn =>
      val attempts = AttemptRepo.listByRunId("nonexistent-run")
      assert(attempts.isEmpty)
    }
  }

  test("AttemptRepo markAborted sets status and endedAt") {
    withDb { implicit conn =>
      AttemptRepo.insert(makeAttempt("att-abort", 1))

      val endTime = Instant.parse("2025-01-01T00:15:00Z")
      AttemptRepo.markAborted("att-abort", endTime)

      val loaded = AttemptRepo.getById("att-abort")
      assert(loaded.isDefined)
      assertEquals(loaded.get.status, AttemptStatus.Aborted)
      assertEquals(loaded.get.endedAt, Some(endTime))
    }
  }
}
