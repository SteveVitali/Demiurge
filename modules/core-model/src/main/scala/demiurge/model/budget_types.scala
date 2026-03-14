package demiurge.model

// Spec §3.2: ExecutionBudget
case class ExecutionBudget(
  runTimeoutMs:       Long,
  attemptTimeoutMs:   Long,
  verifierTimeoutMs:  Long,
  browserActionTimeoutMs: Long,
  repairBackendTimeoutMs: Long,
  inferenceTimeoutMs: Long,
  softResetTimeoutMs: Long,
  degradedRecoveryTimeoutMs: Long,
  maxAttempts:        Int,
  maxRepairRetriesPerAttempt: Int,
  maxRepairTokensPerInvocation: Long,
  maxExploratorySteps: Int,
  maxEnvBootRetries:  Int,
  maxArtifactDiskBytes: Long,
  maxLogCaptureBytes: Long,
  maxPatchLines:      Int,
  maxServiceRestarts: Int,
  healthCheckIntervalMs: Int,
  healthCheckMaxFailures: Int,
)
