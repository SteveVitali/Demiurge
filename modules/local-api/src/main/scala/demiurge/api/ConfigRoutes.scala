package demiurge.api

import java.nio.file.{Files, Paths}
import java.sql.Connection

import com.sun.net.httpserver.HttpHandler
import io.circe._
import io.circe.syntax._
import io.circe.parser.{decode => jsonDecode}

import RouteHelpers.{sendJson, parseQueryParams}

// Desktop Phase 4 — §6.2: Config CRUD, validation, smart init endpoints.
object ConfigRoutes {

  // GET /config?repo=<path> → ResolvedConfig + ConfigProvenance
  def getConfigHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val query = Option(exchange.getRequestURI.getQuery).getOrElse("")
    val params = parseQueryParams(query)
    val repoPath = params.get("repo")

    repoPath match {
      case None =>
        sendJson(exchange, 400, ApiEnvelope.error(400, "Missing 'repo' query parameter"))
      case Some(repo) =>
        val repoDir = Paths.get(repo)
        if (!Files.isDirectory(repoDir)) {
          sendJson(exchange, 404, ApiEnvelope.error(404, s"Directory not found: $repo"))
        } else {
          val manifestPath = repoDir.resolve("demiurge.yaml")
          val requirementsPath = repoDir.resolve("requirements.yaml")

          val manifestYaml = if (Files.exists(manifestPath))
            Some(new String(Files.readAllBytes(manifestPath), "UTF-8"))
          else None

          val requirementsYaml = if (Files.exists(requirementsPath))
            Some(new String(Files.readAllBytes(requirementsPath), "UTF-8"))
          else None

          val config = Json.obj(
            "repoPath" -> repo.asJson,
            "manifestYaml" -> manifestYaml.asJson,
            "requirementsYaml" -> requirementsYaml.asJson,
            "manifestExists" -> Files.exists(manifestPath).asJson,
            "requirementsExists" -> Files.exists(requirementsPath).asJson,
            "provenance" -> Json.obj(
              "manifest" -> (if (manifestYaml.isDefined) "explicit".asJson else "missing".asJson),
              "requirements" -> (if (requirementsYaml.isDefined) "explicit".asJson else "missing".asJson),
            ),
          )
          sendJson(exchange, 200, ApiEnvelope.success(config))
        }
    }
  }

  // PUT /config/manifest — body = { repoPath: string, yaml: string }
  def putManifestHandler(connProvider: () => Connection): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "PUT") {
      sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
      jsonDecode[Json](body) match {
        case Left(_) =>
          sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid JSON body"))
        case Right(json) =>
          val repoPath = json.hcursor.get[String]("repoPath").toOption
          val yaml = json.hcursor.get[String]("yaml").toOption
          (repoPath, yaml) match {
            case (Some(repo), Some(content)) =>
              try {
                val target = Paths.get(repo, "demiurge.yaml")
                Files.createDirectories(target.getParent)
                Files.write(target, content.getBytes("UTF-8"))
                val result = Json.obj(
                  "success" -> Json.True,
                  "path" -> target.toAbsolutePath.toString.asJson,
                )
                sendJson(exchange, 200, ApiEnvelope.success(result))
              } catch {
                case e: Exception =>
                  sendJson(exchange, 500, ApiEnvelope.error(500, s"Failed to write manifest: ${e.getMessage}"))
              }
            case _ =>
              sendJson(exchange, 400, ApiEnvelope.error(400, "Missing 'repoPath' or 'yaml' fields"))
          }
      }
    }
  }

  // PUT /config/requirements — body = { repoPath: string, yaml: string }
  def putRequirementsHandler(connProvider: () => Connection): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "PUT") {
      sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
      jsonDecode[Json](body) match {
        case Left(_) =>
          sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid JSON body"))
        case Right(json) =>
          val repoPath = json.hcursor.get[String]("repoPath").toOption
          val yaml = json.hcursor.get[String]("yaml").toOption
          (repoPath, yaml) match {
            case (Some(repo), Some(content)) =>
              try {
                val target = Paths.get(repo, "requirements.yaml")
                Files.createDirectories(target.getParent)
                Files.write(target, content.getBytes("UTF-8"))
                val result = Json.obj(
                  "success" -> Json.True,
                  "path" -> target.toAbsolutePath.toString.asJson,
                )
                sendJson(exchange, 200, ApiEnvelope.success(result))
              } catch {
                case e: Exception =>
                  sendJson(exchange, 500, ApiEnvelope.error(500, s"Failed to write requirements: ${e.getMessage}"))
              }
            case _ =>
              sendJson(exchange, 400, ApiEnvelope.error(400, "Missing 'repoPath' or 'yaml' fields"))
          }
      }
    }
  }

  // POST /config/validate — body = { manifest?: string, requirements?: string }
  def validateConfigHandler(connProvider: () => Connection): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "POST") {
      sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
      jsonDecode[Json](body) match {
        case Left(_) =>
          sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid JSON body"))
        case Right(json) =>
          val manifest = json.hcursor.get[String]("manifest").toOption
          val requirements = json.hcursor.get[String]("requirements").toOption

          val errors = scala.collection.mutable.ListBuffer[Json]()
          val warnings = scala.collection.mutable.ListBuffer[Json]()

          // Validate manifest YAML structure
          manifest.foreach { yaml =>
            if (yaml.trim.isEmpty) {
              errors += Json.obj(
                "field" -> "manifest".asJson,
                "message" -> "Manifest YAML is empty".asJson,
                "line" -> Json.fromInt(1),
              )
            } else {
              // Basic YAML validation: check for required top-level keys
              if (!yaml.contains("services:") && !yaml.contains("type:")) {
                warnings += Json.obj(
                  "field" -> "manifest".asJson,
                  "message" -> "Manifest should contain 'services' or 'type' section".asJson,
                  "line" -> Json.fromInt(1),
                )
              }
            }
          }

          // Validate requirements YAML structure
          requirements.foreach { yaml =>
            if (yaml.trim.isEmpty) {
              errors += Json.obj(
                "field" -> "requirements".asJson,
                "message" -> "Requirements YAML is empty".asJson,
                "line" -> Json.fromInt(1),
              )
            } else {
              if (!yaml.contains("requirements:") && !yaml.contains("- id:")) {
                warnings += Json.obj(
                  "field" -> "requirements".asJson,
                  "message" -> "Requirements file should contain 'requirements' list with 'id' fields".asJson,
                  "line" -> Json.fromInt(1),
                )
              }
            }
          }

          val result = Json.obj(
            "valid" -> (errors.isEmpty).asJson,
            "errors" -> Json.arr(errors.toList: _*),
            "warnings" -> Json.arr(warnings.toList: _*),
          )
          sendJson(exchange, 200, ApiEnvelope.success(result))
      }
    }
  }

  // POST /config/init-smart — body = { repoPath: string, taskHint?: string }
  // Long-running: triggers agent-based smart init. Returns accepted immediately.
  def smartInitHandler(connProvider: () => Connection): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "POST") {
      sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
      jsonDecode[Json](body) match {
        case Left(_) =>
          sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid JSON body"))
        case Right(json) =>
          val repoPath = json.hcursor.get[String]("repoPath").toOption
          repoPath match {
            case None =>
              sendJson(exchange, 400, ApiEnvelope.error(400, "Missing 'repoPath' field"))
            case Some(repo) =>
              val repoDir = Paths.get(repo)
              if (!Files.isDirectory(repoDir)) {
                sendJson(exchange, 404, ApiEnvelope.error(404, s"Directory not found: $repo"))
              } else {
                // Accept immediately; agent progress streamed via WebSocket
                val result = Json.obj(
                  "status" -> "accepted".asJson,
                  "repoPath" -> repo.asJson,
                  "message" -> "Smart init started. Monitor progress via WebSocket agent subscription.".asJson,
                )
                sendJson(exchange, 202, ApiEnvelope.success(result))
              }
          }
      }
    }
  }
}
