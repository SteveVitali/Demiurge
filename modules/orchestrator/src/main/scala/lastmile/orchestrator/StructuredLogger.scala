package lastmile.orchestrator

import java.time.Instant
import java.util.UUID

import io.circe.Json
import lastmile.model._
import lastmile.persistence.EventRepo

// Spec §16.4: Structured JSON log lines to stderr.
// Spec §16.1: Event envelope schema with all required fields.
// Spec §16.3: Events persisted to SQLite asynchronously.
object StructuredLogger {

  @volatile var verbose: Boolean = false
  @volatile var quiet: Boolean = false

  // Spec §16.4: Structured JSON log line format
  def log(level: String, component: String, msg: String, runId: String,
          fields: Map[String, String] = Map.empty): Unit = {
    val shouldLog = level match {
      case "debug" => verbose
      case "info"  => !quiet
      case "warn"  => true
      case "error" => true
      case _       => !quiet
    }
    if (shouldLog) {
      val fieldsJson = fields.map { case (k, v) => s""""${escapeJson(k)}":"${escapeJson(v)}"""" }.mkString(",")
      val extra = if (fieldsJson.nonEmpty) s",$fieldsJson" else ""
      System.err.println(
        s"""{"ts":"${Instant.now()}","level":"${escapeJson(level)}","component":"${escapeJson(component)}","msg":"${escapeJson(msg)}","runId":"${escapeJson(runId)}"$extra}"""
      )
    }
  }

  private def escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

  // Spec §16.2: Emit a structured SystemEvent and optionally persist it.
  def emitEvent(
    runId: String,
    attemptNumber: Option[Int],
    eventType: String,
    component: String,
    severity: String,
    payload: Map[String, Json],
    humanMessage: String,
    correlationFields: Map[String, String] = Map.empty,
    conn: Option[java.sql.Connection] = None,
  ): SystemEvent = {
    val event = SystemEvent(
      eventId = UUID.randomUUID().toString,
      runId = runId,
      attemptNumber = attemptNumber,
      eventType = eventType,
      component = component,
      severity = severity,
      timestamp = Instant.now(),
      correlationFields = correlationFields,
      payload = payload,
      humanMessage = humanMessage,
    )

    // Log to stderr
    log(severity, component, humanMessage, runId, correlationFields)

    // Spec §16.5: Persist if not debug (unless verbose mode)
    val shouldPersist = severity != "debug" || verbose
    if (shouldPersist) {
      conn.foreach { implicit c =>
        try {
          EventRepo.insert(event)(c)
        } catch {
          // Spec §16.3: If SQLite write fails, log to stderr and drop. Does NOT affect run execution.
          case e: Exception =>
            System.err.println(s"""{"ts":"${Instant.now()}","level":"warn","component":"logger","msg":"Failed to persist event: ${e.getMessage}","runId":"$runId"}""")
        }
      }
    }

    event
  }

  // Convenience methods for common event types (Spec §16.2)

  def runCreated(runId: String, runMode: RunMode, taskText: String, conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, None, "run_created", "orchestrator", "info",
      Map("runMode" -> Json.fromString(runMode.toString), "taskText" -> Json.fromString(taskText.take(200))),
      s"Run $runId created in mode $runMode", conn = conn)

  def runStateChanged(runId: String, fromState: RunStatus, toState: RunStatus, trigger: String = "",
                      conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, None, "run_state_changed", "orchestrator", "info",
      Map("fromState" -> Json.fromString(fromState.toString), "toState" -> Json.fromString(toState.toString),
        "trigger" -> Json.fromString(trigger)),
      s"Run $runId: $fromState → $toState", conn = conn)

  def runCompleted(runId: String, finalVerdict: VerdictStatus, totalAttempts: Int, durationMs: Long,
                   conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, None, "run_completed", "orchestrator", "info",
      Map("finalVerdict" -> Json.fromString(finalVerdict.toString),
        "totalAttempts" -> Json.fromInt(totalAttempts),
        "durationMs" -> Json.fromLong(durationMs)),
      s"Run $runId completed: $finalVerdict after $totalAttempts attempts", conn = conn)

  def verificationStarted(runId: String, attemptNumber: Int, verifierCount: Int,
                          conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, Some(attemptNumber), "verification_started", "verifier_engine", "info",
      Map("attemptNumber" -> Json.fromInt(attemptNumber), "verifierCount" -> Json.fromInt(verifierCount)),
      s"Verification started for attempt $attemptNumber with $verifierCount verifiers", conn = conn)

  def verifierCompleted(runId: String, attemptNumber: Int, verifierId: String, verdict: VerdictStatus,
                        durationMs: Long, failureClass: Option[FailureClass] = None,
                        conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, Some(attemptNumber), "verifier_completed", "verifier_engine", "info",
      Map("verifierId" -> Json.fromString(verifierId), "verdict" -> Json.fromString(verdict.toString),
        "durationMs" -> Json.fromLong(durationMs)) ++
        failureClass.map(fc => "failureClass" -> Json.fromString(fc.toString)),
      s"Verifier $verifierId: $verdict (${durationMs}ms)",
      correlationFields = Map("verifierId" -> verifierId), conn = conn)

  def repairStarted(runId: String, attemptNumber: Int, backendId: String,
                    conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, Some(attemptNumber), "repair_started", "repair_orchestrator", "info",
      Map("attemptNumber" -> Json.fromInt(attemptNumber), "backendId" -> Json.fromString(backendId)),
      s"Repair started for attempt $attemptNumber using $backendId", conn = conn)

  def repairCompleted(runId: String, attemptNumber: Int, status: String, filesChanged: List[String],
                      conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, Some(attemptNumber), "repair_completed", "repair_orchestrator", "info",
      Map("status" -> Json.fromString(status), "filesChanged" -> Json.fromValues(filesChanged.map(Json.fromString))),
      s"Repair completed: $status, ${filesChanged.size} files changed", conn = conn)

  def inferenceCompleted(runId: String, requestId: String, component: String, inputTokens: Long,
                         outputTokens: Long, cachedHit: Boolean, durationMs: Long,
                         conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, None, "inference_completed", "inference_service", "info",
      Map("requestId" -> Json.fromString(requestId), "component" -> Json.fromString(component),
        "inputTokens" -> Json.fromLong(inputTokens), "outputTokens" -> Json.fromLong(outputTokens),
        "cachedHit" -> Json.fromBoolean(cachedHit), "durationMs" -> Json.fromLong(durationMs)),
      s"Inference completed for $component: ${inputTokens + outputTokens} tokens (${durationMs}ms)",
      correlationFields = Map("requestId" -> requestId), conn = conn)

  def environmentReady(runId: String, bootDurationMs: Long, conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, None, "environment_ready", "runtime_supervisor", "info",
      Map("bootDurationMs" -> Json.fromLong(bootDurationMs)),
      s"Environment ready after ${bootDurationMs}ms", conn = conn)

  def environmentFailed(runId: String, reason: String, failedServiceIds: List[String],
                        conn: Option[java.sql.Connection] = None): SystemEvent =
    emitEvent(runId, None, "environment_failed", "runtime_supervisor", "error",
      Map("reason" -> Json.fromString(reason),
        "failedServiceIds" -> Json.fromValues(failedServiceIds.map(Json.fromString))),
      s"Environment failed: $reason", conn = conn)
}
