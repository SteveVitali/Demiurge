package demiurge.model

import munit.FunSuite

class DefaultsSuite extends FunSuite {

  // Spec §8: All 19 ExecutionBudget default values
  test("ExecutionBudget.defaults has correct values per spec §8") {
    val b = ExecutionBudgetDefaults.defaults

    assertEquals(b.runTimeoutMs, 3600000L)
    assertEquals(b.attemptTimeoutMs, 900000L)
    assertEquals(b.verifierTimeoutMs, 60000L)
    assertEquals(b.browserActionTimeoutMs, 15000L)
    assertEquals(b.repairBackendTimeoutMs, 300000L)
    assertEquals(b.inferenceTimeoutMs, 120000L)
    assertEquals(b.softResetTimeoutMs, 30000L)
    assertEquals(b.degradedRecoveryTimeoutMs, 30000L)
    assertEquals(b.maxAttempts, 5)
    assertEquals(b.maxRepairRetriesPerAttempt, 1)
    assertEquals(b.maxRepairTokensPerInvocation, 200000L)
    assertEquals(b.maxExploratorySteps, 50)
    assertEquals(b.maxEnvBootRetries, 2)
    assertEquals(b.maxArtifactDiskBytes, 536870912L)
    assertEquals(b.maxLogCaptureBytes, 10485760L)
    assertEquals(b.maxPatchLines, 2000)
    assertEquals(b.maxServiceRestarts, 2)
    assertEquals(b.healthCheckIntervalMs, 2000)
    assertEquals(b.healthCheckMaxFailures, 30)
  }
}
