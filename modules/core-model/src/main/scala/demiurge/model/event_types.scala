package demiurge.model

import java.time.Instant
import io.circe.Json

// Spec §3.2: SystemEvent
// Rule 8: payload is Map[String, Json] instead of Map[String, Any] for serializability
case class SystemEvent(
  eventId:          String,
  runId:            String,
  attemptNumber:    Option[Int],
  eventType:        String,
  component:        String,
  severity:         String,
  timestamp:        Instant,
  correlationFields: Map[String, String],
  payload:          Map[String, Json],
  humanMessage:     String,
)
