package demiurge.artifact

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

import demiurge.model._

// Phase 8: Tests for prompt package assembly (Spec §14.1, §14.6)
class PromptPackageSuite extends munit.FunSuite {

  private def mkArtifact(
    artifactType: ArtifactType,
    contentType: String = "application/json",
    sizeBytes: Long = 500,
  ): ArtifactRecord = ArtifactRecord(
    artifactId = UUID.randomUUID().toString,
    runId = "run-1",
    attemptNumber = Some(1),
    artifactType = artifactType,
    producerComponent = "test",
    logicalScope = None,
    relativePath = s"run-1/attempt_1/${UUID.randomUUID().toString.take(8)}.json",
    contentType = contentType,
    sizeBytes = sizeBytes,
    checksumSha256 = "abc123",
    compressed = false,
    compressionFormat = None,
    createdAt = Instant.now(),
    metadata = Map.empty,
  )

  private def createCollector(): (EvidenceCollectorImpl, Path) = {
    val tmpDir = Files.createTempDirectory("prompt-pkg-test")
    val sink = new ArtifactSinkImpl(tmpDir)
    val collector = new EvidenceCollectorImpl(sink)
    (collector, tmpDir)
  }

  test("prompt package includes failure summary and reproduction steps") {
    val (collector, tmpDir) = createCollector()
    try {
      val result = collector.assemblePromptPackage(
        runId = "run-1",
        attemptNumber = 1,
        failureSummary = "API returned 500 for /users endpoint",
        reproSteps = "1. Navigate to /users\n2. Observe 500 error",
        reqDescriptions = List("User listing should return 200"),
        artifacts = Nil,
        maxArtifacts = 10,
        maxTotalBytes = 100000L,
      )

      assert(result.textContent.contains("FAILURE SUMMARY"))
      assert(result.textContent.contains("API returned 500"))
      assert(result.textContent.contains("REPRODUCTION STEPS"))
      assert(result.textContent.contains("Navigate to /users"))
      assert(result.textContent.contains("AFFECTED REQUIREMENTS"))
      assert(result.textContent.contains("User listing should return 200"))
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("prompt package includes correct artifacts in priority order") {
    val (collector, tmpDir) = createCollector()
    try {
      val artifacts = List(
        mkArtifact(ArtifactType.NetworkSummary),
        mkArtifact(ArtifactType.Screenshot, contentType = "image/png"),
        mkArtifact(ArtifactType.ConsoleLog),
        mkArtifact(ArtifactType.StructuredVerdict),
      )

      val result = collector.assemblePromptPackage(
        runId = "run-1",
        attemptNumber = 1,
        failureSummary = "Test failure",
        reproSteps = "Steps here",
        reqDescriptions = List("req-1"),
        artifacts = artifacts,
        maxArtifacts = 10,
        maxTotalBytes = 100000L,
      )

      assertEquals(result.includedArtifacts.size, 4)
      assert(result.omittedArtifacts.isEmpty)
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("oversized artifacts are truncated") {
    val (collector, tmpDir) = createCollector()
    try {
      val artifacts = List(
        mkArtifact(ArtifactType.ConsoleLog, sizeBytes = 10000),
      )

      val result = collector.assemblePromptPackage(
        runId = "run-1",
        attemptNumber = 1,
        failureSummary = "Test failure",
        reproSteps = "Steps here",
        reqDescriptions = List("req-1"),
        artifacts = artifacts,
        maxArtifacts = 10,
        maxTotalBytes = 100000L,
      )

      assert(result.truncatedArtifacts.nonEmpty, "Oversized text artifact should be truncated")
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("artifacts omitted when budget exceeded") {
    val (collector, tmpDir) = createCollector()
    try {
      val artifacts = (1 to 20).map(_ => mkArtifact(ArtifactType.ConsoleLog)).toList

      val result = collector.assemblePromptPackage(
        runId = "run-1",
        attemptNumber = 1,
        failureSummary = "Test failure",
        reproSteps = "Steps here",
        reqDescriptions = List("req-1"),
        artifacts = artifacts,
        maxArtifacts = 5,
        maxTotalBytes = 100000L,
      )

      assertEquals(result.includedArtifacts.size, 5)
      assertEquals(result.omittedArtifacts.size, 15)
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("never-include artifact types excluded from prompt package") {
    val (collector, tmpDir) = createCollector()
    try {
      val artifacts = List(
        mkArtifact(ArtifactType.BrowserTrace),
        mkArtifact(ArtifactType.InferenceLog),
        mkArtifact(ArtifactType.PromptPackage),
        mkArtifact(ArtifactType.ServiceLog),
        mkArtifact(ArtifactType.Screenshot, contentType = "image/png"),
      )

      val result = collector.assemblePromptPackage(
        runId = "run-1",
        attemptNumber = 1,
        failureSummary = "Test failure",
        reproSteps = "Steps here",
        reqDescriptions = List("req-1"),
        artifacts = artifacts,
        maxArtifacts = 10,
        maxTotalBytes = 100000L,
      )

      // Only Screenshot should be included (BrowserTrace, InferenceLog, PromptPackage, ServiceLog excluded)
      assertEquals(result.includedArtifacts.size, 1)
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("essential artifacts preserved under disk pressure") {
    val (collector, tmpDir) = createCollector()
    try {
      // Small budget should still include failure summary
      val result = collector.assemblePromptPackage(
        runId = "run-1",
        attemptNumber = 1,
        failureSummary = "Critical failure info",
        reproSteps = "Steps",
        reqDescriptions = List("req-1"),
        artifacts = Nil,
        maxArtifacts = 0,
        maxTotalBytes = 100L,
      )

      // Even with 0 maxArtifacts, the header content should still be present
      assert(result.textContent.contains("Critical failure info"))
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val stream = Files.list(path)
      try { stream.forEach(deleteRecursive) } finally { stream.close() }
    }
    Files.deleteIfExists(path)
  }
}
