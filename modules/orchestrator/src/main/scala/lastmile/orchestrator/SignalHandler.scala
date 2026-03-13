package lastmile.orchestrator

import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

import lastmile.model._
import lastmile.persistence._

// Spec §4.4: Signal handling for interruption persistence.
// On SIGINT/SIGTERM, persist run as Interrupted before JVM exits.
object SignalHandler {

  @volatile private var registered: Boolean = false
  @volatile private var currentCtx: Option[RunContext] = None
  @volatile private var currentRepoRoot: Option[Path] = None
  @volatile private var interrupted: Boolean = false

  /** Check if the orchestrator has been interrupted. */
  def isInterrupted: Boolean = interrupted

  /**
   * Register a shutdown hook that will persist interruption state.
   * Should be called once before the orchestrator loop begins.
   */
  def register(ctx: RunContext, repoRoot: Path): Unit = {
    currentCtx = Some(ctx)
    currentRepoRoot = Some(repoRoot)

    if (!registered) {
      registered = true
      Runtime.getRuntime.addShutdownHook(new Thread("demiurge-signal-handler") {
        override def run(): Unit = {
          handleInterrupt()
        }
      })
    }
  }

  /** Update the context as the run progresses through states. */
  def updateContext(ctx: RunContext): Unit = {
    currentCtx = Some(ctx)
  }

  /**
   * Handle an interrupt signal. Spec §4.4:
   * - Mark run as Interrupted in SQLite
   * - Release or leave lock in recoverable stale state
   * - Partial run remains resumable in DB
   */
  private[orchestrator] def handleInterrupt(): Unit = {
    interrupted = true
    for {
      ctx <- currentCtx
      repoRoot <- currentRepoRoot
    } {
      try {
        implicit val conn: Connection = ctx.conn
        // Check if the run is not already in a terminal state
        val current = TaskRunRepo.getById(ctx.run.runId)
        current.foreach { run =>
          val isTerminal = run.status == RunStatus.Succeeded ||
            run.status == RunStatus.Exhausted ||
            run.status == RunStatus.Cancelled ||
            run.status == RunStatus.Interrupted
          if (!isTerminal) {
            TaskRunRepo.updateStatus(run.runId, RunStatus.Interrupted, endedAt = Some(Instant.now()))
          }
        }
      } catch {
        case _: Exception =>
          // Best-effort: if DB write fails during shutdown, the stale lock
          // will be cleaned up on next run (Spec §4.3 stale lock recovery)
      }
      // Leave lock file in place — it becomes a stale lock that next run can recover
      // (Spec §4.3: if owning PID dead → delete stale lock and acquire)
    }
  }

  /**
   * Simulate an interrupt for testing purposes.
   * This directly invokes the interrupt handling logic.
   */
  private[orchestrator] def simulateInterrupt(): Unit = {
    handleInterrupt()
  }

  /** Reset state for testing. */
  private[orchestrator] def reset(): Unit = {
    currentCtx = None
    currentRepoRoot = None
    interrupted = false
    // Note: cannot unregister shutdown hooks, but we clear the context
  }
}
