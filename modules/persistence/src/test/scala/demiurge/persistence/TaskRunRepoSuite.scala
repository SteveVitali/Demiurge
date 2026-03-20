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

  // --- Desktop Phase 1: listPaginated tests ---

  test("TaskRunRepo listPaginated returns paginated results") {
    withDb { implicit conn =>
      for (i <- 1 to 5) {
        val run = makeRun(s"run-pag-$i").copy(
          createdAt = Instant.parse(s"2025-01-0${i}T00:00:00Z"),
        )
        TaskRunRepo.insert(run)
      }

      val (runs, total) = TaskRunRepo.listPaginated(offset = 0, limit = 3)
      assertEquals(total, 5)
      assertEquals(runs.size, 3)
      // Default sort is created_at DESC
      assertEquals(runs.head.runId, "run-pag-5")
    }
  }

  test("TaskRunRepo listPaginated respects offset") {
    withDb { implicit conn =>
      for (i <- 1 to 5) {
        val run = makeRun(s"run-off-$i").copy(
          createdAt = Instant.parse(s"2025-01-0${i}T00:00:00Z"),
        )
        TaskRunRepo.insert(run)
      }

      val (runs, total) = TaskRunRepo.listPaginated(offset = 3, limit = 10)
      assertEquals(total, 5)
      assertEquals(runs.size, 2)
      // Offset 3 in DESC order: items 4 and 5 from the end → run-2 and run-1
      assertEquals(runs.head.runId, "run-off-2")
      assertEquals(runs.last.runId, "run-off-1")
    }
  }

  test("TaskRunRepo listPaginated filters by status") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-filt-1").copy(status = RunStatus.Succeeded))
      TaskRunRepo.insert(makeRun("run-filt-2").copy(status = RunStatus.Exhausted))
      TaskRunRepo.insert(makeRun("run-filt-3").copy(status = RunStatus.Succeeded))

      val (runs, total) = TaskRunRepo.listPaginated(statusFilter = Some(RunStatus.Succeeded))
      assertEquals(total, 2)
      assertEquals(runs.size, 2)
      assert(runs.forall(_.status == RunStatus.Succeeded))
    }
  }

  test("TaskRunRepo listPaginated sorts ascending") {
    withDb { implicit conn =>
      for (i <- 1 to 3) {
        val run = makeRun(s"run-asc-$i").copy(
          createdAt = Instant.parse(s"2025-01-0${i}T00:00:00Z"),
        )
        TaskRunRepo.insert(run)
      }

      val (runs, _) = TaskRunRepo.listPaginated(sort = "created_at", order = "asc")
      assertEquals(runs.head.runId, "run-asc-1")
      assertEquals(runs.last.runId, "run-asc-3")
    }
  }

  test("TaskRunRepo listPaginated rejects invalid sort column") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-safe"))

      // SQL injection attempt should fall back to created_at
      val (runs, total) = TaskRunRepo.listPaginated(sort = "1; DROP TABLE task_runs; --")
      assertEquals(total, 1)
      assertEquals(runs.size, 1)
    }
  }

  // --- Desktop Phase 1: getActiveRun tests ---

  test("TaskRunRepo getActiveRun returns None when all runs are terminal") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-ga-1").copy(status = RunStatus.Succeeded))
      TaskRunRepo.insert(makeRun("run-ga-2").copy(status = RunStatus.Exhausted))
      TaskRunRepo.insert(makeRun("run-ga-3").copy(status = RunStatus.Cancelled))

      assertEquals(TaskRunRepo.getActiveRun(), None)
    }
  }

  test("TaskRunRepo getActiveRun returns the most recent non-terminal run") {
    withDb { implicit conn =>
      TaskRunRepo.insert(makeRun("run-ga-old").copy(
        status = RunStatus.Verifying,
        createdAt = Instant.parse("2025-01-01T00:00:00Z"),
      ))
      TaskRunRepo.insert(makeRun("run-ga-new").copy(
        status = RunStatus.InspectingRepo,
        createdAt = Instant.parse("2025-01-02T00:00:00Z"),
      ))
      TaskRunRepo.insert(makeRun("run-ga-done").copy(
        status = RunStatus.Succeeded,
        createdAt = Instant.parse("2025-01-03T00:00:00Z"),
      ))

      val active = TaskRunRepo.getActiveRun()
      assert(active.isDefined)
      assertEquals(active.get.runId, "run-ga-new")
    }
  }

  test("TaskRunRepo getActiveRun returns None on empty database") {
    withDb { implicit conn =>
      assertEquals(TaskRunRepo.getActiveRun(), None)
    }
  }
}
