package demiurge.api

import java.sql.Connection

import com.sun.net.httpserver.HttpHandler
import io.circe._
import io.circe.syntax._
import io.circe.parser.{decode => jsonDecode}

import demiurge.license.CredentialStore

import RouteHelpers.sendJson

// Desktop Phase 4 — §6.2: System routes (doctor, preferences, repos).
object SystemRoutes {

  // In-memory preferences storage (backed by SQLite in future)
  private val preferences = new java.util.concurrent.ConcurrentHashMap[String, String]()

  // GET /system/doctor → run prerequisite checks
  def getDoctorHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val checks = scala.collection.mutable.ListBuffer[Json]()

    // Check: git installed
    checks += runCheck("git", "Git is installed", () => {
      val proc = new ProcessBuilder("git", "--version").start()
      val exitCode = proc.waitFor()
      exitCode == 0
    })

    // Check: node installed
    checks += runCheck("node", "Node.js is installed", () => {
      val proc = new ProcessBuilder("node", "--version").start()
      val exitCode = proc.waitFor()
      exitCode == 0
    })

    // Check: docker running
    checks += runCheck("docker", "Docker is running", () => {
      val proc = new ProcessBuilder("docker", "info").start()
      val exitCode = proc.waitFor()
      exitCode == 0
    })

    // Check: ANTHROPIC_API_KEY set (env var or ~/.demiurge/config.json)
    val apiKeySet = CredentialStore.resolveApiKey("ANTHROPIC_API_KEY", "anthropic").isDefined
    checks += Json.obj(
      "name" -> "anthropic_api_key".asJson,
      "status" -> (if (apiKeySet) "pass" else "warn").asJson,
      "message" -> (if (apiKeySet) "ANTHROPIC_API_KEY is configured" else "ANTHROPIC_API_KEY not found — set via Settings or environment variable").asJson,
    )

    // Check: SQLite accessible
    checks += runCheck("sqlite", "SQLite database accessible", () => {
      implicit val conn: Connection = connProvider()
      try {
        val stmt = conn.createStatement()
        stmt.executeQuery("SELECT 1")
        stmt.close()
        true
      } finally { conn.close() }
    })

    val result = Json.obj("checks" -> Json.arr(checks.toList: _*))
    sendJson(exchange, 200, ApiEnvelope.success(result))
  }

  // GET /system/preferences → stored user preferences
  def getPreferencesHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val prefs = Json.obj(
      "theme" -> getOrDefault("theme", "dark").asJson,
      "fontSize" -> getOrDefault("fontSize", "14").toIntOption.getOrElse(14).asJson,
      "logLineLimit" -> getOrDefault("logLineLimit", "10000").toIntOption.getOrElse(10000).asJson,
      "autoConnectOnLaunch" -> (getOrDefault("autoConnectOnLaunch", "true") == "true").asJson,
      "showSystemTrayNotifications" -> (getOrDefault("showSystemTrayNotifications", "true") == "true").asJson,
      "defaultRepoPath" -> Option(preferences.get("defaultRepoPath")).asJson,
      "defaultRunMode" -> getOrDefault("defaultRunMode", "Full").asJson,
      "defaultMaxAttempts" -> getOrDefault("defaultMaxAttempts", "5").toIntOption.getOrElse(5).asJson,
      "defaultRunTimeoutMs" -> getOrDefault("defaultRunTimeoutMs", "1800000").toLongOption.getOrElse(1800000L).asJson,
    )
    sendJson(exchange, 200, ApiEnvelope.success(prefs))
  }

  // PUT /system/preferences → update preferences
  def putPreferencesHandler(connProvider: () => Connection): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "PUT") {
      sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
      jsonDecode[Json](body) match {
        case Left(_) =>
          sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid JSON body"))
        case Right(json) =>
          // Store each field
          json.asObject.foreach { obj =>
            obj.toMap.foreach { case (key, value) =>
              val strValue = value.asString
                .orElse(value.asNumber.map(_.toString))
                .orElse(value.asBoolean.map(_.toString))
                .getOrElse(value.noSpaces)
              preferences.put(key, strValue)
            }
          }
          sendJson(exchange, 200, ApiEnvelope.success(Json.obj("status" -> "updated".asJson)))
      }
    }
  }

  // POST /system/api-keys → persist API keys to ~/.demiurge/config.json
  def postApiKeysHandler(): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "POST") {
      sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
      jsonDecode[Json](body) match {
        case Left(_) =>
          sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid JSON body"))
        case Right(json) =>
          try {
            val config = CredentialStore.loadConfig()
            val updated = config.copy(
              anthropicApiKey = json.hcursor.get[String]("anthropicApiKey").toOption.orElse(config.anthropicApiKey),
              openaiApiKey = json.hcursor.get[String]("openaiApiKey").toOption.orElse(config.openaiApiKey),
            )
            CredentialStore.saveConfig(updated)
            sendJson(exchange, 200, ApiEnvelope.success(Json.obj("status" -> "saved".asJson)))
          } catch {
            case e: Exception =>
              sendJson(exchange, 500, ApiEnvelope.error(500, s"Failed to save API key: ${e.getMessage}"))
          }
      }
    }
  }

  // GET /system/repos → list of known repo paths from task_runs table
  def getReposHandler(connProvider: () => Connection): HttpHandler = exchange => {
    implicit val conn: Connection = connProvider()
    try {
      val stmt = conn.createStatement()
      val rs = stmt.executeQuery(
        "SELECT DISTINCT repo_path FROM task_runs ORDER BY created_at DESC LIMIT 20"
      )
      val repos = scala.collection.mutable.ListBuffer[String]()
      while (rs.next()) {
        repos += rs.getString("repo_path")
      }
      rs.close()
      stmt.close()
      sendJson(exchange, 200, ApiEnvelope.success(Json.arr(repos.map(_.asJson).toList: _*)))
    } catch {
      case e: Exception =>
        sendJson(exchange, 200, ApiEnvelope.success(Json.arr()))
    } finally { conn.close() }
  }

  private def runCheck(name: String, description: String, check: () => Boolean): Json = {
    try {
      val passed = check()
      Json.obj(
        "name" -> name.asJson,
        "status" -> (if (passed) "pass" else "fail").asJson,
        "message" -> (if (passed) description else s"$description — FAILED").asJson,
      )
    } catch {
      case e: Exception =>
        Json.obj(
          "name" -> name.asJson,
          "status" -> "fail".asJson,
          "message" -> s"$description — error: ${e.getMessage}".asJson,
        )
    }
  }

  private def getOrDefault(key: String, default: String): String =
    Option(preferences.get(key)).getOrElse(default)
}
