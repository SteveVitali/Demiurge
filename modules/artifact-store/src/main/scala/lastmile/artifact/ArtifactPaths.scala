package lastmile.artifact

import java.nio.file.{Path, Paths}

// Spec §12.1: Canonical artifact directory structure
// <artifactRoot>/<runId>/<attemptNumber>/<artifactType>/<filename>
object ArtifactPaths {

  def artifactRoot(baseDir: Path, runId: String): Path =
    baseDir.resolve(runId)

  def attemptDir(baseDir: Path, runId: String, attemptNumber: Int): Path =
    baseDir.resolve(runId).resolve(s"attempt_$attemptNumber")

  def artifactTypeDir(baseDir: Path, runId: String, attemptNumber: Int, artifactType: String): Path =
    attemptDir(baseDir, runId, attemptNumber).resolve(artifactType.toLowerCase)

  def screenshotPath(baseDir: Path, runId: String, attemptNumber: Int, label: String, timestamp: Long): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "screenshots")
      .resolve(s"${label}_$timestamp.png")

  def tracePath(baseDir: Path, runId: String, attemptNumber: Int, timestamp: Long): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "traces")
      .resolve(s"trace_$timestamp.zip")

  def consoleLogPath(baseDir: Path, runId: String, attemptNumber: Int, timestamp: Long): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "console")
      .resolve(s"console_$timestamp.json")

  def networkSummaryPath(baseDir: Path, runId: String, attemptNumber: Int, timestamp: Long): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "network")
      .resolve(s"network_$timestamp.json")

  def domSnapshotPath(baseDir: Path, runId: String, attemptNumber: Int, label: String, timestamp: Long): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "dom")
      .resolve(s"${label}_$timestamp.html")

  def accessibilitySnapshotPath(baseDir: Path, runId: String, attemptNumber: Int, label: String, timestamp: Long): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "accessibility")
      .resolve(s"${label}_$timestamp.json")

  def storageStatePath(baseDir: Path, runId: String, attemptNumber: Int, timestamp: Long): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "auth")
      .resolve(s"storage_state_$timestamp.json")

  def verdictArtifactPath(baseDir: Path, runId: String, attemptNumber: Int, verdictId: String): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "verdicts")
      .resolve(s"verdict_$verdictId.json")

  def failurePacketArtifactPath(baseDir: Path, runId: String, attemptNumber: Int, packetId: String): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "failure_packets")
      .resolve(s"packet_$packetId.json")

  def repairTranscriptPath(baseDir: Path, runId: String, attemptNumber: Int, timestamp: Long): Path =
    artifactTypeDir(baseDir, runId, attemptNumber, "repair_transcripts")
      .resolve(s"transcript_$timestamp.json")

  def finalReportPath(baseDir: Path, runId: String): Path =
    baseDir.resolve(runId).resolve("report").resolve("final_report.json")

  def attemptReportPath(baseDir: Path, runId: String, attemptNumber: Int): Path =
    attemptDir(baseDir, runId, attemptNumber).resolve("attempt_report.json")

  // Spec §12.1: Relative path from artifact root for storage
  def relativize(artifactRoot: Path, absolutePath: Path): String =
    artifactRoot.relativize(absolutePath).toString
}
