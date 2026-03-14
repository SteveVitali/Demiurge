package demiurge.model

import java.time.Instant

// Spec §3.2: FinalReport
case class FinalReport(
  runId:              String,
  taskText:           String,
  finalVerdict:       VerdictStatus,
  finalSummary:       String,
  totalAttempts:      Int,
  totalDurationMs:    Long,
  requirementResults: List[RequirementResult],
  attemptSummaries:   List[AttemptReportSummary],
  flakyVerifiers:     List[FlakyVerifierReport],
  unresolvedBlockers: List[String],
  totalUsage:         AggregateUsage,
  generatedAt:        Instant,
)

// Spec §3.2: RequirementResult
case class RequirementResult(
  requirementId:      String,
  description:        String,
  priority:           RequirementPriority,
  finalVerdict:       VerdictStatus,
  verifierResults:    List[VerifierResult],
)

// Spec §3.2: VerifierResult
case class VerifierResult(
  verifierId:         String,
  displayName:        String,
  verifierType:       VerifierType,
  finalVerdict:       VerdictStatus,
  attemptNumber:      Int,
  evidenceArtifacts:  List[String],
  failureMessage:     Option[String],
)

// Spec §3.2: AttemptReportSummary
case class AttemptReportSummary(
  attemptNumber:      Int,
  verdict:            String,
  failureClasses:     List[FailureClass],
  patchSummary:       Option[String],
  filesChanged:       List[String],
  durationMs:         Long,
)

// Spec §3.2: FlakyVerifierReport
case class FlakyVerifierReport(
  verifierId:         String,
  displayName:        String,
  retryResults:       List[VerdictStatus],
)

// Spec §3.2: AggregateUsage
case class AggregateUsage(
  totalInferenceTokens: Long,
  totalRepairTokens:  Long,
  estimatedCostUsd:   Double,
  totalRepairCalls:   Int,
  totalInferenceCalls: Int,
)
