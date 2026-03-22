package demiurge.api

import java.io.OutputStreamWriter
import java.sql.Connection
import java.nio.file.{Files, Path}

import com.sun.net.httpserver.{HttpExchange, HttpHandler}
import io.circe._
import io.circe.syntax._
import io.circe.parser.{decode => jsonDecode}

import demiurge.model._
import demiurge.model.JsonCodecs._
import demiurge.persistence._

// Phase 7: HTTP route handlers for local API — Spec §14.4
// All endpoints return JSON envelopes. Localhost only, no auth.
object Routes {

  def healthHandler(): HttpHandler = exchange => {
    val body = ApiEnvelope.success(Json.obj("status" -> Json.fromString("ok")))
    sendJson(exchange, 200, body)
  }

  def getRunHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val runId = extractPathParam(exchange.getRequestURI.getPath, "/runs/")
    if (runId.isEmpty || runId.contains("/")) {
      sendJson(exchange, 400, ApiEnvelope.error(400, "Missing or invalid run ID"))
    } else {
      implicit val conn: Connection = connProvider()
      try {
        TaskRunRepo.getById(runId) match {
          case Some(run) => sendJson(exchange, 200, ApiEnvelope.success(run.asJson))
          case None      => sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
        }
      } finally { conn.close() }
    }
  }

  def getRunPlanHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val runId = extractRunIdFromPath(path)
    implicit val conn: Connection = connProvider()
    try {
      TaskRunRepo.getById(runId) match {
        case Some(run) =>
          val artifacts = ArtifactRecordRepo.listByRunAndType(runId, ArtifactType.Plan)
          sendJson(exchange, 200, ApiEnvelope.success(Json.arr(artifacts.map(_.asJson): _*)))
        case None => sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
      }
    } finally { conn.close() }
  }

  def getAttemptsHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val runId = extractRunIdFromPath(path)
    implicit val conn: Connection = connProvider()
    try {
      TaskRunRepo.getById(runId) match {
        case Some(_) =>
          val attempts = AttemptRepo.listByRunId(runId)
          sendJson(exchange, 200, ApiEnvelope.success(Json.arr(attempts.map(_.asJson): _*)))
        case None => sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
      }
    } finally { conn.close() }
  }

  def getVerdictsHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    // /runs/{id}/attempts/{num}/verdicts
    val parts = path.split("/").filter(_.nonEmpty)
    if (parts.length >= 5) {
      val runId = parts(1)
      val attemptNum = try { parts(3).toInt } catch { case _: Exception => -1 }
      if (attemptNum < 0) {
        sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid attempt number"))
      } else {
        implicit val conn: Connection = connProvider()
        try {
          val verdicts = VerdictRepo.listByRunAndAttempt(runId, attemptNum)
          sendJson(exchange, 200, ApiEnvelope.success(Json.arr(verdicts.map(_.asJson): _*)))
        } finally { conn.close() }
      }
    } else {
      sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid path"))
    }
  }

  def getArtifactsHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val runId = extractRunIdFromPath(path)
    val query = Option(exchange.getRequestURI.getQuery).getOrElse("")
    val params = parseQueryParams(query)
    val offset = params.get("offset").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(0)
    val limit = params.get("limit").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(50)
    val typeFilter = params.get("type").flatMap(t => ArtifactType.values.find(_.toString.equalsIgnoreCase(t)))
    val attemptFilter = params.get("attempt").flatMap(s => scala.util.Try(s.toInt).toOption)

    implicit val conn: Connection = connProvider()
    try {
      TaskRunRepo.getById(runId) match {
        case Some(_) =>
          val artifacts = ArtifactRecordRepo.listByRunPaginated(runId, offset, limit, typeFilter, attemptFilter)
          val total = ArtifactRecordRepo.countByRunId(runId)
          sendJson(exchange, 200, ApiEnvelope.success(
            ApiModels.paginatedJson(Json.arr(artifacts.map(_.asJson): _*), total, offset, limit)))
        case None => sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
      }
    } finally { conn.close() }
  }

  def getArtifactContentHandler(connProvider: () => Connection, artifactRootResolver: String => Option[Path]): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    // /runs/{id}/artifacts/{artifactId}/content
    val parts = path.split("/").filter(_.nonEmpty)
    if (parts.length >= 5) {
      val runId = parts(1)
      val artifactId = parts(3)
      implicit val conn: Connection = connProvider()
      try {
        ArtifactRecordRepo.getById(artifactId) match {
          case Some(record) =>
            artifactRootResolver(runId) match {
              case Some(root) =>
                val filePath = root.resolve(record.relativePath)
                if (Files.exists(filePath)) {
                  val bytes = Files.readAllBytes(filePath)
                  exchange.getResponseHeaders.set("Content-Type", record.contentType)
                  exchange.sendResponseHeaders(200, bytes.length.toLong)
                  val os = exchange.getResponseBody
                  try { os.write(bytes) } finally { os.close() }
                } else {
                  sendJson(exchange, 404, ApiEnvelope.error(404, "Artifact file not found on disk"))
                }
              case None =>
                sendJson(exchange, 404, ApiEnvelope.error(404, s"Cannot resolve artifact root for run: $runId"))
            }
          case None => sendJson(exchange, 404, ApiEnvelope.error(404, s"Artifact not found: $artifactId"))
        }
      } finally { conn.close() }
    } else {
      sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid path"))
    }
  }

  private val resumableStatuses: Set[RunStatus] = Set(
    RunStatus.Interrupted, RunStatus.ReadyToVerify,
    RunStatus.AnalyzingFailure, RunStatus.PlanningRepair,
  )

  def postResumeHandler(connProvider: () => Connection): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "POST") {
      sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val runId = extractRunIdFromPath(exchange.getRequestURI.getPath.replaceAll("/resume$", ""))
      implicit val conn: Connection = connProvider()
      try {
        TaskRunRepo.getById(runId) match {
          case Some(run) if resumableStatuses.contains(run.status) =>
            sendJson(exchange, 200, ApiEnvelope.success(Json.obj("runId" -> Json.fromString(runId), "status" -> Json.fromString("resuming"))))
          case Some(run) =>
            sendJson(exchange, 409, ApiEnvelope.error(409, s"Run $runId is not resumable (status: ${run.status})"))
          case None => sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
        }
      } finally { conn.close() }
    }
  }

  private val terminalStatuses: Set[RunStatus] = Set(
    RunStatus.Succeeded, RunStatus.Exhausted,
    RunStatus.Cancelled, RunStatus.Interrupted,
  )

  def postCancelHandler(connProvider: () => Connection): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "POST") {
      sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val runId = extractRunIdFromPath(exchange.getRequestURI.getPath.replaceAll("/cancel$", ""))
      implicit val conn: Connection = connProvider()
      try {
        TaskRunRepo.getById(runId) match {
          case Some(run) if terminalStatuses.contains(run.status) =>
            sendJson(exchange, 409, ApiEnvelope.error(409, s"Run $runId is already in terminal state: ${run.status}"))
          case Some(run) =>
            TaskRunRepo.updateStatus(runId, RunStatus.Cancelled, Some(java.time.Instant.now()))
            sendJson(exchange, 200, ApiEnvelope.success(Json.obj("runId" -> Json.fromString(runId), "status" -> Json.fromString("cancelled"))))
          case None => sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
        }
      } finally { conn.close() }
    }
  }

  def eventsHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val runId = extractRunIdFromPath(path.replaceAll("/events$", ""))

    exchange.getResponseHeaders.set("Content-Type", "text/event-stream")
    exchange.getResponseHeaders.set("Cache-Control", "no-cache")
    exchange.getResponseHeaders.set("Connection", "keep-alive")
    exchange.sendResponseHeaders(200, 0) // chunked

    val os = exchange.getResponseBody
    val writer = new OutputStreamWriter(os, "UTF-8")

    @volatile var closed = false

    val listener: EventStream.EventListener = (sseData: String) => {
      if (!closed) {
        try {
          writer.write(sseData)
          writer.flush()
        } catch {
          case _: Exception => closed = true
        }
      }
    }

    EventStream.subscribe(runId, listener)

    try {
      // Keep connection open until run ends or client disconnects
      while (!closed && !EventStream.isRunEnded(runId)) {
        Thread.sleep(500)
      }
      // Send final event
      if (!closed) {
        try {
          writer.write("event: done\ndata: {}\n\n")
          writer.flush()
        } catch { case _: Exception => }
      }
    } finally {
      EventStream.unsubscribe(runId, listener)
      try { writer.close() } catch { case _: Exception => }
      try { os.close() } catch { case _: Exception => }
    }
  }

  // Phase 10: Decoupled run-starter callback for POST /runs
  // CLI wires this to create a real TaskRun and start orchestration in a background thread.
  @volatile private var runStarter: Option[(String, Connection) => Option[String]] = None

  def setRunStarter(starter: (String, Connection) => Option[String]): Unit = {
    runStarter = Some(starter)
  }

  def clearRunStarter(): Unit = {
    runStarter = None
  }

  def postRunHandler(connProvider: () => Connection): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "POST") {
      sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
      jsonDecode[Json](body) match {
        case Left(_) =>
          sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid JSON body"))
        case Right(json) =>
          val task = json.hcursor.get[String]("task").toOption
          task match {
            case None =>
              sendJson(exchange, 400, ApiEnvelope.error(400, "Missing 'task' field"))
            case Some(t) =>
              runStarter match {
                case Some(starter) =>
                  implicit val conn: Connection = connProvider()
                  try {
                    starter(t, conn) match {
                      case Some(runId) =>
                        sendJson(exchange, 200, ApiEnvelope.success(Json.obj(
                          "runId" -> Json.fromString(runId),
                          "task" -> Json.fromString(t),
                          "status" -> Json.fromString("started"),
                        )))
                      case None =>
                        sendJson(exchange, 500, ApiEnvelope.error(500, "Failed to start run"))
                    }
                  } finally { conn.close() }
                case None =>
                  sendJson(exchange, 200, ApiEnvelope.success(Json.obj(
                    "task" -> Json.fromString(t),
                    "status" -> Json.fromString("accepted"),
                  )))
              }
          }
      }
    }
  }

  // Desktop Phase 1: GET /runs — paginated, sorted list of all runs
  def listRunsHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val query = Option(exchange.getRequestURI.getQuery).getOrElse("")
    val params = parseQueryParams(query)
    val offset = params.get("offset").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(0)
    val limit = params.get("limit").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(20)
    val sort = params.getOrElse("sort", "created_at")
    val order = params.getOrElse("order", "desc")
    val statusFilter = params.get("status").flatMap(s => RunStatus.values.find(_.toString.equalsIgnoreCase(s)))

    implicit val conn: Connection = connProvider()
    try {
      val (runs, total) = TaskRunRepo.listPaginated(offset, limit, statusFilter, sort, order)
      sendJson(exchange, 200, ApiEnvelope.success(
        ApiModels.paginatedJson(Json.arr(runs.map(_.asJson): _*), total, offset, limit)))
    } finally { conn.close() }
  }

  // Desktop Phase 1: GET /runs/active — find the currently active run
  def getActiveRunHandler(connProvider: () => Connection): HttpHandler = exchange => {
    implicit val conn: Connection = connProvider()
    try {
      TaskRunRepo.getActiveRun() match {
        case Some(run) => sendJson(exchange, 200, ApiEnvelope.success(run.asJson))
        case None      => sendJson(exchange, 404, ApiEnvelope.error(404, "No active run"))
      }
    } finally { conn.close() }
  }

  // --- Helpers (delegated to RouteHelpers) ---

  private def sendJson(exchange: HttpExchange, status: Int, body: String): Unit =
    RouteHelpers.sendJson(exchange, status, body)

  private def extractPathParam(path: String, prefix: String): String =
    RouteHelpers.extractPathParam(path, prefix)

  private def extractRunIdFromPath(path: String): String =
    RouteHelpers.extractRunIdFromPath(path)

  private def parseQueryParams(query: String): Map[String, String] =
    RouteHelpers.parseQueryParams(query)
}
