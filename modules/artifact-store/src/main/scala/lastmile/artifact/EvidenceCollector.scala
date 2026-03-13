package lastmile.artifact

import lastmile.model.ArtifactRecord

// Spec §12.4: EvidenceCollector trait — collects and registers evidence artifacts
// from verification results, failure packets, and repair transcripts.
trait EvidenceCollector {
  // Register worker-produced artifacts into the artifact store
  def registerWorkerArtifacts(
    runId:             String,
    attemptNumber:     Int,
    workerArtifacts:   List[WorkerArtifactRef],
  ): List[ArtifactRecord]

  // Write a structured verdict artifact
  def writeVerdictArtifact(
    runId:         String,
    attemptNumber: Int,
    verdictId:     String,
    verdictJson:   String,
  ): ArtifactRecord

  // Write a failure packet artifact
  def writeFailurePacketArtifact(
    runId:         String,
    attemptNumber: Int,
    packetId:      String,
    packetJson:    String,
  ): ArtifactRecord

  // Write a minimal final report artifact
  def writeFinalReportArtifact(
    runId:      String,
    reportJson: String,
  ): ArtifactRecord

  // Write a minimal attempt report artifact
  def writeAttemptReportArtifact(
    runId:         String,
    attemptNumber: Int,
    reportJson:    String,
  ): ArtifactRecord
}

// Reference to an artifact produced by the worker process
case class WorkerArtifactRef(
  artifactType:   String,
  relativePath:   String,
  contentType:    String,
  sizeBytes:      Long,
  checksumSha256: String,
  label:          Option[String],
)
