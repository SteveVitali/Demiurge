package demiurge.api

import com.sun.net.httpserver.HttpExchange

// Shared HTTP route helper methods extracted from Routes, InspectionRoutes, FailureRoutes.
object RouteHelpers {

  def sendJson(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes("UTF-8")
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val os = exchange.getResponseBody
    try { os.write(bytes) } finally { os.close() }
  }

  def extractRunIdFromPath(path: String): String = {
    val parts = path.split("/").filter(_.nonEmpty)
    if (parts.length >= 2) parts(1) else ""
  }

  def extractPathParam(path: String, prefix: String): String = {
    val after = path.stripPrefix(prefix)
    after.split("/")(0)
  }

  def parseQueryParams(query: String): Map[String, String] = {
    if (query.isEmpty) Map.empty
    else query.split("&").flatMap { pair =>
      val kv = pair.split("=", 2)
      if (kv.length == 2) Some(kv(0) -> java.net.URLDecoder.decode(kv(1), "UTF-8"))
      else None
    }.toMap
  }
}
