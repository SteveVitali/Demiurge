package lastmile.persistence

import munit.FunSuite
import java.nio.file.{Files, Paths}
import java.time.Instant
import lastmile.model._

class RepairPersistenceSuite extends FunSuite {

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

  private def makeFailurePacket(id: String, attemptNumber: Int): FailurePacket = FailurePacket(
    failurePacketId = id,
    runId = "run-001",
    attemptNumber = attemptNumber,
    primaryFailureClass = FailureClass.BackendContractFailure,
    secondaryFailureClasses = List(FailureClass.PersistenceFailure),
    summary = "Test failure summary",
    affectedRequirementIds = List("req-1", "req-2"),
    reproductionSteps = Nil,
    evidenceRefs = Nil,
    suspectedRootCauses = Nil,
    recommendedRerunScope = List("req-1"),
    recommendedRepairScope = RepairScope(Nil, Nil, "test", false),
    hardBlockers = Nil,
    softBlockers = Nil,
    producedAt = Instant.parse("2025-01-01T00:05:00Z"),
    inferenceRequestId = None,
  )

  private def makePatchRecord(id: String, attemptNumber: Int): PatchRepo.PatchRecord =
    PatchRepo.PatchRecord(
      patchRecordId = id,
      runId = "run-001",
      attemptNumber = attemptNumber,
      filesChangedJson = """["src/app.js","src/index.html"]""",
      totalLinesAdded = 10,
      totalLinesRemoved = 3,
      repairBackend = "claude",
      repairSummary = "Fixed the API endpoint",
      hypothesesJson = """["Wrong URL in fetch call"]""",
      requiresEnvRebuild = false,
      appliedAt = Instant.parse("2025-01-01T00:06:00Z"),
    )

  // --- FailurePacketRepo tests ---

  test("FailurePacketRepo insert and getById") {
    withDb { implicit conn =>
      val packet = makeFailurePacket("fp-001", 1)
      FailurePacketRepo.insert(packet)

      val loaded = FailurePacketRepo.getById("fp-001")
      assert(loaded.isDefined, "Should find failure packet by ID")
      assertEquals(loaded.get.failurePacketId, "fp-001")
      assertEquals(loaded.get.runId, "run-001")
      assertEquals(loaded.get.attemptNumber, 1)
      assertEquals(loaded.get.primaryFailureClass, FailureClass.BackendContractFailure)
      assertEquals(loaded.get.affectedRequirementIds, List("req-1", "req-2"))
    }
  }

  test("FailurePacketRepo getById returns None for missing ID") {
    withDb { implicit conn =>
      val loaded = FailurePacketRepo.getById("nonexistent")
      assert(loaded.isEmpty)
    }
  }

  test("FailurePacketRepo getByRunAndAttempt") {
    withDb { implicit conn =>
      FailurePacketRepo.insert(makeFailurePacket("fp-010", 1))
      FailurePacketRepo.insert(makeFailurePacket("fp-011", 2))

      val loaded = FailurePacketRepo.getByRunAndAttempt("run-001", 1)
      assert(loaded.isDefined)
      assertEquals(loaded.get.failurePacketId, "fp-010")

      val loaded2 = FailurePacketRepo.getByRunAndAttempt("run-001", 2)
      assert(loaded2.isDefined)
      assertEquals(loaded2.get.failurePacketId, "fp-011")

      val missing = FailurePacketRepo.getByRunAndAttempt("run-001", 99)
      assert(missing.isEmpty)
    }
  }

  // --- PatchRepo tests ---

  test("PatchRepo insert and getById") {
    withDb { implicit conn =>
      val record = makePatchRecord("pr-001", 1)
      PatchRepo.insert(record)

      val loaded = PatchRepo.getById("pr-001")
      assert(loaded.isDefined, "Should find patch record by ID")
      assertEquals(loaded.get.patchRecordId, "pr-001")
      assertEquals(loaded.get.runId, "run-001")
      assertEquals(loaded.get.attemptNumber, 1)
      assertEquals(loaded.get.repairBackend, "claude")
      assertEquals(loaded.get.repairSummary, "Fixed the API endpoint")
      assertEquals(loaded.get.totalLinesAdded, 10)
      assertEquals(loaded.get.totalLinesRemoved, 3)
      assertEquals(loaded.get.requiresEnvRebuild, false)
    }
  }

  test("PatchRepo getById returns None for missing ID") {
    withDb { implicit conn =>
      val loaded = PatchRepo.getById("nonexistent")
      assert(loaded.isEmpty)
    }
  }

  test("PatchRepo listByRunId") {
    withDb { implicit conn =>
      PatchRepo.insert(makePatchRecord("pr-010", 1))
      PatchRepo.insert(makePatchRecord("pr-011", 2))

      val records = PatchRepo.listByRunId("run-001")
      assertEquals(records.size, 2)
      assertEquals(records.map(_.patchRecordId), List("pr-010", "pr-011"))
    }
  }

  test("PatchRepo listByRunId returns empty for unknown run") {
    withDb { implicit conn =>
      val records = PatchRepo.listByRunId("nonexistent-run")
      assert(records.isEmpty)
    }
  }
}
