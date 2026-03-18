package demiurge.api

import io.circe._
import io.circe.syntax._

// Phase 7: API response envelope — Spec §14.4
// { "ok": true, "data": ..., "error": null }
// { "ok": false, "data": null, "error": { ... } }
object ApiEnvelope {

  def success(data: Json): String =
    Json.obj(
      "ok" -> Json.True,
      "data" -> data,
      "error" -> Json.Null,
    ).noSpaces

  def error(code: Int, message: String): String =
    Json.obj(
      "ok" -> Json.False,
      "data" -> Json.Null,
      "error" -> Json.obj(
        "code" -> Json.fromInt(code),
        "message" -> Json.fromString(message),
      ),
    ).noSpaces

  def errorJson(code: Int, message: String): Json =
    Json.obj(
      "ok" -> Json.False,
      "data" -> Json.Null,
      "error" -> Json.obj(
        "code" -> Json.fromInt(code),
        "message" -> Json.fromString(message),
      ),
    )

  def successJson(data: Json): Json =
    Json.obj(
      "ok" -> Json.True,
      "data" -> data,
      "error" -> Json.Null,
    )
}
