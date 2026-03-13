package lastmile.model

// Spec §8: ExecutionBudget default values
object ExecutionBudgetDefaults {

  def defaults: ExecutionBudget = ExecutionBudget(
    runTimeoutMs              = 3600000L,   // 60 min
    attemptTimeoutMs          = 900000L,    // 15 min
    verifierTimeoutMs         = 60000L,     // 60s
    browserActionTimeoutMs    = 15000L,     // 15s
    repairBackendTimeoutMs    = 300000L,    // 5 min
    inferenceTimeoutMs        = 120000L,    // 2 min
    softResetTimeoutMs        = 30000L,     // 30s
    degradedRecoveryTimeoutMs = 30000L,     // 30s
    maxAttempts               = 5,
    maxRepairRetriesPerAttempt = 1,
    maxRepairTokensPerInvocation = 200000L,
    maxExploratorySteps       = 50,
    maxEnvBootRetries         = 2,
    maxArtifactDiskBytes      = 536870912L, // 512 MB
    maxLogCaptureBytes        = 10485760L,  // 10 MB
    maxPatchLines             = 2000,
    maxServiceRestarts        = 2,
    healthCheckIntervalMs     = 2000,
    healthCheckMaxFailures    = 30,
  )
}
