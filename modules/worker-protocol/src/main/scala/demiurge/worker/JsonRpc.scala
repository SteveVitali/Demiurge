package demiurge.worker

import io.circe._
import io.circe.syntax._
import io.circe.parser._

// Spec §10.1: JSON-RPC 2.0 message types for Scala side of worker protocol

case class JsonRpcRequest(
  id:     Long,
  method: String,
  params: Json = Json.obj(),
) {
  def toJson: String = Json.obj(
    "jsonrpc" -> "2.0".asJson,
    "id"      -> id.asJson,
    "method"  -> method.asJson,
    "params"  -> params,
  ).noSpaces
}

case class JsonRpcResponse(
  id:     Option[Long],
  result: Option[Json],
  error:  Option[JsonRpcError],
) {
  def isSuccess: Boolean = error.isEmpty
  def isError: Boolean = error.isDefined
}

case class JsonRpcError(
  code:    Int,
  message: String,
  data:    Option[Json] = None,
)

case class JsonRpcNotification(
  method: String,
  params: Json = Json.obj(),
)

object JsonRpc {

  // Spec §10.1: Standard JSON-RPC error codes
  val PARSE_ERROR: Int       = -32700
  val INVALID_REQUEST: Int   = -32600
  val METHOD_NOT_FOUND: Int  = -32601
  val INVALID_PARAMS: Int    = -32602
  val INTERNAL_ERROR: Int    = -32603
  val TASK_CANCELLED: Int    = -32000
  val BROWSER_ERROR: Int     = -32001
  val ARTIFACT_ERROR: Int    = -32002
  val NOT_INITIALIZED: Int   = -32003

  def parseResponse(line: String): Either[String, Either[JsonRpcNotification, JsonRpcResponse]] = {
    parse(line) match {
      case Left(err) => Left(s"JSON parse error: ${err.getMessage}")
      case Right(json) =>
        val cursor = json.hcursor
        val hasId = cursor.downField("id").focus.exists(!_.isNull)
        val hasMethod = cursor.downField("method").focus.isDefined

        if (!hasId && hasMethod) {
          // It's a notification
          val method = cursor.downField("method").as[String].getOrElse("")
          val params = cursor.downField("params").focus.getOrElse(Json.obj())
          Right(Left(JsonRpcNotification(method, params)))
        } else {
          // It's a response
          val id = cursor.downField("id").as[Long].toOption
          val result = cursor.downField("result").focus
          val error = cursor.downField("error").focus.flatMap { errJson =>
            val ec = errJson.hcursor
            for {
              code <- ec.downField("code").as[Int].toOption
              message <- ec.downField("message").as[String].toOption
            } yield JsonRpcError(code, message, ec.downField("data").focus)
          }
          Right(Right(JsonRpcResponse(id, result, error)))
        }
    }
  }
}
