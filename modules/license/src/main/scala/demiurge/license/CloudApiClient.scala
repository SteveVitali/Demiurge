package demiurge.license

import java.net.{HttpURLConnection, URL}
import java.io.{BufferedReader, InputStreamReader}
import io.circe.parser.decode
import io.circe.syntax._

object CloudApiClient {

  private def baseUrl: String =
    CredentialStore.loadConfig().cloudApiUrl.getOrElse("https://demiurge.dev")

  /** Validate a license key with machine fingerprint. */
  def validateLicense(licenseKey: String, fingerprint: String): LicenseStatus = {
    try {
      val url = new URL(s"$baseUrl/api/license/validate")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      conn.setRequestProperty("X-License-Key", licenseKey)
      conn.setRequestProperty("X-Machine-Fingerprint", fingerprint)
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(10000)

      val status = conn.getResponseCode
      val body = readBody(conn)

      status match {
        case 200 =>
          decode[ValidateResponse](body) match {
            case Right(r) if r.valid =>
              LicenseStatus.Valid(r.planTier, r.uses, r.maxUses, r.expiry, r.entitlements)
            case Right(r) =>
              r.code match {
                case "EXPIRED"            => LicenseStatus.Expired(r.expiry)
                case "SUSPENDED"          => LicenseStatus.Suspended("License suspended")
                case "NO_MACHINE"         => LicenseStatus.MachineNotActivated
                case "TOO_MANY_MACHINES"  => LicenseStatus.TooManyMachines
                case "OVER_LIMIT"         => LicenseStatus.OverLimit(r.uses, r.maxUses)
                case _                    => LicenseStatus.NotFound
              }
            case Left(_) => LicenseStatus.NetworkError("Failed to parse validation response")
          }
        case 403 =>
          decode[ValidateResponse](body).map { r =>
            r.code match {
              case "EXPIRED"           => LicenseStatus.Expired(r.expiry)
              case "SUSPENDED"         => LicenseStatus.Suspended("License suspended")
              case "NO_MACHINE"        => LicenseStatus.MachineNotActivated
              case "TOO_MANY_MACHINES" => LicenseStatus.TooManyMachines
              case "OVER_LIMIT"        => LicenseStatus.OverLimit(r.uses, r.maxUses)
              case _                   => LicenseStatus.NotFound
            }
          }.getOrElse(LicenseStatus.NotFound)
        case 404 => LicenseStatus.NotFound
        case _   => LicenseStatus.NetworkError(s"HTTP $status")
      }
    } catch {
      case e: Exception => LicenseStatus.NetworkError(e.getMessage)
    }
  }

  /** Activate a machine for a license. */
  def activateMachine(
    licenseKey: String,
    fingerprint: String,
    machineName: String,
    platform: String
  ): Either[String, String] = {
    try {
      val url = new URL(s"$baseUrl/api/license/activate")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setRequestProperty("X-License-Key", licenseKey)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setDoOutput(true)
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(10000)

      val payload = io.circe.Json.obj(
        "fingerprint" -> fingerprint.asJson,
        "name" -> machineName.asJson,
        "platform" -> platform.asJson,
        "hostname" -> MachineFingerprint.hostname().asJson
      ).noSpaces

      conn.getOutputStream.write(payload.getBytes("UTF-8"))
      conn.getOutputStream.close()

      val status = conn.getResponseCode
      val body = readBody(conn)

      if (status == 200 || status == 201) {
        Right("Machine activated successfully")
      } else {
        Left(s"Activation failed: $body")
      }
    } catch {
      case e: Exception => Left(s"Network error: ${e.getMessage}")
    }
  }

  /** Start device auth flow for CLI login. */
  def startDeviceAuth(): Either[String, DeviceCodeResponse] = {
    try {
      val url = new URL(s"$baseUrl/api/auth/device-code")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setDoOutput(true)
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(10000)
      conn.getOutputStream.write("{}".getBytes("UTF-8"))
      conn.getOutputStream.close()

      val body = readBody(conn)
      decode[DeviceCodeResponse](body).left.map(_.getMessage)
    } catch {
      case e: Exception => Left(s"Network error: ${e.getMessage}")
    }
  }

  /** Poll device auth status. */
  def pollDeviceAuth(deviceCode: String): Either[String, DevicePollResponse] = {
    try {
      val url = new URL(s"$baseUrl/api/auth/device-poll?device_code=$deviceCode")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(10000)

      val body = readBody(conn)
      decode[DevicePollResponse](body).left.map(_.getMessage)
    } catch {
      case e: Exception => Left(s"Network error: ${e.getMessage}")
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

  // Response DTOs
  case class ValidateResponse(
    valid: Boolean, code: String, planTier: String,
    uses: Int, maxUses: Int, expiry: String, entitlements: List[String]
  )
  case class DeviceCodeResponse(
    deviceCode: String, userCode: String, verificationUrl: String,
    expiresIn: Int, pollInterval: Int
  )
  case class DevicePollResponse(
    status: String, // "pending" | "authorized" | "expired"
    licenseKey: Option[String],
    planTier: Option[String],
    userEmail: Option[String]
  )

  // Circe codecs
  import io.circe.generic.semiauto._
  implicit val validateResponseDecoder: io.circe.Decoder[ValidateResponse] = deriveDecoder
  implicit val deviceCodeResponseDecoder: io.circe.Decoder[DeviceCodeResponse] = deriveDecoder
  implicit val devicePollResponseDecoder: io.circe.Decoder[DevicePollResponse] = deriveDecoder
}
