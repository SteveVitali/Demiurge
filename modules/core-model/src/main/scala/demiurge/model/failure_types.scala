package demiurge.model

import java.time.Instant

// Spec §3.2: FailurePacket
case class FailurePacket(
  failurePacketId:    String,
  runId:              String,
  attemptNumber:      Int,
  primaryFailureClass: FailureClass,
  secondaryFailureClasses: List[FailureClass],
  summary:            String,
  affectedRequirementIds: List[String],
  reproductionSteps:  List[ReproductionStep],
  evidenceRefs:       List[String],
  suspectedRootCauses: List[SuspectedCause],
  recommendedRerunScope: List[String],
  recommendedRepairScope: RepairScope,
  hardBlockers:       List[String],
  softBlockers:       List[String],
  producedAt:         Instant,
  inferenceRequestId: Option[String],
)

// Spec §3.2: ReproductionStep
case class ReproductionStep(
  order:              Int,
  description:        String,
  actionType:         Option[String],
  target:             Option[String],
)

// Spec §3.2: SuspectedCause
case class SuspectedCause(
  description:        String,
  confidence:         Double,
  affectedFiles:      List[String],
  affectedComponents: List[String],
  evidenceRefs:       List[String],
)

// Spec §3.2: RepairScope
case class RepairScope(
  targetFiles:        List[String],
  targetServices:     List[String],
  description:        String,
  requiresEnvRebuild: Boolean,
)
