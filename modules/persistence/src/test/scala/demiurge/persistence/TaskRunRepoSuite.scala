package demiurge.persistence

import munit.FunSuite
import java.nio.file.{Files, Paths}
import java.time.Instant
import demiurge.model._

class TaskRunRepoSuite extends FunSuite {

  private def withDb(testFn: java.sql.Connection => Unit): Unit = {
    val tmp = Files.createTempFile("demiurge-test-", ".db")
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

  private def makeRun(id: String, repoPath: String = "/home/user/project"): TaskRun = TaskRun(
    runId = id,
    repoPath = Paths.get(repoPath),
    worktreePath = Paths.get(s"/home/user/.demiurge/worktrees/$id"),
    gitRef = Some("abc123"),
    taskText = "Add login button",
    changedFiles = Some(List("src/App.tsx")),
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
    lockFilePath = Paths.get(s"$repoPath/.demiurge/run.lock"),
    artifactRootPath = Paths.get(s"$repoPath/.runs/$id"),
  )

  test("TaskRunRepo insert and read by ID") {
    withDb { implicit conn =>
      val run = makeRun("run-001")
      TaskRunRepo.insert(run)

      val loaded = TaskRunRepo.getById("run-001")
      assert(loaded.isDefined)
      assertEquals(loaded.get.runId, run.runId)
      assertEquals(loaded.get.repoPath, run.repoPath)
      assertEquals(loaded.get.taskText, run.taskText)
      assertEquals(loaded.get.status, RunStatus.Created)
      assertEquals(loaded.get.runMode, RunMode.Full)
      assertEquals(loaded.get.changedFiles, Some(List("src/App.tsx")))
      assertEquals(loaded.get.maxAttempts, 5)
      assertEquals(loaded.get.policySnapshotId, "ps-001")
    }
  }

  test("TaskRunRepo updateStatus") {
    withDb { implicit conn =>
      val run = makeRun("run-002")
      TaskRunRepo.insert(run)

      TaskRunRepo.updateStatus("run-002", RunStatus.InspectingRepo)

      val loaded = TaskRunRepo.getById("run-002")
      assert(loaded.isDefined)
      assertEquals(loaded.get.status, RunStatus.InspectingRepo)
      assertEquals(loaded.get.endedAt, None)
    }
  }

  test("TaskRunRepo getById returns None for nonexistent ID") {
    withDb { implicit conn =>
      val loaded = TaskRunRepo.getById("nonexistent")
      assert(loaded.isEmpty)
    }
  }

  test("TaskRunRepo updateStatus with endedAt") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-003"))

      val endTime = Instant.parse("2025-01-01T01:00:00Z")
      TaskRunRepo.updateStatus("run-003", RunStatus.Succeeded, Some(endTime))

      val loaded = TaskRunRepo.getById("run-003")
      assert(loaded.isDefined)
      assertEquals(loaded.get.status, RunStatus.Succeeded)
      assertEquals(loaded.get.endedAt, Some(endTime))
    }
  }

  test("TaskRunRepo listByRepoPath filters correctly") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-a1", "/home/user/projectA"))
      TaskRunRepo.insert(makeRun("run-a2", "/home/user/projectA"))
      TaskRunRepo.insert(makeRun("run-b1", "/home/user/projectB"))

      val projectARuns = TaskRunRepo.listByRepoPath("/home/user/projectA")
      assertEquals(projectARuns.size, 2)
      assert(projectARuns.map(_.runId).toSet == Set("run-a1", "run-a2"))

      val projectBRuns = TaskRunRepo.listByRepoPath("/home/user/projectB")
      assertEquals(projectBRuns.size, 1)
      assertEquals(projectBRuns.head.runId, "run-b1")
    }
  }

  test("TaskRunRepo update persists all fields") {
    withDb { implicit conn =>
      val run = makeRun("run-upd")
      TaskRunRepo.insert(run)

      val updated = run.copy(
        status = RunStatus.InspectingRepo,
        startedAt = Some(Instant.parse("2025-01-01T00:05:00Z")),
        taskText = "Updated task text",
      )
      TaskRunRepo.update(updated)

      val loaded = TaskRunRepo.getById("run-upd")
      assert(loaded.isDefined)
      assertEquals(loaded.get.status, RunStatus.InspectingRepo)
      assertEquals(loaded.get.startedAt, Some(Instant.parse("2025-01-01T00:05:00Z")))
      assertEquals(loaded.get.taskText, "Updated task text")
    }
  }

  test("TaskRunRepo setCurrentAttempt") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-sca"))
      assertEquals(TaskRunRepo.getById("run-sca").get.currentAttemptId, None)

      TaskRunRepo.setCurrentAttempt("run-sca", Some("attempt-1"))
      assertEquals(TaskRunRepo.getById("run-sca").get.currentAttemptId, Some("attempt-1"))

      TaskRunRepo.setCurrentAttempt("run-sca", None)
      assertEquals(TaskRunRepo.getById("run-sca").get.currentAttemptId, None)
    }
  }

  test("TaskRunRepo incrementAttemptCount") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-inc"))
      assertEquals(TaskRunRepo.getById("run-inc").get.attemptCount, 0)

      TaskRunRepo.incrementAttemptCount("run-inc")
      assertEquals(TaskRunRepo.getById("run-inc").get.attemptCount, 1)

      TaskRunRepo.incrementAttemptCount("run-inc")
      assertEquals(TaskRunRepo.getById("run-inc").get.attemptCount, 2)
    }
  }

  test("TaskRunRepo getActiveRunByRepoPath finds non-terminal runs") {
    withDb { implicit conn =>
      // Terminal run
      val exhausted = makeRun("run-term", "/home/user/projectX")
        .copy(status = RunStatus.Exhausted, endedAt = Some(Instant.parse("2025-01-01T01:00:00Z")))
      TaskRunRepo.insert(exhausted)

      // No active run yet
      assertEquals(TaskRunRepo.getActiveRunByRepoPath("/home/user/projectX"), None)

      // Active run
      val active = makeRun("run-active", "/home/user/projectX")
        .copy(status = RunStatus.InspectingRepo)
      TaskRunRepo.insert(active)

      val found = TaskRunRepo.getActiveRunByRepoPath("/home/user/projectX")
      assert(found.isDefined)
      assertEquals(found.get.runId, "run-active")
    }
  }

  test("TaskRunRepo setStartedAt") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-sta"))
      assertEquals(TaskRunRepo.getById("run-sta").get.startedAt, None)

      val t = Instant.parse("2025-06-15T12:30:00Z")
      TaskRunRepo.setStartedAt("run-sta", t)
      assertEquals(TaskRunRepo.getById("run-sta").get.startedAt, Some(t))
    }
  }

  test("TaskRunRepo setFinalSummary") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-sum"))
      assertEquals(TaskRunRepo.getById("run-sum").get.finalSummary, None)

      TaskRunRepo.setFinalSummary("run-sum", "All checks passed")
      assertEquals(TaskRunRepo.getById("run-sum").get.finalSummary, Some("All checks passed"))
    }
  }
}
