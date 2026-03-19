package demiurge.api

import com.sun.net.httpserver.{HttpExchange, HttpHandler}

object CorsMiddleware {

  private val allowedOrigins: Set[String] = Set(
    "http://localhost:1420",
    "tauri://localhost",
  )

  def wrap(handler: HttpHandler): HttpHandler = exchange => {
    val origin = Option(exchange.getRequestHeaders.getFirst("Origin")).getOrElse("")
    val effectiveOrigin = if (allowedOrigins.contains(origin)) origin else ""

    if (effectiveOrigin.nonEmpty) {
      exchange.getResponseHeaders.set("Access-Control-Allow-Origin", effectiveOrigin)
      exchange.getResponseHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
      exchange.getResponseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization")
      exchange.getResponseHeaders.set("Access-Control-Max-Age", "86400")
    }

    if (exchange.getRequestMethod.equalsIgnoreCase("OPTIONS")) {
      exchange.sendResponseHeaders(204, -1)
      exchange.getResponseBody.close()
    } else {
      handler.handle(exchange)
    }
  }
}
