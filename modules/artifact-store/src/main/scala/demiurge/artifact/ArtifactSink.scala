package demiurge.artifact

import java.nio.file.Path

import demiurge.model.ArtifactRecord

// Spec §12.2: ArtifactSink trait — interface for writing and registering artifacts
trait ArtifactSink {
  // Write content to disk using temp-file-then-rename, compute checksum, return ArtifactRecord
  def writeArtifact(
    runId:             String,
    attemptNumber:     Option[Int],
    artifactType:      String,
    producerComponent: String,
    content:           Array[Byte],
    relativePath:      String,
    contentType:       String,
    logicalScope:      Option[String] = None,
    label:             Option[String] = None,
    metadata:          Map[String, String] = Map.empty,
  ): ArtifactRecord

  // Register an artifact that was already written to disk (e.g., by the worker)
  def registerExternalArtifact(
    runId:             String,
    attemptNumber:     Option[Int],
    artifactType:      String,
    producerComponent: String,
    relativePath:      String,
    contentType:       String,
    sizeBytes:         Long,
    checksumSha256:    String,
    logicalScope:      Option[String] = None,
    label:             Option[String] = None,
    metadata:          Map[String, String] = Map.empty,
  ): ArtifactRecord

  // Verify artifact integrity on read
  def verifyChecksum(record: ArtifactRecord): Boolean

  // Get the absolute path for an artifact
  def absolutePath(relativePath: String): Path

  // Check remaining disk budget
  def remainingBudgetBytes: Long

  // Check if artifact is essential (should always be written regardless of budget)
  def isEssential(artifactType: String): Boolean
}
