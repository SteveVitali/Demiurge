package demiurge.model

import java.time.Instant

// Spec §3.2: RerunPlan
case class RerunPlan(
  rerunPlanId:        String,
  runId:              String,
  fromAttemptNumber:  Int,
  toAttemptNumber:    Int,
  primaryVerifierIds: List[String],
  dependentVerifierIds: List[String],
  regressionVerifierIds: List[String],
  finalGateVerifierIds: List[String],
  patchChangedFiles:  List[String],
  infraSensitive:     Boolean,
  resetStrategy:      ResetStrategy,
  generatedAt:        Instant,
)
