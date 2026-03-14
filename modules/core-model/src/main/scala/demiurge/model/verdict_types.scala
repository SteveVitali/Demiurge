package demiurge.model

import java.time.Instant

// Spec §3.2: RequirementVerdict
case class RequirementVerdict(
  verdictId:          String,
  runId:              String,
  attemptNumber:      Int,
  requirementId:      String,
  verifierId:         String,
  status:             VerdictStatus,
  executionDurationMs: Long,
  retryCount:         Int,
  observations:       List[Observation],
  evidenceRefs:       List[String],
  failureClass:       Option[FailureClass],
  failureMessage:     Option[String],
  suggestedRerunScope: Option[List[String]],
  confidence:         Double,
  producedAt:         Instant,
)

// Spec §3.2: Observation
case class Observation(
  observationType:    String,
  message:            String,
  selector:           Option[String],
  expected:           Option[String],
  actual:             Option[String],
  timestamp:          Instant,
)
