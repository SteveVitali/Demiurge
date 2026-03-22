package demiurge.api

import java.sql.Connection
import com.sun.net.httpserver.{HttpExchange, HttpHandler}
import io.circe._
import io.circe.syntax._

import demiurge.model._
import demiurge.persistence._
import demiurge.runtime.ServiceProcessManager

// Desktop Phase 3 — §6.2: Environment & service endpoints.
// GET /runs/{id}/environment → RuntimeSnapshot
// GET /runs/{id}/services → list of ServiceSnapshot
// POST /runs/{id}/services/{serviceId}/restart → trigger restart
object EnvironmentRoutes {

  def getEnvironmentHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val runId = RouteHelpers.extractRunIdFromPath(path.replaceAll("/environment$", ""))
    implicit val conn: Connection = connProvider()
    try {
      TaskRunRepo.getById(runId) match {
        case Some(run) =>
          val services = buildServiceSnapshots(run)
          val env = Json.obj(
            "runId"    -> run.runId.asJson,
            "status"   -> run.status.toString.asJson,
            "runMode"  -> run.runMode.toString.asJson,
            "services" -> services.asJson,
          )
          RouteHelpers.sendJson(exchange, 200, ApiEnvelope.success(env))
        case None =>
          RouteHelpers.sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
      }
    } finally { conn.close() }
  }

  def getServicesHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val runId = RouteHelpers.extractRunIdFromPath(path.replaceAll("/services$", ""))
    implicit val conn: Connection = connProvider()
    try {
      TaskRunRepo.getById(runId) match {
        case Some(run) =>
          val services = buildServiceSnapshots(run)
          RouteHelpers.sendJson(exchange, 200, ApiEnvelope.success(Json.arr(services: _*)))
        case None =>
          RouteHelpers.sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
      }
    } finally { conn.close() }
  }

  def restartServiceHandler(connProvider: () => Connection): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "POST") {
      RouteHelpers.sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      val path = exchange.getRequestURI.getPath
      // /runs/{id}/services/{serviceId}/restart
      val parts = path.split("/").filter(_.nonEmpty)
      if (parts.length >= 5) {
        val runId = parts(1)
        val serviceId = parts(3)
        implicit val conn: Connection = connProvider()
        try {
          TaskRunRepo.getById(runId) match {
            case Some(_) =>
              ServiceProcessManager.getService(serviceId) match {
                case None =>
                  RouteHelpers.sendJson(exchange, 404,
                    ApiEnvelope.error(404, s"Service not found or not running: $serviceId"))
                case Some(managed) =>
                  // Best-effort process kill. Full restart (stop+start+readiness)
                  // requires RuntimePlan context only available in AgentToolRpcHandlers.
                  managed.process.foreach { proc =>
                    if (proc.isAlive) proc.destroyForcibly()
                  }
                  val result = Json.obj(
                    "serviceId" -> serviceId.asJson,
                    "status"    -> "kill_sent".asJson,
                  )
                  RouteHelpers.sendJson(exchange, 200, ApiEnvelope.success(result))
              }
            case None =>
              RouteHelpers.sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
          }
        } finally { conn.close() }
      } else {
        RouteHelpers.sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid path"))
      }
    }
  }

  private def buildServiceSnapshots(run: TaskRun): List[Json] = {
    val managedServices = ServiceProcessManager.allServices
    managedServices.map { case (serviceId, managed) =>
      val processAlive = managed.process.exists(_.isAlive) || managed.containerId.isDefined
      val status = if (!processAlive) "Stopped" else "Running"
      val logLineCount = ServiceProcessManager.getLogLines(serviceId).size
      Json.obj(
        "serviceId"    -> serviceId.asJson,
        "status"       -> status.asJson,
        "pid"          -> managed.pid.asJson,
        "containerId"  -> managed.containerId.asJson,
        "logLineCount" -> logLineCount.asJson,
        "startupMode"  -> managed.startupMode.toString.asJson,
      )
    }.toList
  }
}
