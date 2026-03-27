# Spec 02: CLI & Desktop License Integration

> **Parent document:** [plan-go-to-market.md](./plan-go-to-market.md)
> **Phase:** 2 of 5 — Depends on Spec 01 (cloud backend must exist to validate against).
> **Estimated effort:** 4–5 days.

---

## 1. Overview

This spec covers all client-side changes to the Demiurge CLI (Scala) and desktop app (Tauri + React) to integrate with the cloud backend defined in Spec 01. After this spec is implemented:

- Users can **log in** via CLI (`demiurge login`) or desktop app (sign-in screen)
- Every `demiurge run` / `demiurge build` validates the user's license before proceeding
- The desktop app shows plan tier, usage, and upgrade prompts
- BYOK API key configuration is supported (`demiurge config set anthropic-api-key`)
- Machine activation happens transparently on first run

---

## 2. Credential Storage

### 2.1 File Location

All auth credentials are stored in `~/.demiurge/credentials.json`. This file is **not** inside any repo — it's global to the user's machine.

```
~/.demiurge/
├── credentials.json      # Auth credentials (license key, tokens)
├── machine-id            # Stable machine fingerprint (generated once)
└── config.json           # User preferences (BYOK API keys, defaults)
```

### 2.2 credentials.json Schema

```json
{
  "license_key": "DEMI-XXXX-XXXX-XXXX",
  "user_email": "user@example.com",
  "plan_tier": "pro",
  "auth_method": "device_code" | "license_key",
  "cached_validation": {
    "valid": true,
    "code": "VALID",
    "plan_tier": "pro",
    "uses": 42,
    "max_uses": 200,
    "expiry": "2026-04-19T00:00:00Z",
    "entitlements": ["agent_repair", "browser_verification", "build_mode"],
    "cached_at": "2026-03-19T15:30:00Z"
  }
}
```

### 2.3 machine-id File

Generated once on first run. Contains the machine fingerprint:

```
sha256_hex_of_hostname_os_arch_platform_username
```

Generation (Scala):
```scala
import java.security.MessageDigest
import java.net.InetAddress

object MachineFingerprint {
  def generate(): String = {
    val hostname = InetAddress.getLocalHost.getHostName
    val osName = System.getProperty("os.name", "unknown")
    val osArch = System.getProperty("os.arch", "unknown")
    val userName = System.getProperty("user.name", "unknown")
    val raw = s"$hostname|$osName|$osArch|$userName"
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(raw.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }
}
```

### 2.4 config.json Schema

```json
{
  "anthropic_api_key": "sk-ant-...",
  "openai_api_key": null,
  "preferred_provider": "anthropic",
  "cloud_api_url": "https://demiurge.dev"
}
```

---

## 3. New Scala Module: `modules/license`

Create a new Bazel module for license management.

### 3.1 Module Structure

```
modules/license/
├── BUILD.bazel
└── src/
    └── main/
        └── scala/
            └── demiurge/
                └── license/
                    ├── LicenseManager.scala
                    ├── CredentialStore.scala
                    ├── MachineFingerprint.scala
                    ├── CloudApiClient.scala
                    └── LicenseStatus.scala
```

### 3.2 BUILD.bazel

```python
load("@rules_scala//scala:scala.bzl", "scala_library")

scala_library(
    name = "license",
    srcs = glob(["src/main/scala/**/*.scala"]),
    visibility = ["//visibility:public"],
    deps = [
        "//modules/core-model",
        "@maven//:io_circe_circe_core_2_13",
        "@maven//:io_circe_circe_generic_2_13",
        "@maven//:io_circe_circe_parser_2_13",
    ],
)
```

### 3.3 LicenseStatus.scala

```scala
package demiurge.license

sealed trait LicenseStatus
object LicenseStatus {
  case class Valid(
    planTier: String,
    uses: Int,
    maxUses: Int,
    expiry: String,
    entitlements: List[String]
  ) extends LicenseStatus

  case class Expired(expiry: String) extends LicenseStatus
  case class Suspended(reason: String) extends LicenseStatus
  case class OverLimit(uses: Int, maxUses: Int) extends LicenseStatus
  case object MachineNotActivated extends LicenseStatus
  case object TooManyMachines extends LicenseStatus
  case object NotFound extends LicenseStatus
  case object NoCredentials extends LicenseStatus
  case class NetworkError(message: String) extends LicenseStatus
}
```

### 3.4 CredentialStore.scala

```scala
package demiurge.license

import java.nio.file.{Files, Path, Paths}
import io.circe._, io.circe.generic.semiauto._, io.circe.parser._, io.circe.syntax._

case class CachedValidation(
  valid: Boolean,
  code: String,
  planTier: String,
  uses: Int,
  maxUses: Int,
  expiry: String,
  entitlements: List[String],
  cachedAt: String
)

case class Credentials(
  licenseKey: String,
  userEmail: Option[String],
  planTier: Option[String],
  authMethod: Option[String],
  cachedValidation: Option[CachedValidation]
)

case class UserConfig(
  anthropicApiKey: Option[String] = None,
  openaiApiKey: Option[String] = None,
  preferredProvider: Option[String] = Some("anthropic"),
  cloudApiUrl: Option[String] = Some("https://demiurge.dev")
)

object CredentialStore {
  // Implicit codecs derived via circe semiauto
  implicit val cachedValidationCodec: Codec[CachedValidation] = deriveCodec
  implicit val credentialsCodec: Codec[Credentials] = deriveCodec
  implicit val userConfigCodec: Codec[UserConfig] = deriveCodec

  private val demiurgeDir: Path = Paths.get(System.getProperty("user.home"), ".demiurge")
  private val credentialsPath: Path = demiurgeDir.resolve("credentials.json")
  private val configPath: Path = demiurgeDir.resolve("config.json")
  private val machineIdPath: Path = demiurgeDir.resolve("machine-id")

  def ensureDir(): Unit = Files.createDirectories(demiurgeDir)

  // --- Credentials ---
  def loadCredentials(): Option[Credentials] = {
    if (!Files.exists(credentialsPath)) return None
    val json = new String(Files.readAllBytes(credentialsPath), "UTF-8")
    decode[Credentials](json).toOption
  }

  def saveCredentials(creds: Credentials): Unit = {
    ensureDir()
    Files.write(credentialsPath, creds.asJson.spaces2.getBytes("UTF-8"))
  }

  def clearCredentials(): Unit = {
    if (Files.exists(credentialsPath)) Files.delete(credentialsPath)
  }

  // --- Config ---
  def loadConfig(): UserConfig = {
    if (!Files.exists(configPath)) return UserConfig()
    val json = new String(Files.readAllBytes(configPath), "UTF-8")
    decode[UserConfig](json).getOrElse(UserConfig())
  }

  def saveConfig(config: UserConfig): Unit = {
    ensureDir()
    Files.write(configPath, config.asJson.spaces2.getBytes("UTF-8"))
  }

  // --- Machine ID ---
  def getMachineFingerprint(): String = {
    ensureDir()
    if (Files.exists(machineIdPath)) {
      new String(Files.readAllBytes(machineIdPath), "UTF-8").trim
    } else {
      val fp = MachineFingerprint.generate()
      Files.write(machineIdPath, fp.getBytes("UTF-8"))
      fp
    }
  }
}
```

### 3.5 CloudApiClient.scala

HTTP client for the cloud backend API routes defined in Spec 01. Uses JDK built-in `HttpURLConnection` (no external deps, consistent with the rest of the codebase).

```scala
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
        "hostname" -> java.net.InetAddress.getLocalHost.getHostName.asJson
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
```

### 3.6 LicenseManager.scala

Orchestrates license validation with caching and offline fallback.

```scala
package demiurge.license

import java.time.{Duration, Instant}

object LicenseManager {

  private val CACHE_TTL_HOURS = 72 // Offline grace period
  private val ONLINE_CACHE_TTL_MINUTES = 30 // Re-validate every 30 min when online

  /**
   * Validate the user's license. Called before every `run` or `build` command.
   * Returns LicenseStatus.Valid on success, or an error status.
   *
   * Flow:
   * 1. Load credentials from ~/.demiurge/credentials.json
   * 2. If no credentials → return NoCredentials
   * 3. If cached validation exists and is fresh enough → return cached
   * 4. Call cloud API to validate
   * 5. If network error and cached validation < 72h old → return cached (offline grace)
   * 6. If network error and no cache → return NetworkError
   * 7. On success → update cache → return Valid
   * 8. If MachineNotActivated → auto-activate → retry
   */
  def validate(): LicenseStatus = {
    val creds = CredentialStore.loadCredentials() match {
      case Some(c) => c
      case None    => return LicenseStatus.NoCredentials
    }

    val fingerprint = CredentialStore.getMachineFingerprint()

    // Check cache freshness
    creds.cachedValidation match {
      case Some(cached) if isFresh(cached.cachedAt, ONLINE_CACHE_TTL_MINUTES) && cached.valid =>
        return LicenseStatus.Valid(cached.planTier, cached.uses, cached.maxUses, cached.expiry, cached.entitlements)
      case _ => // Cache stale or missing, need to validate online
    }

    // Online validation
    CloudApiClient.validateLicense(creds.licenseKey, fingerprint) match {
      case valid: LicenseStatus.Valid =>
        // Update cache
        val newCached = CachedValidation(
          valid = true, code = "VALID", planTier = valid.planTier,
          uses = valid.uses, maxUses = valid.maxUses,
          expiry = valid.expiry, entitlements = valid.entitlements,
          cachedAt = Instant.now.toString
        )
        CredentialStore.saveCredentials(creds.copy(
          planTier = Some(valid.planTier),
          cachedValidation = Some(newCached)
        ))
        valid

      case LicenseStatus.MachineNotActivated =>
        // Auto-activate this machine
        val hostname = java.net.InetAddress.getLocalHost.getHostName
        val platform = System.getProperty("os.name", "unknown")
        CloudApiClient.activateMachine(creds.licenseKey, fingerprint, hostname, platform) match {
          case Right(_) =>
            // Retry validation after activation
            CloudApiClient.validateLicense(creds.licenseKey, fingerprint) match {
              case valid: LicenseStatus.Valid =>
                val newCached = CachedValidation(
                  valid = true, code = "VALID", planTier = valid.planTier,
                  uses = valid.uses, maxUses = valid.maxUses,
                  expiry = valid.expiry, entitlements = valid.entitlements,
                  cachedAt = Instant.now.toString
                )
                CredentialStore.saveCredentials(creds.copy(
                  planTier = Some(valid.planTier),
                  cachedValidation = Some(newCached)
                ))
                valid
              case other => other
            }
          case Left(err) => LicenseStatus.NetworkError(s"Machine activation failed: $err")
        }

      case LicenseStatus.NetworkError(_) =>
        // Offline fallback: use cached validation if within grace period
        creds.cachedValidation match {
          case Some(cached) if isWithinGracePeriod(cached.cachedAt) && cached.valid =>
            System.err.println("[demiurge] Warning: Using cached license validation (offline mode)")
            LicenseStatus.Valid(cached.planTier, cached.uses, cached.maxUses, cached.expiry, cached.entitlements)
          case _ =>
            LicenseStatus.NetworkError("Cannot validate license: no network and cache expired")
        }

      case other => other
    }
  }

  private def isFresh(cachedAt: String, minutes: Int): Boolean = {
    try {
      val cached = Instant.parse(cachedAt)
      Duration.between(cached, Instant.now).toMinutes < minutes
    } catch { case _: Exception => false }
  }

  private def isWithinGracePeriod(cachedAt: String): Boolean = {
    try {
      val cached = Instant.parse(cachedAt)
      Duration.between(cached, Instant.now).toHours < CACHE_TTL_HOURS
    } catch { case _: Exception => false }
  }
}
```

---

## 4. New CLI Commands

### 4.1 `demiurge login`

Add to `CommandParsers.scala`:

```scala
case class LoginCmd(
  licenseKey: Option[String] = None  // --license-key for headless/CI
) extends ParsedCommand
```

Add to parser:
```scala
case "login" => parseLoginCmd(args, global)
```

Add to dispatch:
```scala
case c: LoginCmd => LoginCommand.execute(c, global)
```

#### LoginCommand.scala

```scala
package demiurge.cli.Commands

import demiurge.cli.{ExitCodes, CommandParsers}
import demiurge.cli.CommandParsers.{GlobalOpts, LoginCmd}
import demiurge.license.{CloudApiClient, CredentialStore, Credentials}

object LoginCommand {

  def execute(cmd: LoginCmd, global: GlobalOpts): Int = {
    cmd.licenseKey match {
      case Some(key) => loginWithKey(key)
      case None      => loginWithDeviceCode()
    }
  }

  private def loginWithKey(key: String): Int = {
    System.out.println("Validating license key...")
    val fingerprint = CredentialStore.getMachineFingerprint()

    CloudApiClient.validateLicense(key, fingerprint) match {
      case valid: demiurge.license.LicenseStatus.Valid =>
        CredentialStore.saveCredentials(Credentials(
          licenseKey = key,
          userEmail = None,
          planTier = Some(valid.planTier),
          authMethod = Some("license_key"),
          cachedValidation = None
        ))
        System.out.println(s"✓ Logged in successfully (${valid.planTier} plan)")
        ExitCodes.Success

      case demiurge.license.LicenseStatus.MachineNotActivated =>
        // Auto-activate then retry
        val hostname = java.net.InetAddress.getLocalHost.getHostName
        val platform = System.getProperty("os.name", "unknown")
        CloudApiClient.activateMachine(key, fingerprint, hostname, platform) match {
          case Right(_) =>
            CredentialStore.saveCredentials(Credentials(
              licenseKey = key, userEmail = None,
              planTier = None, authMethod = Some("license_key"),
              cachedValidation = None
            ))
            System.out.println("✓ Machine activated and logged in")
            ExitCodes.Success
          case Left(err) =>
            System.err.println(s"Error: $err")
            ExitCodes.Errored
        }

      case other =>
        System.err.println(s"Error: License validation failed — $other")
        ExitCodes.Errored
    }
  }

  private def loginWithDeviceCode(): Int = {
    CloudApiClient.startDeviceAuth() match {
      case Left(err) =>
        System.err.println(s"Error: $err")
        ExitCodes.Errored

      case Right(device) =>
        System.out.println()
        System.out.println(s"  Visit: ${device.verificationUrl}")
        System.out.println(s"  Enter code: ${device.userCode}")
        System.out.println()
        System.out.println("Waiting for authorization...")

        val deadline = System.currentTimeMillis + (device.expiresIn * 1000L)
        val pollMs = device.pollInterval * 1000L

        while (System.currentTimeMillis < deadline) {
          Thread.sleep(pollMs)
          CloudApiClient.pollDeviceAuth(device.deviceCode) match {
            case Right(poll) if poll.status == "authorized" =>
              val key = poll.licenseKey.getOrElse("")
              CredentialStore.saveCredentials(Credentials(
                licenseKey = key,
                userEmail = poll.userEmail,
                planTier = poll.planTier,
                authMethod = Some("device_code"),
                cachedValidation = None
              ))
              System.out.println(s"✓ Logged in as ${poll.userEmail.getOrElse("user")} (${poll.planTier.getOrElse("trial")} plan)")
              return ExitCodes.Success

            case Right(poll) if poll.status == "expired" =>
              System.err.println("Error: Authorization code expired. Try again.")
              return ExitCodes.Errored

            case Right(_) => // pending, keep polling
              System.out.print(".")

            case Left(err) =>
              // Network error during poll, keep trying
              System.err.print("!")
          }
        }

        System.err.println("\nError: Authorization timed out. Try again.")
        ExitCodes.Errored
    }
  }
}
```

### 4.2 `demiurge logout`

Add to `CommandParsers.scala`:

```scala
case object LogoutCmd extends ParsedCommand
```

#### LogoutCommand.scala

```scala
package demiurge.cli.Commands

import demiurge.cli.ExitCodes
import demiurge.cli.CommandParsers.GlobalOpts
import demiurge.license.CredentialStore

object LogoutCommand {
  def execute(global: GlobalOpts): Int = {
    CredentialStore.clearCredentials()
    System.out.println("Logged out. Credentials cleared.")
    ExitCodes.Success
  }
}
```

### 4.3 `demiurge config`

Add to `CommandParsers.scala`:

```scala
case class ConfigCmd(
  action: String,         // "set" | "get" | "list"
  key: Option[String] = None,
  value: Option[String] = None
) extends ParsedCommand
```

#### ConfigCommand.scala

```scala
package demiurge.cli.Commands

import demiurge.cli.ExitCodes
import demiurge.cli.CommandParsers.{GlobalOpts, ConfigCmd}
import demiurge.license.{CredentialStore, UserConfig}

object ConfigCommand {

  private val VALID_KEYS = Set(
    "anthropic-api-key", "openai-api-key", "preferred-provider", "cloud-api-url"
  )

  def execute(cmd: ConfigCmd, global: GlobalOpts): Int = {
    cmd.action match {
      case "set" => setConfig(cmd.key, cmd.value)
      case "get" => getConfig(cmd.key)
      case "list" => listConfig()
      case other =>
        System.err.println(s"Unknown config action: $other. Use: set, get, list")
        ExitCodes.InputError
    }
  }

  private def setConfig(key: Option[String], value: Option[String]): Int = {
    (key, value) match {
      case (Some(k), Some(v)) if VALID_KEYS.contains(k) =>
        val config = CredentialStore.loadConfig()
        val updated = k match {
          case "anthropic-api-key"  => config.copy(anthropicApiKey = Some(v))
          case "openai-api-key"     => config.copy(openaiApiKey = Some(v))
          case "preferred-provider" => config.copy(preferredProvider = Some(v))
          case "cloud-api-url"      => config.copy(cloudApiUrl = Some(v))
          case _ => config
        }
        CredentialStore.saveConfig(updated)
        System.out.println(s"✓ Set $k")
        ExitCodes.Success
      case (Some(k), _) if !VALID_KEYS.contains(k) =>
        System.err.println(s"Unknown config key: $k. Valid keys: ${VALID_KEYS.mkString(", ")}")
        ExitCodes.InputError
      case _ =>
        System.err.println("Usage: demiurge config set <key> <value>")
        ExitCodes.InputError
    }
  }

  private def getConfig(key: Option[String]): Int = {
    val config = CredentialStore.loadConfig()
    key match {
      case Some("anthropic-api-key")  => printMasked(config.anthropicApiKey)
      case Some("openai-api-key")     => printMasked(config.openaiApiKey)
      case Some("preferred-provider") => System.out.println(config.preferredProvider.getOrElse("(not set)"))
      case Some("cloud-api-url")      => System.out.println(config.cloudApiUrl.getOrElse("(not set)"))
      case Some(k)                    => System.err.println(s"Unknown key: $k"); return ExitCodes.InputError
      case None                       => System.err.println("Usage: demiurge config get <key>"); return ExitCodes.InputError
    }
    ExitCodes.Success
  }

  private def listConfig(): Int = {
    val config = CredentialStore.loadConfig()
    System.out.println(s"  anthropic-api-key:  ${maskKey(config.anthropicApiKey)}")
    System.out.println(s"  openai-api-key:     ${maskKey(config.openaiApiKey)}")
    System.out.println(s"  preferred-provider: ${config.preferredProvider.getOrElse("(not set)")}")
    System.out.println(s"  cloud-api-url:      ${config.cloudApiUrl.getOrElse("https://demiurge.dev")}")
    ExitCodes.Success
  }

  private def maskKey(key: Option[String]): String = key match {
    case Some(k) if k.length > 8 => k.take(4) + "..." + k.takeRight(4)
    case Some(_) => "****"
    case None    => "(not set)"
  }

  private def printMasked(key: Option[String]): Unit =
    System.out.println(maskKey(key))
}
```

### 4.4 `demiurge status` Enhancement

When `demiurge status` is called with no run ID, also show license/plan info:

```
Demiurge v0.1.0
Plan: Pro ($79/mo)
Usage: 42 / 200 runs this period
Expires: Apr 19, 2026
API Key: sk-a...1234 (Anthropic)

Recent runs:
  ...
```

---

## 5. License Gate in RunOrchestrator

### 5.1 Where to Add the Gate

The license check MUST happen **before** any run begins. The cleanest insertion point is in `CliApp.dispatch()`, immediately before calling `RunCommand.execute()` or `BuildCommand.execute()`.

### 5.2 Gated Commands

Commands that require a valid license:
- `run` — requires valid license + increments usage
- `build` — requires valid license + increments usage
- `resume` — requires valid license (does NOT increment usage again)

Commands that work WITHOUT a license (free / read-only):
- `status`, `inspect-run`, `open-artifact`, `explain-failure` — read-only queries
- `doctor` — system check
- `init` — config generation
- `plan` — planning only (no execution)
- `login`, `logout`, `config` — auth management
- `serve` — backend server (validates on individual run requests)
- `cancel`, `clean` — maintenance

### 5.3 Implementation in CliApp.scala

Modify the `dispatch` method to add a license gate:

```scala
private def dispatch(cmd: ParsedCommand, global: GlobalOpts, conn: Connection): Int = {
  // License gate for run-lifecycle commands
  cmd match {
    case _: RunCmd | _: BuildCmd | _: ResumeCmd =>
      LicenseManager.validate() match {
        case LicenseStatus.Valid(_, _, _, _, _) => // OK, proceed
        case LicenseStatus.NoCredentials =>
          System.err.println("Error: Not logged in. Run `demiurge login` to authenticate.")
          return ExitCodes.InputError
        case LicenseStatus.Expired(expiry) =>
          System.err.println(s"Error: License expired on $expiry. Renew at https://demiurge.dev/billing")
          return ExitCodes.InputError
        case LicenseStatus.Suspended(_) =>
          System.err.println("Error: License suspended. Contact support or resubscribe at https://demiurge.dev/billing")
          return ExitCodes.InputError
        case LicenseStatus.OverLimit(uses, maxUses) =>
          System.err.println(s"Error: Usage limit reached ($uses/$maxUses runs). Upgrade at https://demiurge.dev/pricing")
          return ExitCodes.InputError
        case LicenseStatus.TooManyMachines =>
          System.err.println("Error: Machine limit reached. Deactivate a machine or upgrade your plan.")
          return ExitCodes.InputError
        case LicenseStatus.MachineNotActivated =>
          System.err.println("Error: Machine not activated. Run `demiurge login` again.")
          return ExitCodes.InputError
        case LicenseStatus.NotFound =>
          System.err.println("Error: License not found. Run `demiurge login` to authenticate.")
          return ExitCodes.InputError
        case LicenseStatus.NetworkError(msg) =>
          System.err.println(s"Error: Cannot validate license — $msg")
          return ExitCodes.Errored
      }
    case _ => // No gate needed
  }

  // Original dispatch logic
  cmd match {
    case c: RunCmd            => RunCommand.execute(c, global, conn)
    case c: BuildCmd          => BuildCommand.execute(c, global, conn)
    // ... rest unchanged
    case c: LoginCmd          => LoginCommand.execute(c, global)
    case LogoutCmd            => LogoutCommand.execute(global)
    case c: ConfigCmd         => ConfigCommand.execute(c, global)
  }
}
```

### 5.4 BYOK API Key Integration

Currently, the Demiurge orchestrator reads `ANTHROPIC_API_KEY` from the environment. With BYOK, we need to also check `~/.demiurge/config.json`.

**Modify the API key resolution order** (in `RunCommand.scala` or wherever the Anthropic key is resolved):

```
1. Environment variable: ANTHROPIC_API_KEY (highest priority — CI/scripts)
2. User config: ~/.demiurge/config.json → anthropic_api_key
3. Not set → error: "No API key configured. Run `demiurge config set anthropic-api-key <key>`"
```

This change should be applied in the existing code that reads `ANTHROPIC_API_KEY`. Find all `sys.env.get("ANTHROPIC_API_KEY")` or `System.getenv("ANTHROPIC_API_KEY")` calls and replace with:

```scala
def resolveApiKey(envVar: String, configKey: String): Option[String] = {
  Option(System.getenv(envVar))
    .orElse {
      val config = CredentialStore.loadConfig()
      configKey match {
        case "anthropic" => config.anthropicApiKey
        case "openai"    => config.openaiApiKey
        case _           => None
      }
    }
}
```

---

## 6. Desktop App Changes

### 6.1 Auth Screen (New Route)

Add a new route `/auth` that is shown when the user is not logged in. The root layout (`AppLayout`) checks for credentials and redirects to `/auth` if missing.

**File:** `desktop/src/screens/AuthScreen.tsx`

The auth screen shows:
1. "Welcome to Demiurge" header
2. Two options:
   - **"Sign in with browser"** — Opens `https://demiurge.dev/sign-in?redirect_uri=demiurge://auth-callback` in the default browser via Tauri's shell open. The callback deep link carries the license key.
   - **"Enter license key"** — Text input for manual key entry (CI/offline use)
3. After successful auth, redirects to `/` (dashboard)

### 6.2 Deep Link Handler (Tauri Rust Side)

Register the `demiurge://` custom URL scheme using `tauri-plugin-deep-link`.

**Changes to `desktop/src-tauri/Cargo.toml`:**
```toml
tauri-plugin-deep-link = "2"
tauri-plugin-single-instance = { version = "2", features = ["deep-link"] }
```

**Changes to `desktop/src-tauri/tauri.conf.json`:**
```json
{
  "plugins": {
    "deep-link": {
      "desktop": {
        "schemes": ["demiurge"]
      }
    }
  }
}
```

**Changes to `desktop/src-tauri/src/lib.rs`:**
- Add `tauri-plugin-single-instance` (must be first plugin registered)
- Add `tauri-plugin-deep-link`
- In `setup`, listen for deep link events and emit them to the frontend

### 6.3 Auth State in Frontend

**New file:** `desktop/src/stores/auth.store.ts`

```typescript
import { create } from 'zustand';
import { Store } from '@tauri-apps/plugin-store';

interface AuthState {
  licenseKey: string | null;
  planTier: string | null;
  userEmail: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;

  loadCredentials: () => Promise<void>;
  setCredentials: (key: string, email?: string, tier?: string) => Promise<void>;
  clearCredentials: () => Promise<void>;
}

const tauriStore = new Store('.credentials.json');

export const useAuthStore = create<AuthState>((set) => ({
  licenseKey: null,
  planTier: null,
  userEmail: null,
  isAuthenticated: false,
  isLoading: true,

  loadCredentials: async () => {
    try {
      const key = await tauriStore.get<string>('license_key');
      const tier = await tauriStore.get<string>('plan_tier');
      const email = await tauriStore.get<string>('user_email');
      set({
        licenseKey: key ?? null,
        planTier: tier ?? null,
        userEmail: email ?? null,
        isAuthenticated: !!key,
        isLoading: false,
      });
    } catch {
      set({ isLoading: false });
    }
  },

  setCredentials: async (key, email, tier) => {
    await tauriStore.set('license_key', key);
    if (email) await tauriStore.set('user_email', email);
    if (tier) await tauriStore.set('plan_tier', tier);
    await tauriStore.save();
    set({
      licenseKey: key,
      userEmail: email ?? null,
      planTier: tier ?? null,
      isAuthenticated: true,
    });
  },

  clearCredentials: async () => {
    await tauriStore.delete('license_key');
    await tauriStore.delete('plan_tier');
    await tauriStore.delete('user_email');
    await tauriStore.save();
    set({
      licenseKey: null, planTier: null, userEmail: null,
      isAuthenticated: false,
    });
  },
}));
```

### 6.4 Auth Guard in Root Layout

Modify `AppLayout` to check auth state on mount:

```typescript
// In AppLayout.tsx
const { isAuthenticated, isLoading, loadCredentials } = useAuthStore();

useEffect(() => { loadCredentials(); }, []);

if (isLoading) return <LoadingSpinner />;
if (!isAuthenticated) return <Navigate to="/auth" />;
return <>{/* normal layout */}</>;
```

### 6.5 Settings Screen Additions

Add to the existing Settings screen (`desktop/src/screens/SettingsScreen.tsx`):

- **Account section:** Show email, plan tier, usage, "Manage Billing" button (opens Stripe portal URL)
- **API Keys section:** Input fields for Anthropic API key and OpenAI API key (stored in Tauri store, synced to `~/.demiurge/config.json` via sidecar command)
- **Sign Out button:** Calls `clearCredentials()` and redirects to `/auth`

### 6.6 Plan Badge in Sidebar/Header

Show the current plan tier as a small badge in the app sidebar or header:
- Trial → yellow badge with "Trial" and days remaining
- Starter/Pro/Team → green badge with plan name
- Expired → red badge with "Expired — Upgrade"

---

## 7. Updated Help Text and Command List

### 7.1 CliApp.printHelp()

Add the new commands to the help output:

```
Commands:
  login               Authenticate with Demiurge (opens browser or use --license-key)
  logout              Clear stored credentials
  config              Manage configuration (set/get/list API keys, preferences)
  run                 Execute a full verification run
  build               Build a new feature (generate + verify + repair)
  ...
```

### 7.2 `demiurge login --help`

```
demiurge login — Authenticate with Demiurge

Usage: demiurge login [flags]

Flags:
  --license-key <KEY>   Authenticate with a license key directly (for CI/headless)

Without --license-key, opens a browser-based device code flow:
  1. A unique code is generated
  2. You visit https://demiurge.dev/activate and sign in
  3. Enter the code to link your account
  4. The CLI receives your license key automatically
```

---

## 8. Changes to Existing Files (Summary)

| File | Change |
|------|--------|
| `modules/cli/src/main/scala/demiurge/cli/CommandParsers.scala` | Add `LoginCmd`, `LogoutCmd`, `ConfigCmd` to ADT + parsers |
| `modules/cli/src/main/scala/demiurge/cli/CliApp.scala` | Add license gate in `dispatch()`, add new command routes, `login`/`logout`/`config` dispatch BEFORE DB open (they don't need it) |
| `modules/cli/BUILD.bazel` | Add dependency on `//modules/license` |
| `modules/orchestrator/.../RunOrchestrator.scala` | No changes (gate is at CLI level) |
| `desktop/src-tauri/Cargo.toml` | Add `tauri-plugin-deep-link` and `tauri-plugin-single-instance` |
| `desktop/src-tauri/tauri.conf.json` | Add deep-link plugin config |
| `desktop/src-tauri/src/lib.rs` | Add deep-link and single-instance plugins, deep link event handler |
| `desktop/src-tauri/capabilities/default.json` | Add `deep-link:default` permission |
| `desktop/src/main.tsx` | No changes |
| `desktop/src/lib/routes.ts` | Add `/auth` route |
| `desktop/src/stores/auth.store.ts` | New file |
| `desktop/src/screens/AuthScreen.tsx` | New file |
| `desktop/src/screens/SettingsScreen.tsx` | Add account, API keys, sign-out sections |
| `desktop/src/components/layout/AppLayout.tsx` | Add auth guard |
| `desktop/package.json` | Add `@tauri-apps/plugin-deep-link` dependency |

---

## 9. Testing Plan

### 9.1 Scala Unit Tests

Create `modules/license/src/test/scala/demiurge/license/`:

- **CredentialStoreSpec** — read/write/clear credentials, config, machine-id (use temp directories)
- **MachineFingerprint** — verify deterministic output for same inputs
- **LicenseManagerSpec** — mock CloudApiClient responses:
  - Valid license → returns Valid
  - Expired → returns Expired
  - No credentials → returns NoCredentials
  - Network error + fresh cache → returns cached Valid
  - Network error + stale cache → returns NetworkError
  - MachineNotActivated → auto-activates and retries

### 9.2 CLI Integration Tests

- `demiurge login --license-key INVALID` → error message, exit code 3
- `demiurge run --task test` without login → "Not logged in" error, exit code 4
- `demiurge status` without login → works (no gate)
- `demiurge config set anthropic-api-key sk-test` → success
- `demiurge config list` → shows masked keys

### 9.3 Desktop App Manual Tests

- Launch app with no credentials → shows auth screen
- Enter valid license key → redirects to dashboard
- Deep link `demiurge://auth-callback?license_key=...&plan_tier=pro` → sets credentials, shows dashboard
- Settings → Sign Out → returns to auth screen
- Settings → Manage Billing → opens browser to Stripe portal

---

## 10. Out of Scope

- Usage increment on each run (Spec 05)
- Token metering (Spec 05)
- Team management / seat assignment (future)
- Upgrade prompt UI in desktop app (Spec 05)
- The cloud backend itself (Spec 01)
- Distribution/auto-update (Spec 03)
