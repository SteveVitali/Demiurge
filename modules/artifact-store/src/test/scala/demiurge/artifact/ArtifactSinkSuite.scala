package demiurge.artifact

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.security.MessageDigest

class ArtifactSinkSuite extends FunSuite {

  private def withTmpDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("artifact-sink-test-")
    try {
      testFn(tmpDir)
    } finally {
      Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    }
  }

  private def sha256(data: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(data)
    digest.digest().map("%02x".format(_)).mkString
  }

  test("writeArtifact uses temp-file-then-rename") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val content = "test artifact content".getBytes("UTF-8")
      val record = sink.writeArtifact(
        runId = "run-1",
        attemptNumber = Some(1),
        artifactType = "Screenshot",
        producerComponent = "test",
        content = content,
        relativePath = "run-1/test/screenshot.png",
        contentType = "image/png",
      )

      // File should exist at final path
      val finalPath = tmpDir.resolve(record.relativePath)
      assert(Files.exists(finalPath), s"Artifact should exist at ${finalPath}")

      // No .tmp files should remain
      val tmpFiles = Files.list(finalPath.getParent).toArray
        .map(_.asInstanceOf[Path])
        .filter(_.getFileName.toString.contains(".tmp."))
      assertEquals(tmpFiles.length, 0, "No tmp files should remain after write")
    }
  }

  test("checksum is stored and verified") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val content = "checksum test content".getBytes("UTF-8")
      val record = sink.writeArtifact(
        runId = "run-1",
        attemptNumber = Some(1),
        artifactType = "ConsoleLog",
        producerComponent = "test",
        content = content,
        relativePath = "run-1/test/console.json",
        contentType = "application/json",
      )

      val expectedChecksum = sha256(content)
      assertEquals(record.checksumSha256, expectedChecksum)
      assert(sink.verifyChecksum(record), "Checksum verification should pass")
    }
  }

  test("oversized artifact is compressed") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      // Create content > 1 MB
      val content = ("x" * 1024).getBytes("UTF-8")
      val largeContent = new Array[Byte](1024 * 1024 + 100)
      java.util.Arrays.fill(largeContent, 'A'.toByte)

      val record = sink.writeArtifact(
        runId = "run-1",
        attemptNumber = Some(1),
        artifactType = "DomSnapshot",
        producerComponent = "test",
        content = largeContent,
        relativePath = "run-1/test/dom.html",
        contentType = "text/html",
      )

      assert(record.compressed, "Large artifact should be compressed")
      assertEquals(record.compressionFormat, Some("gzip"))
      assert(record.relativePath.endsWith(".gz"), "Compressed artifact should have .gz extension")
      assert(record.sizeBytes < largeContent.length, "Compressed size should be smaller")
    }
  }

  test("artifact metadata is populated correctly") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val content = "metadata test".getBytes("UTF-8")
      val record = sink.writeArtifact(
        runId = "run-42",
        attemptNumber = Some(3),
        artifactType = "NetworkSummary",
        producerComponent = "browser-worker",
        content = content,
        relativePath = "run-42/test/network.json",
        contentType = "application/json",
        logicalScope = Some("verifier-1"),
        label = Some("network-summary"),
        metadata = Map("source" -> "worker"),
      )

      assertEquals(record.runId, "run-42")
      assertEquals(record.attemptNumber, Some(3))
      assertEquals(record.producerComponent, "browser-worker")
      assertEquals(record.logicalScope, Some("verifier-1"))
      assert(record.metadata.contains("label"))
      assert(record.metadata.contains("source"))
      assert(record.artifactId.nonEmpty)
    }
  }

  test("artifact budget enforcement skips non-essential artifacts") {
    withTmpDir { tmpDir =>
      // Very small budget: 100 bytes
      val sink = new ArtifactSinkImpl(tmpDir, maxDiskBytes = 100)
      val content = new Array[Byte](200)
      java.util.Arrays.fill(content, 'X'.toByte)

      // Non-essential artifact should be rejected
      val caught = intercept[RuntimeException] {
        sink.writeArtifact(
          runId = "run-1",
          attemptNumber = Some(1),
          artifactType = "ConsoleLog",
          producerComponent = "test",
          content = content,
          relativePath = "run-1/test/console.json",
          contentType = "application/json",
        )
      }
      assert(caught.getMessage.contains("budget exceeded"))
    }
  }

  test("essential artifacts bypass budget enforcement") {
    withTmpDir { tmpDir =>
      // Very small budget: 10 bytes
      val sink = new ArtifactSinkImpl(tmpDir, maxDiskBytes = 10)
      val content = "essential content that exceeds budget".getBytes("UTF-8")

      // Essential artifact (Screenshot) should still be written
      val record = sink.writeArtifact(
        runId = "run-1",
        attemptNumber = Some(1),
        artifactType = "Screenshot",
        producerComponent = "test",
        content = content,
        relativePath = "run-1/test/screenshot.png",
        contentType = "image/png",
      )
      assert(record.artifactId.nonEmpty)
    }
  }

  test("corrupted artifact is detected on read") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val content = "original content".getBytes("UTF-8")
      val record = sink.writeArtifact(
        runId = "run-1",
        attemptNumber = Some(1),
        artifactType = "ConsoleLog",
        producerComponent = "test",
        content = content,
        relativePath = "run-1/test/console.json",
        contentType = "application/json",
      )

      // Corrupt the file
      val path = tmpDir.resolve(record.relativePath)
      Files.write(path, "corrupted data".getBytes("UTF-8"))

      assert(!sink.verifyChecksum(record), "Checksum should fail for corrupted file")
    }
  }

  test("registerExternalArtifact creates record without writing") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      val record = sink.registerExternalArtifact(
        runId = "run-1",
        attemptNumber = Some(1),
        artifactType = "BrowserTrace",
        producerComponent = "browser-worker",
        relativePath = "run-1/task-1/traces/trace.zip",
        contentType = "application/zip",
        sizeBytes = 12345,
        checksumSha256 = "abc123",
        label = Some("trace"),
      )

      assertEquals(record.runId, "run-1")
      assertEquals(record.sizeBytes, 12345L)
      assertEquals(record.checksumSha256, "abc123")
      assertEquals(record.producerComponent, "browser-worker")
    }
  }

  test("isEssential classifies artifact types correctly") {
    withTmpDir { tmpDir =>
      val sink = new ArtifactSinkImpl(tmpDir)
      assert(sink.isEssential("Screenshot"))
      assert(sink.isEssential("StructuredVerdict"))
      assert(sink.isEssential("FinalReport"))
      assert(sink.isEssential("PatchDiff"))
      assert(!sink.isEssential("ConsoleLog"))
      assert(!sink.isEssential("NetworkSummary"))
      assert(!sink.isEssential("BrowserTrace"))
    }
  }
}
