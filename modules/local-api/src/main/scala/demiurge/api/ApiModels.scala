package demiurge.api

import io.circe._
import io.circe.syntax._

// Phase 7: API request/response models — Spec §14.4
object ApiModels {

  // POST /runs request body
  case class CreateRunRequest(
    task: String,
    maxAttempts: Option[Int]       = None,
    mode: Option[String]           = None,
    changedFiles: Option[List[String]] = None,
    gitRef: Option[String]         = None,
  )

  // Pagination response wrapper
  case class PaginatedResponse(
    items: Json,
    total: Int,
    offset: Int,
    limit: Int,
  )

  def paginatedJson(items: Json, total: Int, offset: Int, limit: Int): Json =
    Json.obj(
      "items" -> items,
      "total" -> Json.fromInt(total),
      "offset" -> Json.fromInt(offset),
      "limit" -> Json.fromInt(limit),
    )
}
