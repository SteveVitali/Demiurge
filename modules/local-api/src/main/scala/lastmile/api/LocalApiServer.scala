package lastmile.api

import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.Connection

import com.sun.net.httpserver.HttpServer

import lastmile.persistence.{Database, TaskRunRepo}

// Phase 7: Local HTTP API server — Spec §14.4
// Binds to 127.0.0.1:19440. Starts with `lastmile run`, stops when run exits.
// Localhost only, no auth.
object LocalApiServer {

  private var server: Option[HttpServer] = None

  def start(
    port: Int = 19440,
    dbPath: Path,
    artifactRootResolver: String => Option[Path] = _ => None,
  ): HttpServer = {
    val connProvider: () => Connection = () => Database.open(dbPath)

    val httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)

    // Spec §14.4: Required endpoints
    httpServer.createContext("/health", Routes.healthHandler())
    httpServer.createContext("/runs", new com.sun.net.httpserver.HttpHandler {
      override def handle(exchange: com.sun.net.httpserver.HttpExchange): Unit = {
        val path = exchange.getRequestURI.getPath
        val method = exchange.getRequestMethod

        try {
          if (path == "/runs" && method == "POST") {
            Routes.postRunHandler(connProvider).handle(exchange)
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
    })

    httpServer.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4))
    httpServer.start()
    server = Some(httpServer)
    httpServer
  }

  def stop(): Unit = {
    server.foreach { s =>
      s.stop(1) // 1 second grace period
    }
    server = None
  }

  def isRunning: Boolean = server.isDefined
}
