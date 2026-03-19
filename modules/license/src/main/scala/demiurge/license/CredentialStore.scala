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

  /** Resolve an API key from environment variable first, then user config. */
  def resolveApiKey(envVar: String, configKey: String): Option[String] = {
    Option(System.getenv(envVar))
      .filter(_.nonEmpty)
      .orElse {
        val config = loadConfig()
        configKey match {
          case "anthropic" => config.anthropicApiKey
          case "openai"    => config.openaiApiKey
          case _           => None
        }
      }
  }
}
