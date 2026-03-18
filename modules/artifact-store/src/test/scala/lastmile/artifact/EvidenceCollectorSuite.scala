package lastmile.artifact

import munit.FunSuite
import java.nio.file.{Files, Path}

class EvidenceCollectorSuite extends FunSuite {

  private def withTmpDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("evidence-collector-test-")
    try {
      testFn(tmpDir)
    } finally {
      Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    }
  }

  test("registerWorkerArtifacts creates ArtifactRecords from worker refs") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val collector = new EvidenceCollectorImpl(sink)

      val workerRefs = List(
        WorkerArtifactRef("Screenshot", "run-1/task-1/screenshots/final.png", "image/png", 1024, "abc123", Some("final")),
        WorkerArtifactRef("ConsoleLog", "run-1/task-1/console/console.json", "application/json", 512, "def456", Some("console")),
      )

      val records = collector.registerWorkerArtifacts("run-1", 1, workerRefs)
      assertEquals(records.size, 2)
      assertEquals(records(0).runId, "run-1")
      assertEquals(records(0).attemptNumber, Some(1))
      assertEquals(records(0).producerComponent, "browser-worker")
      assertEquals(records(0).checksumSha256, "abc123")
      assertEquals(records(1).checksumSha256, "def456")
    }
  }

  test("writeVerdictArtifact produces StructuredVerdict artifact") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val collector = new EvidenceCollectorImpl(sink)

      val record = collector.writeVerdictArtifact(
        runId = "run-1",
        attemptNumber = 1,
        verdictId = "v-123",
        verdictJson = """{"status":"pass","verifierId":"v-123"}""",
      )

      assertEquals(record.runId, "run-1")
      assertEquals(record.attemptNumber, Some(1))
      assert(record.relativePath.contains("verdict_v-123.json"))
      assert(record.logicalScope.contains("v-123"))

      // File should exist
      val path = tmpDir.resolve(record.relativePath)
      assert(Files.exists(path))
    }
  }

  test("writeFailurePacketArtifact produces FailurePacketArtifact") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val collector = new EvidenceCollectorImpl(sink)

      val record = collector.writeFailurePacketArtifact(
        runId = "run-1",
        attemptNumber = 1,
        packetId = "fp-456",
        packetJson = """{"failurePacketId":"fp-456","summary":"test failure"}""",
      )

      assertEquals(record.runId, "run-1")
      assert(record.relativePath.contains("packet_fp-456.json"))
      val path = tmpDir.resolve(record.relativePath)
      assert(Files.exists(path))
    }
  }

  test("writeFinalReportArtifact produces FinalReport artifact") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val collector = new EvidenceCollectorImpl(sink)

      val record = collector.writeFinalReportArtifact(
        runId = "run-1",
        reportJson = """{"runId":"run-1","finalVerdict":"Pass"}""",
      )

      assertEquals(record.runId, "run-1")
      assertEquals(record.attemptNumber, None)
      assert(record.relativePath.contains("final_report.json"))
    }
  }

  test("writeAttemptReportArtifact produces AttemptReport artifact") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val collector = new EvidenceCollectorImpl(sink)

      val record = collector.writeAttemptReportArtifact(
        runId = "run-1",
        attemptNumber = 2,
        reportJson = """{"attemptNumber":2,"verdict":"Fail"}""",
      )

      assertEquals(record.runId, "run-1")
      assertEquals(record.attemptNumber, Some(2))
      assert(record.relativePath.contains("attempt_report.json"))
    }
  }

  test("multiple worker artifacts registered correctly") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val collector = new EvidenceCollectorImpl(sink)

      val refs = (1 to 5).map { i =>
        WorkerArtifactRef(
          s"Screenshot", s"run-1/task-1/screenshots/step_$i.png",
          "image/png", 100 * i, s"checksum_$i", Some(s"step_$i"),
        )
      }.toList

      val records = collector.registerWorkerArtifacts("run-1", 1, refs)
      assertEquals(records.size, 5)
      records.zipWithIndex.foreach { case (r, i) =>
        assertEquals(r.checksumSha256, s"checksum_${i + 1}")
      }
    }
  }
}
