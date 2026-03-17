package demiurge.model

// Phase D: Build mode budget defaults — higher limits than verify mode
// because initial code generation is more expensive than repair.
object BuildBudgetDefaults {

  def defaults: ExecutionBudget = ExecutionBudget(
    runTimeoutMs              = 7200000L,    // 2 hours (vs 1h for verify)
    attemptTimeoutMs          = 1800000L,    // 30 min (vs 15m for verify)
    verifierTimeoutMs         = 60000L,      // same
    browserActionTimeoutMs    = 15000L,      // same
    repairBackendTimeoutMs    = 600000L,     // 10 min (vs 5m — initial gen is bigger)
    inferenceTimeoutMs        = 120000L,     // same
    softResetTimeoutMs        = 30000L,      // same
    degradedRecoveryTimeoutMs = 30000L,      // same
    maxAttempts               = 8,           // more attempts (vs 5 for verify)
    maxRepairRetriesPerAttempt = 2,          // allow more retries per attempt
    maxRepairTokensPerInvocation = 500000L,  // 500k tokens (vs 200k)
    maxExploratorySteps       = 100,         // more exploration (vs 50)
    maxEnvBootRetries         = 3,           // more tolerance (vs 2)
    maxArtifactDiskBytes      = 1073741824L, // 1 GB (vs 512 MB)
    maxLogCaptureBytes        = 10485760L,   // same
    maxPatchLines             = 5000,        // larger patches (vs 2000)
    maxServiceRestarts        = 3,           // more restarts (vs 2)
    healthCheckIntervalMs     = 2000,        // same
    healthCheckMaxFailures    = 30,          // same
  )
}
