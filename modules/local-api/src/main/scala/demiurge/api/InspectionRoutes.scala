package demiurge.api

import java.sql.Connection

import com.sun.net.httpserver.HttpHandler
import io.circe._
import io.circe.syntax._

import RouteHelpers.{sendJson, extractRunIdFromPath}

import demiurge.model._
import demiurge.model.JsonCodecs._
import demiurge.persistence._

// Desktop Phase 2: Inspection, Requirement Graph, and Feature Plan endpoints
object InspectionRoutes {

  // GET /runs/{id}/inspection
  def getInspectionHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val runId = extractRunIdFromPath(path)
    implicit val conn: Connection = connProvider()
    try {
      TaskRunRepo.getById(runId) match {
        case Some(_) =>
          RepoInspectionReportRepo.getByRunId(runId) match {
            case Some(report) => sendJson(exchange, 200, ApiEnvelope.success(report.asJson))
            case None         => sendJson(exchange, 404, ApiEnvelope.error(404, s"No inspection report for run: $runId"))
          }
        case None => sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
      }
    } finally { conn.close() }
  }

  // GET /runs/{id}/requirement-graph
  def getRequirementGraphHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val runId = extractRunIdFromPath(path)
    implicit val conn: Connection = connProvider()
    try {
      TaskRunRepo.getById(runId) match {
        case Some(_) =>
          // Build requirement graph from verdicts: nodes = unique requirements, edges = empty
          // A full graph with dependency edges would require requirement compilation data;
          // for now we derive nodes from verdict data.
          val verdicts = VerdictRepo.listByRunId(runId)
          val nodeMap = scala.collection.mutable.LinkedHashMap[String, Json]()
          for (v <- verdicts) {
            if (!nodeMap.contains(v.requirementId)) {
              nodeMap(v.requirementId) = Json.obj(
                "requirementId" -> Json.fromString(v.requirementId),
                "description" -> Json.fromString(v.requirementId),
                "priority" -> Json.fromString("Required"),
                "category" -> Json.fromString("verification"),
                "verdictStatus" -> (v.status match {
                  case VerdictStatus.Pass => Json.fromString("Pass")
                  case VerdictStatus.Fail => Json.fromString("Fail")
                  case VerdictStatus.Inconclusive => Json.fromString("Inconclusive")
                  case VerdictStatus.Blocked => Json.fromString("Blocked")
                  case VerdictStatus.Timeout => Json.fromString("Timeout")
                  case VerdictStatus.Flake => Json.fromString("Flake")
                }),
              )
            }
          }
          val graph = Json.obj(
            "nodes" -> Json.arr(nodeMap.values.toSeq: _*),
            "edges" -> Json.arr(),
          )
          sendJson(exchange, 200, ApiEnvelope.success(graph))
        case None => sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
      }
    } finally { conn.close() }
  }

  // GET /runs/{id}/feature-plan
  def getFeaturePlanHandler(connProvider: () => Connection): HttpHandler = exchange => {
    val path = exchange.getRequestURI.getPath
    val runId = extractRunIdFromPath(path)
    implicit val conn: Connection = connProvider()
    try {
      TaskRunRepo.getById(runId) match {
        case Some(_) =>
          FeaturePlanRepo.getByRunId(runId) match {
            case Some(plan) => sendJson(exchange, 200, ApiEnvelope.success(plan.asJson))
            case None       => sendJson(exchange, 404, ApiEnvelope.error(404, s"No feature plan for run: $runId"))
          }
        case None => sendJson(exchange, 404, ApiEnvelope.error(404, s"Run not found: $runId"))
      }
    } finally { conn.close() }
  }
}
