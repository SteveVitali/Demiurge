package lastmile.orchestrator

import lastmile.model._

// Phase 8: Tests for timeout and budget enforcement (Spec §8)
class TimeoutEnforcerSuite extends munit.FunSuite {

  private def mkBudget(
    runTimeoutMs: Long = 3600000L,
    attemptTimeoutMs: Long = 900000L,
  ): ExecutionBudget = ExecutionBudgetDefaults.defaults.copy(
    runTimeoutMs = runTimeoutMs,
    attemptTimeoutMs = attemptTimeoutMs,
  )

  test("run timeout not exceeded initially") {
    val clock = TimeoutEnforcer.create(mkBudget())
    assert(!clock.isRunTimedOut)
    assert(clock.runElapsedMs < 1000)
  }

  test("run timeout detected when budget is tiny") {
    val clock = TimeoutEnforcer.create(mkBudget(runTimeoutMs = 1L))
    Thread.sleep(5)
    assert(clock.isRunTimedOut)
  }

  test("attempt timeout not exceeded before startAttempt") {
    val clock = TimeoutEnforcer.create(mkBudget())
    assert(!clock.isAttemptTimedOut)
  }

  test("attempt timeout detected when budget is tiny") {
    val clock = TimeoutEnforcer.create(mkBudget(attemptTimeoutMs = 1L))
    clock.startAttempt()
    Thread.sleep(5)
    assert(clock.isAttemptTimedOut)
  }

  test("run remaining milliseconds decreases over time") {
    val clock = TimeoutEnforcer.create(mkBudget(runTimeoutMs = 10000L))
    val remaining1 = clock.runRemainingMs
    Thread.sleep(10)
    val remaining2 = clock.runRemainingMs
    assert(remaining2 < remaining1)
  }

  test("attempt remaining resets on new attempt start") {
    val clock = TimeoutEnforcer.create(mkBudget(attemptTimeoutMs = 10000L))
    clock.startAttempt()
    Thread.sleep(10)
    val r1 = clock.attemptRemainingMs

    clock.startAttempt()
    val r2 = clock.attemptRemainingMs
    assert(r2 > r1, "Remaining should increase after starting new attempt")
  }

  test("budget defaults match Spec §8.1 values") {
    val budget = ExecutionBudgetDefaults.defaults
    assertEquals(budget.runTimeoutMs, 3600000L)       // 60 min
    assertEquals(budget.attemptTimeoutMs, 900000L)    // 15 min
    assertEquals(budget.verifierTimeoutMs, 60000L)    // 60s
    assertEquals(budget.browserActionTimeoutMs, 15000L) // 15s
    assertEquals(budget.repairBackendTimeoutMs, 300000L) // 5 min
    assertEquals(budget.inferenceTimeoutMs, 120000L)  // 2 min
    assertEquals(budget.softResetTimeoutMs, 30000L)   // 30s
    assertEquals(budget.degradedRecoveryTimeoutMs, 30000L) // 30s
  }

  test("budget count defaults match Spec §8.2 values") {
    val budget = ExecutionBudgetDefaults.defaults
    assertEquals(budget.maxAttempts, 5)
    assertEquals(budget.maxEnvBootRetries, 2)
    assertEquals(budget.maxRepairRetriesPerAttempt, 1)
    assertEquals(budget.maxServiceRestarts, 2)
    assertEquals(budget.maxExploratorySteps, 50)
  }

  test("budget size defaults match Spec §8.3 values") {
    val budget = ExecutionBudgetDefaults.defaults
    assertEquals(budget.maxArtifactDiskBytes, 536870912L) // 512 MB
    assertEquals(budget.maxLogCaptureBytes, 10485760L)    // 10 MB
    assertEquals(budget.maxPatchLines, 2000)
    assertEquals(budget.maxRepairTokensPerInvocation, 200000L)
  }
}
