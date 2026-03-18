package demiurge.repair

import java.time.Instant
import java.util.UUID

import demiurge.model._

// Phase 5: Builds a FailurePacket from verification results and run context.
// Collects requirements, verifier results, runtime plan, inspection report,
// manifest info, selectors, and patch history into a single packet for repair.
object FailurePacketBuilder {

  case class FailurePacketInput(
    runId:              String,
    attemptNumber:      Int,
    taskText:           String,
    verdicts:           List[RequirementVerdict],
    graph:              RequirementGraph,
    inspectionReport:   Option[RepoInspectionReport],
    runtimePlan:        Option[RuntimePlan],
    patchHistory:       List[PatchProposal],
    logs:               Option[String],
  )

  def build(input: FailurePacketInput): FailurePacket = {
    val failedVerdicts = input.verdicts.filter(v =>
      v.status == VerdictStatus.Fail || v.status == VerdictStatus.Timeout)

    val affectedRequirementIds = failedVerdicts.map(_.requirementId).distinct

    val primaryFailureClass = failedVerdicts.headOption
      .flatMap(_.failureClass)
      .getOrElse(FailureClass.UnknownFailure)

    val secondaryFailureClasses = failedVerdicts.flatMap(_.failureClass).distinct
      .filterNot(_ == primaryFailureClass)

    val failureSummary = buildSummary(input, failedVerdicts)

    val suspectedCauses = failedVerdicts.map { v =>
      SuspectedCause(
        description = v.failureMessage.getOrElse("Unknown failure"),
        confidence = 0.5,
        affectedFiles = Nil,
        affectedComponents = Nil,
        evidenceRefs = v.evidenceRefs,
      )
    }

    val repairScope = RepairScope(
      targetFiles = Nil,
      targetServices = input.runtimePlan.map(_.services.map(_.serviceId)).getOrElse(Nil),
      description = s"Repair for ${affectedRequirementIds.size} failed requirements",
      requiresEnvRebuild = false,
    )

    FailurePacket(
      failurePacketId = UUID.randomUUID().toString,
      runId = input.runId,
      attemptNumber = input.attemptNumber,
      primaryFailureClass = primaryFailureClass,
      secondaryFailureClasses = secondaryFailureClasses,
      summary = failureSummary,
      affectedRequirementIds = affectedRequirementIds,
      reproductionSteps = Nil,
      evidenceRefs = failedVerdicts.flatMap(_.evidenceRefs).distinct,
      suspectedRootCauses = suspectedCauses,
      recommendedRerunScope = affectedRequirementIds,
      recommendedRepairScope = repairScope,
      hardBlockers = Nil,
      softBlockers = Nil,
      producedAt = Instant.now(),
      inferenceRequestId = None,
    )
  }

  private def buildSummary(
    input: FailurePacketInput,
    failedVerdicts: List[RequirementVerdict],
  ): String = {
    val total = input.verdicts.size
    val failCount = failedVerdicts.size
    val passCount = input.verdicts.count(_.status == VerdictStatus.Pass)
    val failMessages = failedVerdicts.flatMap(_.failureMessage).take(3)
    val msgPart = if (failMessages.nonEmpty) s" Failures: ${failMessages.mkString("; ")}" else ""
    s"Verification failed: $failCount of $total verifiers failed ($passCount passed).$msgPart"
  }
}
