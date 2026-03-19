package demiurge.license

import java.net.{HttpURLConnection, URL}
import java.io.{BufferedReader, InputStreamReader}
import java.nio.file.{Files, Path, Paths}
import io.circe.parser.{decode => jsonDecode}
import io.circe.generic.semiauto._
import io.circe.syntax._

// Spec 05 §3.3: Usage metering — run counting + token reporting + offline sync
sealed trait UsageResult
case class UsageReport(uses: Int, maxUses: Int) extends UsageResult
case class UsageLimitExceeded(uses: Int, maxUses: Int) extends UsageResult
case class UsageReportError(message: String) extends UsageResult

object UsageReporter {

  private def baseUrl: String =
    CredentialStore.loadConfig().cloudApiUrl.getOrElse("https://demiurge.dev")

  private val demiurgeDir: Path = Paths.get(System.getProperty("user.home"), ".demiurge")
  private val offlineUsagePath: Path = demiurgeDir.resolve("offline-usage.json")

  // Circe codecs for response parsing
  private case class IncrementResponse(uses: Int, maxUses: Int)
  private implicit val incrementResponseDecoder: io.circe.Decoder[IncrementResponse] = deriveDecoder

  private case class OfflineUsage(unsyncedRuns: Int, lastSync: String)
  private implicit val offlineUsageCodec: io.circe.Codec[OfflineUsage] = io.circe.generic.semiauto.deriveCodec

  /**
   * Increment run count via the cloud backend.
   * The cloud backend proxies to Keygen's increment-usage endpoint.
   * Returns the updated usage count, or an error.
   *
   * On network failure, records an offline run for later sync.
   */
  def incrementRunCount(licenseKey: String, fingerprint: String, increment: Int = 1): Either[UsageResult, UsageReport] = {
    if (licenseKey.isEmpty) return Left(UsageReportError("No license key"))

    // Attempt to sync any previously offline runs first
    syncOfflineUsage(licenseKey, fingerprint)

    try {
      val url = new URL(s"$baseUrl/api/license/increment-usage")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setRequestProperty("X-License-Key", licenseKey)
      conn.setRequestProperty("X-Machine-Fingerprint", fingerprint)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setDoOutput(true)
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(10000)

      val payload = s"""{"increment":$increment}"""
      conn.getOutputStream.write(payload.getBytes("UTF-8"))
      conn.getOutputStream.close()

      val status = conn.getResponseCode
      val body = readBody(conn)

      status match {
        case 200 =>
          jsonDecode[IncrementResponse](body) match {
            case Right(r) => Right(UsageReport(r.uses, r.maxUses))
            case Left(e)  => Left(UsageReportError(s"Parse error: ${e.getMessage}"))
          }

        case 422 =>
          // maxUses exceeded — response shape matches IncrementResponse
          jsonDecode[IncrementResponse](body) match {
            case Right(r) => Left(UsageLimitExceeded(r.uses, r.maxUses))
            case Left(_)  => Left(UsageLimitExceeded(-1, -1))
          }

        case _ =>
          Left(UsageReportError(s"HTTP $status: $body"))
      }
    } catch {
      case e: Exception =>
        // Offline: record locally for later sync
        recordOfflineRun(increment)
        Left(UsageReportError(e.getMessage))
    }
  }

  /**
   * Report token consumption for a completed run.
   * This is fire-and-forget (best effort). Tokens are tracked locally in SQLite
   * and periodically synced to the cloud.
   */
  def reportTokenUsage(licenseKey: String, runId: String, inputTokens: Long, outputTokens: Long): Unit = {
    if (licenseKey.isEmpty) return

    try {
      val url = new URL(s"$baseUrl/api/license/report-tokens")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setRequestProperty("X-License-Key", licenseKey)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setDoOutput(true)
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(5000)

      // Use circe to avoid JSON injection from untrusted runId values
      val payload = io.circe.Json.obj(
        "run_id" -> io.circe.Json.fromString(runId),
        "input_tokens" -> io.circe.Json.fromLong(inputTokens),
        "output_tokens" -> io.circe.Json.fromLong(outputTokens),
      ).noSpaces
      conn.getOutputStream.write(payload.getBytes("UTF-8"))
      conn.getOutputStream.close()

      conn.getResponseCode // fire-and-forget, ignore response
    } catch {
      case _: Exception => // Silently ignore — best effort
    }
  }

  // --- Offline usage tracking (Spec 05 §8.1) ---

  /** Record an offline run in ~/.demiurge/offline-usage.json */
  private def recordOfflineRun(increment: Int): Unit = {
    try {
      Files.createDirectories(demiurgeDir)
      val current = loadOfflineUsage()
      val updated = current.copy(unsyncedRuns = current.unsyncedRuns + increment)
      Files.write(offlineUsagePath, updated.asJson.noSpaces.getBytes("UTF-8"))
    } catch {
      case _: Exception => // Best effort
    }
  }

  /** Sync offline usage on next successful network call. */
  private[license] def syncOfflineUsage(licenseKey: String, fingerprint: String): Unit = {
    val offline = loadOfflineUsage()
    if (offline.unsyncedRuns <= 0) return

    try {
      val url = new URL(s"$baseUrl/api/license/increment-usage")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setRequestProperty("X-License-Key", licenseKey)
      conn.setRequestProperty("X-Machine-Fingerprint", fingerprint)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setDoOutput(true)
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(5000)

      val payload = s"""{"increment":${offline.unsyncedRuns}}"""
      conn.getOutputStream.write(payload.getBytes("UTF-8"))
      conn.getOutputStream.close()

      val status = conn.getResponseCode
      if (status == 200 || status == 422) {
        // Successfully synced (or limit exceeded — either way, server has the count)
        resetOfflineUsage()
      }
    } catch {
      case _: Exception => // Still offline — will retry next time
    }
  }

  private def loadOfflineUsage(): OfflineUsage = {
    try {
      if (!Files.exists(offlineUsagePath)) return OfflineUsage(0, "")
      val json = new String(Files.readAllBytes(offlineUsagePath), "UTF-8")
      jsonDecode[OfflineUsage](json).getOrElse(OfflineUsage(0, ""))
    } catch {
      case _: Exception => OfflineUsage(0, "")
    }
  }

  private def resetOfflineUsage(): Unit = {
    try {
      val reset = OfflineUsage(0, java.time.Instant.now.toString)
      Files.write(offlineUsagePath, reset.asJson.noSpaces.getBytes("UTF-8"))
    } catch {
      case _: Exception => // Best effort
    }
  }

  private def readBody(conn: HttpURLConnection): String = {
    val stream = if (conn.getResponseCode >= 400) conn.getErrorStream else conn.getInputStream
    if (stream == null) return ""
    val reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))
    try {
      val sb = new StringBuilder
      var line = reader.readLine()
      while (line != null) { sb.append(line); line = reader.readLine() }
      sb.toString
    } finally reader.close()
  }
}
