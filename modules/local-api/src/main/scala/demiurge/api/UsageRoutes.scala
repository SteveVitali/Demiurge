package demiurge.api

import com.sun.net.httpserver.HttpHandler
import io.circe.syntax._
import io.circe.Json

import demiurge.license.{CredentialStore, LicenseManager, LicenseStatus}

// Spec 05 §7.5: Sidecar /usage endpoint
// Proxies usage data from the cloud backend (or returns cached data from license validation).
// This avoids the desktop frontend needing to call the cloud API directly.
object UsageRoutes {

  /** GET /usage — return current usage for the authenticated license */
  def getUsageHandler(): HttpHandler = exchange => {
    if (exchange.getRequestMethod != "GET") {
      RouteHelpers.sendJson(exchange, 405, ApiEnvelope.error(405, "Method not allowed"))
    } else {
      try {
        // Use the license validation cache (which includes uses/maxUses)
        // to avoid an extra network round-trip
        LicenseManager.validate() match {
          case LicenseStatus.Valid(planTier, uses, maxUses, expiry, entitlements) =>
            val creds = CredentialStore.loadCredentials()
            val email = creds.flatMap(_.userEmail).getOrElse("")

            val response = Json.obj(
              "runs" -> Json.obj(
                "used" -> Json.fromInt(uses),
                "limit" -> Json.fromInt(maxUses),
                "periodEnd" -> (if (expiry.nonEmpty) Json.fromString(expiry) else Json.Null),
              ),
              "tokens" -> Json.obj(
                // Token usage is tracked locally — cloud aggregation is future work
                "used" -> Json.fromInt(0),
                "limit" -> Json.fromInt(0),
                "periodEnd" -> (if (expiry.nonEmpty) Json.fromString(expiry) else Json.Null),
              ),
              "account" -> Json.obj(
                "email" -> Json.fromString(email),
                "planTier" -> Json.fromString(planTier),
                "entitlements" -> Json.arr(entitlements.map(Json.fromString): _*),
              ),
            )
            RouteHelpers.sendJson(exchange, 200, ApiEnvelope.success(response))

          case LicenseStatus.NoCredentials =>
            RouteHelpers.sendJson(exchange, 401, ApiEnvelope.error(401, "Not authenticated"))

          case LicenseStatus.Expired(expiry) =>
            RouteHelpers.sendJson(exchange, 403, ApiEnvelope.error(403, s"License expired: $expiry"))

          case LicenseStatus.OverLimit(uses, maxUses) =>
            val response = Json.obj(
              "runs" -> Json.obj(
                "used" -> Json.fromInt(uses),
                "limit" -> Json.fromInt(maxUses),
              ),
              "error" -> Json.fromString("Usage limit exceeded"),
            )
            RouteHelpers.sendJson(exchange, 200, ApiEnvelope.success(response))

          case LicenseStatus.NetworkError(msg) =>
            // Offline: return cached data if available
            val creds = CredentialStore.loadCredentials()
            creds.flatMap(_.cachedValidation) match {
              case Some(cached) =>
                val response = Json.obj(
                  "runs" -> Json.obj(
                    "used" -> Json.fromInt(cached.uses),
                    "limit" -> Json.fromInt(cached.maxUses),
                    "periodEnd" -> (if (cached.expiry.nonEmpty) Json.fromString(cached.expiry) else Json.Null),
                  ),
                  "tokens" -> Json.obj(
                    "used" -> Json.fromInt(0),
                    "limit" -> Json.fromInt(0),
                  ),
                  "offline" -> Json.fromBoolean(true),
                )
                RouteHelpers.sendJson(exchange, 200, ApiEnvelope.success(response))
              case None =>
                RouteHelpers.sendJson(exchange, 503, ApiEnvelope.error(503, s"Cannot fetch usage: $msg"))
            }

          case other =>
            RouteHelpers.sendJson(exchange, 403, ApiEnvelope.error(403, s"License error: $other"))
        }
      } catch {
        case e: Exception =>
          RouteHelpers.sendJson(exchange, 500, ApiEnvelope.error(500, e.getMessage))
      }
    }
  }
}
