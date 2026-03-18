package lastmile.artifact

import java.sql.Connection

import lastmile.model.ArtifactRecord

// Spec §12.4: EvidenceCollectorImpl — registers worker artifacts, writes structured
// verdict/failure/report artifacts through the ArtifactSink.
class EvidenceCollectorImpl(
  sink: ArtifactSink,
) extends EvidenceCollector {

  override def registerWorkerArtifacts(
    runId:           String,
    attemptNumber:   Int,
    workerArtifacts: List[WorkerArtifactRef],
  ): List[ArtifactRecord] = {
    workerArtifacts.map { ref =>
      sink.registerExternalArtifact(
        runId             = runId,
        attemptNumber     = Some(attemptNumber),
        artifactType      = ref.artifactType,
        producerComponent = "browser-worker",
        relativePath      = ref.relativePath,
        contentType       = ref.contentType,
        sizeBytes         = ref.sizeBytes,
        checksumSha256    = ref.checksumSha256,
        logicalScope      = None,
        label             = ref.label,
        metadata          = Map.empty,
      )
    }
  }

  override def writeVerdictArtifact(
    runId:         String,
    attemptNumber: Int,
    verdictId:     String,
    verdictJson:   String,
  ): ArtifactRecord = {
    val relPath = s"$runId/attempt_$attemptNumber/verdicts/verdict_$verdictId.json"
    sink.writeArtifact(
      runId             = runId,
      attemptNumber     = Some(attemptNumber),
      artifactType      = "StructuredVerdict",
      producerComponent = "verification-engine",
      content           = verdictJson.getBytes("UTF-8"),
      relativePath      = relPath,
      contentType       = "application/json",
      logicalScope      = Some(verdictId),
      label             = Some("verdict"),
    )
  }

  override def writeFailurePacketArtifact(
    runId:         String,
    attemptNumber: Int,
    packetId:      String,
    packetJson:    String,
  ): ArtifactRecord = {
    val relPath = s"$runId/attempt_$attemptNumber/failure_packets/packet_$packetId.json"
    sink.writeArtifact(
      runId             = runId,
      attemptNumber     = Some(attemptNumber),
      artifactType      = "FailurePacketArtifact",
      producerComponent = "failure-analysis",
      content           = packetJson.getBytes("UTF-8"),
      relativePath      = relPath,
      contentType       = "application/json",
      logicalScope      = Some(packetId),
      label             = Some("failure-packet"),
    )
  }

  override def writeFinalReportArtifact(
    runId:      String,
    reportJson: String,
  ): ArtifactRecord = {
    val relPath = s"$runId/report/final_report.json"
    sink.writeArtifact(
      runId             = runId,
      attemptNumber     = None,
      artifactType      = "FinalReport",
      producerComponent = "orchestrator",
      content           = reportJson.getBytes("UTF-8"),
      relativePath      = relPath,
      contentType       = "application/json",
      logicalScope      = None,
      label             = Some("final-report"),
    )
  }

  override def writeAttemptReportArtifact(
    runId:         String,
    attemptNumber: Int,
    reportJson:    String,
  ): ArtifactRecord = {
    val relPath = s"$runId/attempt_$attemptNumber/attempt_report.json"
    sink.writeArtifact(
      runId             = runId,
      attemptNumber     = Some(attemptNumber),
      artifactType      = "AttemptReport",
      producerComponent = "orchestrator",
      content           = reportJson.getBytes("UTF-8"),
      relativePath      = relPath,
      contentType       = "application/json",
      logicalScope      = None,
      label             = Some("attempt-report"),
    )
  }
}
