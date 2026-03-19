package demiurge.api

import java.sql.Connection

import com.sun.net.httpserver.{HttpExchange, HttpHandler}
import io.circe._
import io.circe.syntax._

import demiurge.model._
import demiurge.model.JsonCodecs._
import demiurge.persistence._

// Desktop Phase 2: Failure Packet and Patches endpoints
object FailureRoutes {

  // GET /runs/{id}/attempts/{n}/failure-packet
  def getFailurePacketHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val parts = path.split("/").filter(_.nonEmpty)
    if (parts.length >= 5) {
      val runId = parts(1)
      val attemptNum = try { parts(3).toInt } catch { case _: Exception => -1 }
      if (attemptNum < 0) {
        sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid attempt number"))
      } else {
        implicit val conn: Connection = connProvider()
        try {
          FailurePacketRepo.getByRunAndAttempt(runId, attemptNum) match {
            case Some(packet) => sendJson(exchange, 200, ApiEnvelope.success(packet.asJson))
            case None         => sendJson(exchange, 404, ApiEnvelope.error(404, s"No failure packet for run $runId attempt $attemptNum"))
          }
        } finally { conn.close() }
      }
    } else {
      sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid path"))
    }
  }

  // GET /runs/{id}/attempts/{n}/patches
  def getPatchesHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val parts = path.split("/").filter(_.nonEmpty)
    if (parts.length >= 5) {
      val runId = parts(1)
      val attemptNum = try { parts(3).toInt } catch { case _: Exception => -1 }
      if (attemptNum < 0) {
        sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid attempt number"))
      } else {
        implicit val conn: Connection = connProvider()
        try {
          val patches = PatchRepo.listByRunAndAttempt(runId, attemptNum)
          val patchJsons = patches.map { p =>
            Json.obj(
              "patchId" -> Json.fromString(p.patchRecordId),
              "runId" -> Json.fromString(p.runId),
              "attemptNumber" -> Json.fromInt(p.attemptNumber),
              "repairBackend" -> Json.fromString(p.repairBackend),
              "repairSummary" -> Json.fromString(p.repairSummary),
              "totalLinesAdded" -> Json.fromInt(p.totalLinesAdded),
              "totalLinesRemoved" -> Json.fromInt(p.totalLinesRemoved),
              "requiresEnvRebuild" -> Json.fromBoolean(p.requiresEnvRebuild),
              "appliedAt" -> Json.fromString(p.appliedAt.toString),
              "filesChangedJson" -> Json.fromString(p.filesChangedJson),
            )
          }
          sendJson(exchange, 200, ApiEnvelope.success(Json.arr(patchJsons: _*)))
        } finally { conn.close() }
      }
    } else {
      sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid path"))
    }
  }

  // --- Helpers ---

  private def sendJson(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes("UTF-8")
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val os = exchange.getResponseBody
    try { os.write(bytes) } finally { os.close() }
  }
}
