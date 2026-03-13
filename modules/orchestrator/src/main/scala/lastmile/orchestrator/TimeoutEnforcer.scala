package lastmile.orchestrator

import lastmile.model.ExecutionBudget

// Spec §8: Timeout enforcement utility.
// Tracks elapsed time and enforces run-level, attempt-level, and component-level timeouts.
object TimeoutEnforcer {

  // Not a case class — contains mutable state (attemptStartMs) which is inappropriate for case classes.
  class RunClock(
    val runStartMs: Long,
    val budget: ExecutionBudget,
  ) {
    @volatile private var attemptStartMs: Long = 0L

    def startAttempt(): Unit = {
      attemptStartMs = System.currentTimeMillis()
    }

    def isRunTimedOut: Boolean =
      (System.currentTimeMillis() - runStartMs) >= budget.runTimeoutMs

    def isAttemptTimedOut: Boolean =
      attemptStartMs > 0 && (System.currentTimeMillis() - attemptStartMs) >= budget.attemptTimeoutMs

    def runElapsedMs: Long = System.currentTimeMillis() - runStartMs
    def attemptElapsedMs: Long = if (attemptStartMs > 0) System.currentTimeMillis() - attemptStartMs else 0L
    def runRemainingMs: Long = math.max(0, budget.runTimeoutMs - runElapsedMs)
    def attemptRemainingMs: Long = if (attemptStartMs > 0) math.max(0, budget.attemptTimeoutMs - attemptElapsedMs) else budget.attemptTimeoutMs
  }

  def create(budget: ExecutionBudget): RunClock = new RunClock(System.currentTimeMillis(), budget)
}
