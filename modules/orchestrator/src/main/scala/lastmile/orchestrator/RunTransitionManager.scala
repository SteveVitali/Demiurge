package lastmile.orchestrator

import java.sql.Connection
import java.time.Instant
import java.util.UUID

import io.circe.Json
import lastmile.model._
import lastmile.persistence._

// Spec §4.1: Persist-before-side-effects invariant.
// Every state transition:
//   1. Updates SQLite first (status + event)
//   2. Only then executes target-state side effect
object RunTransitionManager {

  // Decoupled event listener hook for SSE streaming.
  // CLI wires this to EventStream.publish at run start.
  @volatile private var eventListener: Option[SystemEvent => Unit] = None

  def setEventListener(listener: SystemEvent => Unit): Unit = {
    eventListener = Some(listener)
  }

  def clearEventListener(): Unit = {
    eventListener = None
  }

  private def publishEvent(event: SystemEvent): Unit = {
    eventListener.foreach { listener =>
      try { listener(event) } catch { case _: Exception => }
    }
  }

  /**
   * Transition a run from its current state to the target state.
   * Enforces the persist-before-side-effects invariant:
   *   1. Persist new status to SQLite within a transaction
   *   2. Insert a state-transition event
   *   3. Only then execute the side-effect callback
   *
   * Returns the updated TaskRun with the new status.
   */
  def transition(
    ctx: RunContext,
    targetStatus: RunStatus,
    sideEffect: RunContext => Unit,
  ): TaskRun = {
    implicit val conn: Connection = ctx.conn

    val now = Instant.now()
    val updatedRun = ctx.run.copy(
      status = targetStatus,
      startedAt = ctx.run.startedAt.orElse(Some(now)),
    )

    val event = SystemEvent(
      eventId = UUID.randomUUID().toString,
      runId = updatedRun.runId,
      attemptNumber = None,
      eventType = "state_transition",
      component = "orchestrator",
      severity = "info",
      timestamp = now,
      correlationFields = Map(
        "from_status" -> ctx.run.status.toString,
        "to_status" -> targetStatus.toString,
      ),
      payload = Map(
        "from_status" -> Json.fromString(ctx.run.status.toString),
        "to_status" -> Json.fromString(targetStatus.toString),
      ),
      humanMessage = s"Run ${updatedRun.runId} transitioned from ${ctx.run.status} to $targetStatus",
    )

    // Step 1: Persist state change before any side effect (Spec §4.1)
    TransactionManager.atomic(conn) { implicit txn =>
      TaskRunRepo.updateStatus(updatedRun.runId, targetStatus)(txn)
      if (ctx.run.startedAt.isEmpty) {
        TaskRunRepo.setStartedAt(updatedRun.runId, now)(txn)
      }
      EventRepo.insert(event)(txn)
    }

    // Publish to SSE listeners after DB commit
    publishEvent(event)

    // Step 2: Execute side effect only after persistence succeeds
    val updatedCtx = ctx.copy(run = updatedRun)
    sideEffect(updatedCtx)

    updatedRun
  }

  /**
   * Transition to a terminal state (Exhausted, Interrupted, etc.)
   * Sets endedAt and persists before any side effect.
   */
  def transitionToTerminal(
    ctx: RunContext,
    targetStatus: RunStatus,
    summary: Option[String] = None,
    finalVerdict: Option[VerdictStatus] = None,
  ): TaskRun = {
    implicit val conn: Connection = ctx.conn

    val now = Instant.now()
    val updatedRun = ctx.run.copy(
      status = targetStatus,
      endedAt = Some(now),
      finalSummary = summary.orElse(ctx.run.finalSummary),
      finalVerdict = finalVerdict.orElse(ctx.run.finalVerdict),
    )

    val termEvent = SystemEvent(
      eventId = UUID.randomUUID().toString,
      runId = updatedRun.runId,
      attemptNumber = None,
      eventType = "state_transition",
      component = "orchestrator",
      severity = if (targetStatus == RunStatus.Interrupted) "warn" else "info",
      timestamp = now,
      correlationFields = Map(
        "from_status" -> ctx.run.status.toString,
        "to_status" -> targetStatus.toString,
      ),
      payload = Map(
        "from_status" -> Json.fromString(ctx.run.status.toString),
        "to_status" -> Json.fromString(targetStatus.toString),
      ),
      humanMessage = s"Run ${updatedRun.runId} terminated: ${ctx.run.status} → $targetStatus",
    )

    // Persist terminal state
    TransactionManager.atomic(conn) { implicit txn =>
      TaskRunRepo.updateStatus(updatedRun.runId, targetStatus, endedAt = Some(now))(txn)
      summary.foreach(s => TaskRunRepo.setFinalSummary(updatedRun.runId, s)(txn))
      finalVerdict.foreach(v => TaskRunRepo.setFinalVerdict(updatedRun.runId, v)(txn))
      EventRepo.insert(termEvent)(txn)
    }

    // Publish to SSE listeners after DB commit
    publishEvent(termEvent)

    updatedRun
  }
}
