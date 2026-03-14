package demiurge.artifact

import demiurge.model.ArtifactRecord

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

  // Spec §14.1, §14.6: Assemble a prompt package for repair.
  // Curates artifacts, truncates oversized ones, omits unhelpful ones,
  // produces canonical PromptPackageArtifact.
  def assemblePromptPackage(
    runId:          String,
    attemptNumber:  Int,
    failureSummary: String,
    reproSteps:     String,
    reqDescriptions: List[String],
    artifacts:      List[ArtifactRecord],
    maxArtifacts:   Int,
    maxTotalBytes:  Long,
  ): PromptPackageResult
}

// Spec §3.2: PromptPackageArtifact result
case class PromptPackageResult(
  artifactRecord:     ArtifactRecord,
  textContent:        String,
  includedArtifacts:  List[String],
  truncatedArtifacts: List[String],
  omittedArtifacts:   List[String],
)

// Reference to an artifact produced by the worker process
case class WorkerArtifactRef(
  artifactType:   String,
  relativePath:   String,
  contentType:    String,
  sizeBytes:      Long,
  checksumSha256: String,
  label:          Option[String],
)
