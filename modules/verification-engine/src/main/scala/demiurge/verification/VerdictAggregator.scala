package demiurge.verification

import demiurge.model.{RequirementGraph, RequirementNode, RequirementPriority, RequirementVerdict, VerdictStatus}

// Spec §4.3–4.4: Verdict aggregation
// §4.3: Requirement-level aggregation (multiple verifiers per requirement)
// §4.4: Attempt-level aggregation (priority-aware — only Required blocks success)
object VerdictAggregator {

  case class AggregateResult(
    overallVerdict: VerdictStatus,
    passCount:      Int,
    failCount:      Int,
    errorCount:     Int,
    timeoutCount:   Int,
    flakeCount:     Int,
    blockedCount:   Int,
    total:          Int,
  )

  // Spec §4.3: Compute a single requirement-level verdict from all its verifier verdicts.
  def requirementVerdict(verdicts: List[VerdictStatus]): VerdictStatus = {
    if (verdicts.isEmpty) return VerdictStatus.Pass
    if (verdicts.forall(_ == VerdictStatus.Pass)) return VerdictStatus.Pass
    if (verdicts.forall(v => v == VerdictStatus.Pass || v == VerdictStatus.Flake)) return VerdictStatus.Flake
    if (verdicts.exists(v => v == VerdictStatus.Blocked) &&
        !verdicts.exists(v => v == VerdictStatus.Fail || v == VerdictStatus.Timeout))
      return VerdictStatus.Blocked
    if (verdicts.exists(v => v == VerdictStatus.Fail || v == VerdictStatus.Timeout))
      return VerdictStatus.Fail
    VerdictStatus.Inconclusive
  }

  // Spec §4.4: Attempt-level aggregation — only Required priority blocks success.
  // Important and NiceToHave failures do NOT block success.
  def aggregateWithGraph(
    verdicts: List[RequirementVerdict],
    graph: RequirementGraph,
  ): AggregateResult = {
    val nodeMap = graph.nodes.map(n => n.requirementId -> n).toMap
    val total = verdicts.size

    // Count individual verifier outcomes
    var passCount = 0
    var failCount = 0
    var errorCount = 0
    var timeoutCount = 0
    var flakeCount = 0
    var blockedCount = 0

    verdicts.foreach { v =>
      v.status match {
        case VerdictStatus.Pass    => passCount += 1
        case VerdictStatus.Fail    => failCount += 1
        case VerdictStatus.Timeout => timeoutCount += 1
        case VerdictStatus.Flake   => flakeCount += 1
        case VerdictStatus.Blocked => blockedCount += 1
        case VerdictStatus.Inconclusive => errorCount += 1
      }
    }

    // §4.3: Group verdicts by requirement and compute requirement-level verdict
    val verdictsByReq = verdicts.groupBy(_.requirementId)
    val reqVerdicts: Map[String, VerdictStatus] = verdictsByReq.map { case (reqId, vs) =>
      reqId -> requirementVerdict(vs.map(_.status))
    }

    // §4.4: Only Required-priority requirements determine overall pass/fail
    val requiredReqIds = graph.nodes
      .filter(_.priority == RequirementPriority.Required)
      .map(_.requirementId)
      .toSet

    val requiredVerdicts = requiredReqIds.toList.flatMap(reqVerdicts.get)

    val overallVerdict = if (requiredVerdicts.isEmpty) {
      VerdictStatus.Pass
    } else if (requiredVerdicts.forall(v => v == VerdictStatus.Pass || v == VerdictStatus.Flake)) {
      // §4.5: If any required requirement was Flake, overall is Flake
      if (requiredVerdicts.exists(_ == VerdictStatus.Flake)) VerdictStatus.Flake
      else VerdictStatus.Pass
    } else {
      VerdictStatus.Fail
    }

    AggregateResult(
      overallVerdict = overallVerdict,
      passCount = passCount,
      failCount = failCount,
      errorCount = errorCount,
      timeoutCount = timeoutCount,
      flakeCount = flakeCount,
      blockedCount = blockedCount,
      total = total,
    )
  }

  // Legacy aggregate — flat aggregation without priority awareness.
  // Kept for backward compatibility with callers that don't have a RequirementGraph.
  def aggregate(outcomes: List[(String, VerifierOutcome)]): AggregateResult = {
    val total = outcomes.size
    var passCount = 0
    var failCount = 0
    var errorCount = 0
    var timeoutCount = 0

    outcomes.foreach { case (_, outcome) =>
      outcome match {
        case VerifierOutcome.Passed     => passCount += 1
        case VerifierOutcome.Failed(_)  => failCount += 1
        case VerifierOutcome.Error(_)   => errorCount += 1
        case VerifierOutcome.TimedOut   => timeoutCount += 1
      }
    }

    val verdict = if (total == 0) {
      VerdictStatus.Pass
    } else if (passCount == total) {
      VerdictStatus.Pass
    } else {
      VerdictStatus.Fail
    }

    AggregateResult(
      overallVerdict = verdict,
      passCount = passCount,
      failCount = failCount,
      errorCount = errorCount,
      timeoutCount = timeoutCount,
      flakeCount = 0,
      blockedCount = 0,
      total = total,
    )
  }
}
