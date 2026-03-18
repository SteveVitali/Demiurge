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

  // Spec §14.1, §14.6: Prompt package assembly for repair.
  // Priority order: failure summary, screenshots, console errors, network summary, DB results, log tails.
  // Text-based artifacts inline (truncated to 4096 bytes). Binary artifacts referenced by path only.
  // Never include: full traces, full logs, inference logs, previous prompt packages.
  override def assemblePromptPackage(
    runId:           String,
    attemptNumber:   Int,
    failureSummary:  String,
    reproSteps:      String,
    reqDescriptions: List[String],
    artifacts:       List[ArtifactRecord],
    maxArtifacts:    Int,
    maxTotalBytes:   Long,
  ): PromptPackageResult = {
    val maxInlineBytes = 4096
    val neverIncludeTypes = Set("BrowserTrace", "InferenceLog", "PromptPackage", "ServiceLog")

    // Start with always-included header content
    val sb = new StringBuilder
    sb.append("=== FAILURE SUMMARY ===\n")
    sb.append(failureSummary).append("\n\n")
    sb.append("=== REPRODUCTION STEPS ===\n")
    sb.append(reproSteps).append("\n\n")
    sb.append("=== AFFECTED REQUIREMENTS ===\n")
    reqDescriptions.foreach(d => sb.append(s"- $d\n"))
    sb.append("\n")

    val included = scala.collection.mutable.ListBuffer[String]()
    val truncated = scala.collection.mutable.ListBuffer[String]()
    val omitted = scala.collection.mutable.ListBuffer[String]()
    var totalBytes = sb.length.toLong

    // Priority-sort artifacts: screenshots first, then console, network, DB, others
    val priorityOrder = List("Screenshot", "ConsoleLog", "NetworkSummary", "DbQueryResult",
      "StructuredVerdict", "FailurePacketArtifact")

    val sortedArtifacts = artifacts
      .filterNot(a => neverIncludeTypes.contains(a.artifactType.toString))
      .sortBy { a =>
        val idx = priorityOrder.indexOf(a.artifactType.toString)
        if (idx >= 0) idx else priorityOrder.size
      }

    for (artifact <- sortedArtifacts) {
      if (included.size >= maxArtifacts || totalBytes >= maxTotalBytes) {
        omitted += artifact.artifactId
      } else {
        val isText = artifact.contentType.startsWith("application/json") ||
          artifact.contentType.startsWith("text/")
        val isBinary = !isText

        if (isBinary) {
          // Binary: reference by path only
          val ref = s"[Binary artifact: ${artifact.artifactType} at ${artifact.relativePath} (${artifact.sizeBytes} bytes)]\n"
          if (totalBytes + ref.length <= maxTotalBytes) {
            sb.append(s"\n=== ${artifact.artifactType} (${artifact.relativePath}) ===\n")
            sb.append(ref)
            totalBytes += ref.length
            included += artifact.artifactId
          } else {
            omitted += artifact.artifactId
          }
        } else {
          // Text: inline, truncated to 4096 bytes
          val preview = if (artifact.sizeBytes > maxInlineBytes) {
            truncated += artifact.artifactId
            s"[Content truncated to $maxInlineBytes bytes of ${artifact.sizeBytes}]\n"
          } else {
            s"[Content: ${artifact.sizeBytes} bytes]\n"
          }

          val entrySize = preview.length + 80 // header overhead
          if (totalBytes + entrySize <= maxTotalBytes) {
            sb.append(s"\n=== ${artifact.artifactType} (${artifact.relativePath}) ===\n")
            sb.append(preview)
            totalBytes += entrySize
            included += artifact.artifactId
          } else {
            omitted += artifact.artifactId
          }
        }
      }
    }

    val textContent = sb.toString()
    val relPath = s"$runId/attempt_$attemptNumber/repair/prompt-package.json"
    val record = sink.writeArtifact(
      runId             = runId,
      attemptNumber     = Some(attemptNumber),
      artifactType      = "PromptPackage",
      producerComponent = "evidence-collector",
      content           = textContent.getBytes("UTF-8"),
      relativePath      = relPath,
      contentType       = "application/json",
      logicalScope      = None,
      label             = Some("prompt-package"),
    )

    PromptPackageResult(
      artifactRecord     = record,
      textContent        = textContent,
      includedArtifacts  = included.toList,
      truncatedArtifacts = truncated.toList,
      omittedArtifacts   = omitted.toList,
    )
  }
}
