package lastmile.verification

import java.time.Instant
import java.util.UUID

import lastmile.model._

// Phase 4: VerificationEngine — orchestrates verifier generation, execution, and verdict aggregation.
// Runs all verifiers in-process, produces verdicts, and returns aggregate result.
object VerificationEngine {

  case class VerificationResult(
    verdicts:  List[RequirementVerdict],
    aggregate: VerdictAggregator.AggregateResult,
  )

  def runVerification(
    runId: String,
    attemptNumber: Int,
    graph: RequirementGraph,
  ): VerificationResult = {
    val verifiers = VerifierGenerator.generate(graph)
    val outcomes = verifiers.map { v =>
      val startTime = System.currentTimeMillis()
      val outcome = VerifierExecutor.execute(v)
      val durationMs = System.currentTimeMillis() - startTime
      (v, outcome, durationMs)
    }

    val verdicts = outcomes.map { case (verifier, outcome, durationMs) =>
      val (status, failureClass, failureMessage) = outcome match {
        case VerifierOutcome.Passed =>
          (VerdictStatus.Pass, None, None)
        case VerifierOutcome.Failed(msg) =>
          (VerdictStatus.Fail, Some(FailureClass.UnknownFailure), Some(msg))
        case VerifierOutcome.Error(msg) =>
          (VerdictStatus.Fail, Some(FailureClass.UnknownFailure), Some(msg))
        case VerifierOutcome.TimedOut =>
          (VerdictStatus.Timeout, None, Some("Verifier timed out"))
      }

      RequirementVerdict(
        verdictId = UUID.randomUUID().toString,
        runId = runId,
        attemptNumber = attemptNumber,
        requirementId = verifier.requirementId,
        verifierId = verifier.id,
        status = status,
        executionDurationMs = durationMs,
        retryCount = 0,
        observations = Nil,
        evidenceRefs = Nil,
        failureClass = failureClass,
        failureMessage = failureMessage,
        suggestedRerunScope = None,
        confidence = 1.0,
        producedAt = Instant.now(),
      )
    }

    val outcomeList = outcomes.map { case (v, o, _) => (v.id, o) }
    val aggregate = VerdictAggregator.aggregate(outcomeList)

    VerificationResult(verdicts = verdicts, aggregate = aggregate)
  }
}
