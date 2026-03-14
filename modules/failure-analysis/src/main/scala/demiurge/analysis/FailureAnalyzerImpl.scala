package demiurge.analysis

import java.time.Instant
import java.util.UUID

import demiurge.model._
import demiurge.inference.InferenceService

// Spec §5.2, §7: Inference-backed failure analyzer with rule-based fallback.
// On inference failure: produce rule-based FailurePacket with confidence 0.3 and UnknownFailure.
class FailureAnalyzerImpl(
  inferenceService: Option[InferenceService] = None,
  model: String = "claude-sonnet-4-20250514",
) extends FailureAnalyzer {

  override def analyze(
    runId: String,
    attemptNumber: Int,
    verdicts: List[RequirementVerdict],
    graph: RequirementGraph,
    taskText: String,
    changedFiles: Option[List[String]],
  ): FailurePacket = {
    val failedVerdicts = verdicts.filter(v =>
      v.status == VerdictStatus.Fail || v.status == VerdictStatus.Timeout || v.status == VerdictStatus.Inconclusive)

    // Try inference-backed analysis first
    inferenceService match {
      case Some(svc) =>
        tryInferenceAnalysis(svc, runId, attemptNumber, failedVerdicts, graph, taskText, changedFiles)
          .getOrElse(buildRuleBasedPacket(runId, attemptNumber, failedVerdicts, graph, changedFiles))
      case None =>
        buildRuleBasedPacket(runId, attemptNumber, failedVerdicts, graph, changedFiles)
    }
  }

  // Spec §5.2: Attempt LLM-backed failure analysis
  private def tryInferenceAnalysis(
    svc: InferenceService,
    runId: String,
    attemptNumber: Int,
    failedVerdicts: List[RequirementVerdict],
    graph: RequirementGraph,
    taskText: String,
    changedFiles: Option[List[String]],
  ): Option[FailurePacket] = {
    val requestId = UUID.randomUUID().toString
    val failureSummary = failedVerdicts.map { v =>
      s"- ${v.verifierId}: ${v.status} ${v.failureClass.map(_.toString).getOrElse("")} ${v.failureMessage.getOrElse("")}"
    }.mkString("\n")

    val userPrompt = s"""Analyze the following verification failures for task: "$taskText"

Failed verifiers:
$failureSummary

Changed files: ${changedFiles.map(_.mkString(", ")).getOrElse("unknown")}

Requirements graph has ${graph.nodes.size} nodes.

Produce a JSON object with:
- primaryFailureClass: one of the FailureClass enum values
- summary: human-readable summary
- suspectedRootCauses: list of {description, confidence, affectedFiles, affectedComponents}
- reproductionSteps: list of {order, description}
- recommendedRepairScope: {targetFiles, targetServices, description, requiresEnvRebuild}"""

    val request = InferenceRequest(
      requestId = requestId,
      runId = runId,
      attemptNumber = Some(attemptNumber),
      component = "failure_analyzer",
      provider = InferenceProvider.Anthropic,
      model = model,
      systemPrompt = "You are a failure analysis engine for a web application testing system. Analyze test failures and produce structured root cause analysis.",
      userPrompt = userPrompt,
      responseFormat = Some("json"),
      jsonSchema = None,
      maxOutputTokens = 4096,
      temperature = 0.0,
      cacheable = true,
      timeoutMs = 120000L,
      metadata = Map("attemptNumber" -> attemptNumber.toString),
    )

    svc.infer(request) match {
      case Right(response) =>
        // Parse the LLM response into a FailurePacket
        Some(buildPacketFromInference(runId, attemptNumber, failedVerdicts, graph, response, changedFiles))
      case Left(_) =>
        // Spec §5.2: On inference failure, fall back to rule-based
        None
    }
  }

  // Build FailurePacket from inference response (best-effort parse)
  private def buildPacketFromInference(
    runId: String,
    attemptNumber: Int,
    failedVerdicts: List[RequirementVerdict],
    graph: RequirementGraph,
    response: InferenceResponse,
    changedFiles: Option[List[String]],
  ): FailurePacket = {
    // Even with LLM response, build a structured packet using the response text
    val primaryClass = classifyPrimaryFailure(failedVerdicts)
    val affectedReqIds = failedVerdicts.map(_.requirementId).distinct

    FailurePacket(
      failurePacketId = UUID.randomUUID().toString,
      runId = runId,
      attemptNumber = attemptNumber,
      primaryFailureClass = primaryClass,
      secondaryFailureClasses = classifySecondaryFailures(failedVerdicts),
      summary = response.responseText.take(500),
      affectedRequirementIds = affectedReqIds,
      reproductionSteps = buildReproductionSteps(failedVerdicts),
      evidenceRefs = failedVerdicts.flatMap(_.evidenceRefs),
      suspectedRootCauses = List(SuspectedCause(
        description = response.responseText.take(200),
        confidence = 0.7,
        affectedFiles = changedFiles.getOrElse(Nil),
        affectedComponents = Nil,
        evidenceRefs = failedVerdicts.flatMap(_.evidenceRefs),
      )),
      recommendedRerunScope = affectedReqIds,
      recommendedRepairScope = RepairScope(
        targetFiles = changedFiles.getOrElse(Nil),
        targetServices = Nil,
        description = s"Repair ${affectedReqIds.size} failed requirements",
        requiresEnvRebuild = false,
      ),
      hardBlockers = Nil,
      softBlockers = Nil,
      producedAt = Instant.now(),
      inferenceRequestId = Some(response.requestId),
    )
  }

  // Spec §5.2: Rule-based fallback — produces packet with confidence 0.3 and UnknownFailure
  private[analysis] def buildRuleBasedPacket(
    runId: String,
    attemptNumber: Int,
    failedVerdicts: List[RequirementVerdict],
    graph: RequirementGraph,
    changedFiles: Option[List[String]],
  ): FailurePacket = {
    val primaryClass = classifyPrimaryFailure(failedVerdicts)
    val affectedReqIds = failedVerdicts.map(_.requirementId).distinct

    FailurePacket(
      failurePacketId = UUID.randomUUID().toString,
      runId = runId,
      attemptNumber = attemptNumber,
      primaryFailureClass = primaryClass,
      secondaryFailureClasses = classifySecondaryFailures(failedVerdicts),
      summary = buildRuleSummary(failedVerdicts),
      affectedRequirementIds = affectedReqIds,
      reproductionSteps = buildReproductionSteps(failedVerdicts),
      evidenceRefs = failedVerdicts.flatMap(_.evidenceRefs),
      suspectedRootCauses = List(SuspectedCause(
        description = s"Rule-based analysis: ${failedVerdicts.size} verifiers failed",
        confidence = 0.3,
        affectedFiles = changedFiles.getOrElse(Nil),
        affectedComponents = Nil,
        evidenceRefs = failedVerdicts.flatMap(_.evidenceRefs),
      )),
      recommendedRerunScope = affectedReqIds,
      recommendedRepairScope = RepairScope(
        targetFiles = changedFiles.getOrElse(Nil),
        targetServices = Nil,
        description = s"Repair ${affectedReqIds.size} failed requirements (rule-based analysis)",
        requiresEnvRebuild = false,
      ),
      hardBlockers = Nil,
      softBlockers = Nil,
      producedAt = Instant.now(),
      inferenceRequestId = None,
    )
  }

  // Classify primary failure from verdicts using rule-based heuristics
  private def classifyPrimaryFailure(failedVerdicts: List[RequirementVerdict]): FailureClass = {
    if (failedVerdicts.isEmpty) return FailureClass.UnknownFailure
    // Use the most common failure class, or UnknownFailure
    val classes = failedVerdicts.flatMap(_.failureClass)
    if (classes.isEmpty) FailureClass.UnknownFailure
    else classes.groupBy(identity).maxBy(_._2.size)._1
  }

  private def classifySecondaryFailures(failedVerdicts: List[RequirementVerdict]): List[FailureClass] = {
    failedVerdicts.flatMap(_.failureClass).distinct
  }

  private def buildRuleSummary(failedVerdicts: List[RequirementVerdict]): String = {
    val failCount = failedVerdicts.count(_.status == VerdictStatus.Fail)
    val timeoutCount = failedVerdicts.count(_.status == VerdictStatus.Timeout)
    val inconclusiveCount = failedVerdicts.count(_.status == VerdictStatus.Inconclusive)
    s"$failCount failures, $timeoutCount timeouts, $inconclusiveCount inconclusive out of ${failedVerdicts.size} failed verifiers"
  }

  private def buildReproductionSteps(failedVerdicts: List[RequirementVerdict]): List[ReproductionStep] = {
    failedVerdicts.zipWithIndex.map { case (v, i) =>
      ReproductionStep(
        order = i + 1,
        description = s"Execute verifier ${v.verifierId}: ${v.failureMessage.getOrElse("failed")}",
        actionType = None,
        target = Some(v.verifierId),
      )
    }
  }
}
