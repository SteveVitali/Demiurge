package demiurge.persistence

import munit.FunSuite
import java.nio.file.Files
import java.time.Instant

import demiurge.model._

class ArtifactRecordRepoSuite extends FunSuite {

  private def withDb(testFn: java.sql.Connection => Unit): Unit = {
    val tmp = Files.createTempFile("artifact-repo-test-", ".db")
    Files.delete(tmp)
    val conn = Database.open(tmp)
    try {
      Migrator.migrate(conn)
      // Insert a task_run to satisfy foreign key
      val ps = conn.prepareStatement(
        """INSERT INTO task_runs (run_id, repo_path, worktree_path, task_text, status, run_mode,
          |created_at, policy_snapshot_id, lock_file_path, artifact_root_path)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin)
      ps.setString(1, "run-1")
      ps.setString(2, "/repo")
      ps.setString(3, "/worktree")
      ps.setString(4, "test task")
      ps.setString(5, "Created")
      ps.setString(6, "Full")
      ps.setString(7, Instant.now().toString)
      ps.setString(8, "policy-1")
      ps.setString(9, "/lock")
      ps.setString(10, "/artifacts")
      ps.executeUpdate()
      ps.close()

      testFn(conn)
    } finally {
      conn.close()
      Files.deleteIfExists(tmp)
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-wal"))
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-shm"))
    }
  }

  private def makeRecord(
    artifactId: String = "art-1",
    runId: String = "run-1",
    attemptNumber: Option[Int] = Some(1),
    artifactType: ArtifactType = ArtifactType.Screenshot,
  ): ArtifactRecord = {
    ArtifactRecord(
      artifactId = artifactId,
      runId = runId,
      attemptNumber = attemptNumber,
      artifactType = artifactType,
      producerComponent = "browser-worker",
      logicalScope = Some("verifier-1"),
      relativePath = s"$runId/test/$artifactId.png",
      contentType = "image/png",
      sizeBytes = 1024,
      checksumSha256 = "abc123def456",
      compressed = false,
      compressionFormat = None,
      createdAt = Instant.now(),
      metadata = Map("label" -> "test"),
    )
  }

  test("insert and getById") {
    withDb { implicit conn =>
      val record = makeRecord()
      ArtifactRecordRepo.insert(record)

      val retrieved = ArtifactRecordRepo.getById("art-1")
      assert(retrieved.isDefined)
      assertEquals(retrieved.get.artifactId, "art-1")
      assertEquals(retrieved.get.runId, "run-1")
      assertEquals(retrieved.get.attemptNumber, Some(1))
      assertEquals(retrieved.get.producerComponent, "browser-worker")
      assertEquals(retrieved.get.sizeBytes, 1024L)
      assertEquals(retrieved.get.checksumSha256, "abc123def456")
    }
  }

  test("insertAll persists multiple records") {
    withDb { implicit conn =>
      val records = List(
        makeRecord("art-1"),
        makeRecord("art-2", artifactType = ArtifactType.ConsoleLog),
        makeRecord("art-3", artifactType = ArtifactType.NetworkSummary),
      )
      ArtifactRecordRepo.insertAll(records)

      val all = ArtifactRecordRepo.listByRunId("run-1")
      assertEquals(all.size, 3)
    }
  }

  test("listByRunId returns artifacts for run") {
    withDb { implicit conn =>
      ArtifactRecordRepo.insert(makeRecord("art-1"))
      ArtifactRecordRepo.insert(makeRecord("art-2"))

      val results = ArtifactRecordRepo.listByRunId("run-1")
      assertEquals(results.size, 2)
    }
  }

  test("listByRunAndAttempt filters by attempt") {
    withDb { implicit conn =>
      ArtifactRecordRepo.insert(makeRecord("art-1", attemptNumber = Some(1)))
      ArtifactRecordRepo.insert(makeRecord("art-2", attemptNumber = Some(2)))
      ArtifactRecordRepo.insert(makeRecord("art-3", attemptNumber = Some(1)))

      val attempt1 = ArtifactRecordRepo.listByRunAndAttempt("run-1", 1)
      assertEquals(attempt1.size, 2)

      val attempt2 = ArtifactRecordRepo.listByRunAndAttempt("run-1", 2)
      assertEquals(attempt2.size, 1)
    }
  }

  test("listByRunAndType filters by artifact type") {
    withDb { implicit conn =>
      ArtifactRecordRepo.insert(makeRecord("art-1", artifactType = ArtifactType.Screenshot))
      ArtifactRecordRepo.insert(makeRecord("art-2", artifactType = ArtifactType.ConsoleLog))
      ArtifactRecordRepo.insert(makeRecord("art-3", artifactType = ArtifactType.Screenshot))

      val screenshots = ArtifactRecordRepo.listByRunAndType("run-1", ArtifactType.Screenshot)
      assertEquals(screenshots.size, 2)

      val consoleLogs = ArtifactRecordRepo.listByRunAndType("run-1", ArtifactType.ConsoleLog)
      assertEquals(consoleLogs.size, 1)
    }
  }

  test("getById returns None for missing artifact") {
    withDb { implicit conn =>
      val result = ArtifactRecordRepo.getById("nonexistent")
      assert(result.isEmpty)
    }
  }

  test("artifact with null attempt_number is stored correctly") {
    withDb { implicit conn =>
      val record = makeRecord("art-1", attemptNumber = None)
      ArtifactRecordRepo.insert(record)

      val retrieved = ArtifactRecordRepo.getById("art-1")
      assert(retrieved.isDefined)
      assertEquals(retrieved.get.attemptNumber, None)
    }
  }

  test("compressed artifact fields are stored correctly") {
    withDb { implicit conn =>
      val record = ArtifactRecord(
        artifactId = "art-compressed",
        runId = "run-1",
        attemptNumber = Some(1),
        artifactType = ArtifactType.DomSnapshot,
        producerComponent = "browser-worker",
        logicalScope = None,
        relativePath = "run-1/test/dom.html.gz",
        contentType = "application/gzip",
        sizeBytes = 50000,
        checksumSha256 = "compressed-hash",
        compressed = true,
        compressionFormat = Some("gzip"),
        createdAt = Instant.now(),
        metadata = Map.empty,
      )
      ArtifactRecordRepo.insert(record)

      val retrieved = ArtifactRecordRepo.getById("art-compressed")
      assert(retrieved.isDefined)
      assert(retrieved.get.compressed)
      assertEquals(retrieved.get.compressionFormat, Some("gzip"))
    }
  }
}
