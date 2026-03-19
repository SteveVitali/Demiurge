package demiurge.api

import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.Connection

import com.sun.net.httpserver.HttpServer

import demiurge.persistence.{Database, TaskRunRepo}

// Phase 7: Local HTTP API server — Spec §14.4
// Binds to 127.0.0.1:19440. Starts with `demiurge run`, stops when run exits.
// Localhost only, no auth.
object LocalApiServer {

  private var server: Option[HttpServer] = None

  def start(
    port: Int = 19440,
    wsPort: Int = 19441,
    dbPath: Path,
    artifactRootResolver: String => Option[Path] = _ => None,
  ): HttpServer = {
    val connProvider: () => Connection = () => Database.open(dbPath)

    val httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)

    // Spec §14.4: Required endpoints (all wrapped with CORS middleware)
    httpServer.createContext("/health", CorsMiddleware.wrap(Routes.healthHandler()))
    httpServer.createContext("/runs", CorsMiddleware.wrap(new com.sun.net.httpserver.HttpHandler {
      override def handle(exchange: com.sun.net.httpserver.HttpExchange): Unit = {
        val path = exchange.getRequestURI.getPath
        val method = exchange.getRequestMethod

        try {
          if (path == "/runs" && method == "POST") {
            Routes.postRunHandler(connProvider).handle(exchange)
          } else if (path == "/runs" && method == "GET") {
            // Desktop Phase 1: paginated run list
            Routes.listRunsHandler(connProvider).handle(exchange)
          } else if (path == "/runs/active" && method == "GET") {
            // Desktop Phase 1: active run lookup
            Routes.getActiveRunHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/plan")) {
            Routes.getRunPlanHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/attempts/\\d+/verdicts")) {
            Routes.getVerdictsHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/attempts")) {
            Routes.getAttemptsHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/artifacts/[^/]+/content")) {
            Routes.getArtifactContentHandler(connProvider, artifactRootResolver).handle(exchange)
          } else if (path.matches("/runs/[^/]+/artifacts")) {
            Routes.getArtifactsHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/inspection")) {
            // Desktop Phase 2: Inspection report
            InspectionRoutes.getInspectionHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/requirement-graph")) {
            // Desktop Phase 2: Requirement graph
            InspectionRoutes.getRequirementGraphHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/feature-plan")) {
            // Desktop Phase 2: Feature plan
            InspectionRoutes.getFeaturePlanHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/attempts/\\d+/failure-packet")) {
            // Desktop Phase 2: Failure packet
            FailureRoutes.getFailurePacketHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/attempts/\\d+/patches")) {
            // Desktop Phase 2: Patches
            FailureRoutes.getPatchesHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/environment") && method == "GET") {
            // Desktop Phase 3: Environment snapshot
            EnvironmentRoutes.getEnvironmentHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/services/[^/]+/restart") && method == "POST") {
            // Desktop Phase 3: Service restart
            EnvironmentRoutes.restartServiceHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/services") && method == "GET") {
            // Desktop Phase 3: Service list
            EnvironmentRoutes.getServicesHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/agent/transcript") && method == "GET") {
            // Desktop Phase 3: Agent transcript
            AgentRoutes.getTranscriptHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/agent/cost") && method == "GET") {
            // Desktop Phase 3: Agent cost
            AgentRoutes.getCostHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/resume") && method == "POST") {
            Routes.postResumeHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/cancel") && method == "POST") {
            Routes.postCancelHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+/events")) {
            Routes.eventsHandler(connProvider).handle(exchange)
          } else if (path.matches("/runs/[^/]+")) {
            Routes.getRunHandler(connProvider).handle(exchange)
          } else {
            val body = ApiEnvelope.error(404, "Not found")
            val bytes = body.getBytes("UTF-8")
            exchange.getResponseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(404, bytes.length.toLong)
            val os = exchange.getResponseBody
            try { os.write(bytes) } finally { os.close() }
          }
        } catch {
          case e: Exception =>
            try {
              val body = ApiEnvelope.error(500, e.getMessage)
              val bytes = body.getBytes("UTF-8")
              exchange.getResponseHeaders.set("Content-Type", "application/json")
              exchange.sendResponseHeaders(500, bytes.length.toLong)
              val os = exchange.getResponseBody
              try { os.write(bytes) } finally { os.close() }
            } catch { case _: Exception => }
        }
      }
    }))

    // Desktop Phase 4: Config routes
    httpServer.createContext("/config", CorsMiddleware.wrap(new com.sun.net.httpserver.HttpHandler {
      override def handle(exchange: com.sun.net.httpserver.HttpExchange): Unit = {
        val path = exchange.getRequestURI.getPath
        val method = exchange.getRequestMethod
        try {
          if (path == "/config" && method == "GET") {
            ConfigRoutes.getConfigHandler(connProvider).handle(exchange)
          } else if (path == "/config/manifest" && method == "PUT") {
            ConfigRoutes.putManifestHandler(connProvider).handle(exchange)
          } else if (path == "/config/requirements" && method == "PUT") {
            ConfigRoutes.putRequirementsHandler(connProvider).handle(exchange)
          } else if (path == "/config/validate" && method == "POST") {
            ConfigRoutes.validateConfigHandler(connProvider).handle(exchange)
          } else if (path == "/config/init-smart" && method == "POST") {
            ConfigRoutes.smartInitHandler(connProvider).handle(exchange)
          } else {
            RouteHelpers.sendJson(exchange, 404, ApiEnvelope.error(404, "Not found"))
          }
        } catch {
          case e: Exception =>
            try { RouteHelpers.sendJson(exchange, 500, ApiEnvelope.error(500, e.getMessage)) }
            catch { case _: Exception => }
        }
      }
    }))

    // Desktop Phase 4: System routes
    httpServer.createContext("/system", CorsMiddleware.wrap(new com.sun.net.httpserver.HttpHandler {
      override def handle(exchange: com.sun.net.httpserver.HttpExchange): Unit = {
        val path = exchange.getRequestURI.getPath
        val method = exchange.getRequestMethod
        try {
          if (path == "/system/doctor" && method == "GET") {
            SystemRoutes.getDoctorHandler(connProvider).handle(exchange)
          } else if (path == "/system/preferences" && method == "GET") {
            SystemRoutes.getPreferencesHandler(connProvider).handle(exchange)
          } else if (path == "/system/preferences" && method == "PUT") {
            SystemRoutes.putPreferencesHandler(connProvider).handle(exchange)
          } else if (path == "/system/repos" && method == "GET") {
            SystemRoutes.getReposHandler(connProvider).handle(exchange)
          } else {
            RouteHelpers.sendJson(exchange, 404, ApiEnvelope.error(404, "Not found"))
          }
        } catch {
          case e: Exception =>
            try { RouteHelpers.sendJson(exchange, 500, ApiEnvelope.error(500, e.getMessage)) }
            catch { case _: Exception => }
        }
      }
    }))

    // Spec 05 §7.5: Usage endpoint for desktop app
    httpServer.createContext("/usage", CorsMiddleware.wrap(UsageRoutes.getUsageHandler()))

    httpServer.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4))
    httpServer.start()
    server = Some(httpServer)

    // Desktop Phase 3: Start WebSocket server alongside HTTP
    try {
      WebSocketServer.start(wsPort)
    } catch {
      case e: Exception =>
        System.err.println(s"[local-api] Failed to start WebSocket server on :$wsPort: ${e.getMessage}")
    }

    httpServer
  }

  def stop(): Unit = {
    server.foreach { s =>
      s.stop(1) // 1 second grace period
    }
    server = None
    // Desktop Phase 3: Stop WebSocket server
    WebSocketServer.stop()
  }

  def isRunning: Boolean = server.isDefined
}
