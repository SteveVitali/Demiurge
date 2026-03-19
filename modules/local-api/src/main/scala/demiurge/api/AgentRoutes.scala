package demiurge.api

import java.sql.Connection
import com.sun.net.httpserver.{HttpExchange, HttpHandler}
import io.circe._
import io.circe.syntax._

import demiurge.model._
import demiurge.persistence._

// Desktop Phase 3 — §6.2: Agent transcript and cost endpoints.
// GET /runs/{id}/agent/transcript → stored transcript
// GET /runs/{id}/agent/cost → UsageRecord
object AgentRoutes {

  // In-memory transcript storage for active agent sessions.
  // Populated by AgentToolRpcHandlers WS broadcast hook.
  private val MAX_TRANSCRIPT_MESSAGES = 5000
  private val transcripts = new java.util.concurrent.ConcurrentHashMap[String, java.util.List[Json]]()
  private val costRecords = new java.util.concurrent.ConcurrentHashMap[String, Json]()

  def appendTranscriptMessage(runId: String, message: Json): Unit = {
    val list = transcripts.computeIfAbsent(runId,
      _ => java.util.Collections.synchronizedList(new java.util.ArrayList[Json]()))
    list.add(message)
    // Cap to prevent unbounded memory growth during long sessions
    while (list.size() > MAX_TRANSCRIPT_MESSAGES) {
      list.synchronized { if (list.size() > MAX_TRANSCRIPT_MESSAGES) list.remove(0) }
    }
  }

  def updateCost(runId: String, cost: Json): Unit = {
    costRecords.put(runId, cost)
  }

  def clearTranscript(runId: String): Unit = {
    transcripts.remove(runId)
    costRecords.remove(runId)
  }

  def getTranscriptHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    // /runs/{id}/agent/transcript
    val parts = path.split("/").filter(_.nonEmpty)
    if (parts.length >= 4) {
      val runId = parts(1)
      implicit val conn: Connection = connProvider()
      try {
        TaskRunRepo.getById(runId) match {
          case Some(_) =>
            val messages = Option(transcripts.get(runId))
              .map { list =>
                list.synchronized {
                  val arr = new Array[Json](list.size())
                  list.toArray(arr)
                  arr.toList
                }
              }
              .getOrElse(Nil)
            RouteHelpers.sendJson(exchange, 200, ApiEnvelope.success(Json.arr(messages: _*)))
          case None =>
            RouteHelpers.sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
        }
      } finally { conn.close() }
    } else {
      RouteHelpers.sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid path"))
    }
  }

  def getCostHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    // /runs/{id}/agent/cost
    val parts = path.split("/").filter(_.nonEmpty)
    if (parts.length >= 4) {
      val runId = parts(1)
      implicit val conn: Connection = connProvider()
      try {
        TaskRunRepo.getById(runId) match {
          case Some(_) =>
            val cost = Option(costRecords.get(runId)).getOrElse(
              Json.obj(
                "inputTokens"  -> 0.asJson,
                "outputTokens" -> 0.asJson,
                "costUsd"      -> 0.0.asJson,
                "numTurns"     -> 0.asJson,
                "durationMs"   -> 0.asJson,
              )
            )
            RouteHelpers.sendJson(exchange, 200, ApiEnvelope.success(cost))
          case None =>
            RouteHelpers.sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
        }
      } finally { conn.close() }
    } else {
      RouteHelpers.sendJson(exchange, 400, ApiEnvelope.error(400, "Invalid path"))
    }
  }
}
