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
        System.out.println(s"\u2713 Set $k")
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
