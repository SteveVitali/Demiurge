package demiurge.verification

import java.time.Instant
import java.util.UUID

import demiurge.model._

// Phase 4+6: VerificationEngine — orchestrates verifier generation, execution, and verdict aggregation.
// Phase 6: Supports optional WorkerProcessManager for browser verifier dispatch.
// Browser verifiers produce observations and evidenceRefs from worker artifacts.
object VerificationEngine {

  case class VerificationResult(
    verdicts:  List[RequirementVerdict],
    aggregate: VerdictAggregator.AggregateResult,
  )

  // Phase 6: Browser verifier executor — injected by orchestrator when worker is available
  trait BrowserVerifierExecutor {
    def execute(verifier: BrowserFlowVerifier): BrowserVerifierResult
  }

  def runVerification(
    runId: String,
    attemptNumber: Int,
    graph: RequirementGraph,
    browserExecutor: Option[BrowserVerifierExecutor] = None,
  ): VerificationResult = {
    val verifiers = VerifierGenerator.generate(graph)
    val outcomes = verifiers.map { v =>
      val startTime = System.currentTimeMillis()
      v match {
        // Phase 6: Browser verifiers dispatched through worker
        case bv: BrowserFlowVerifier =>
          browserExecutor match {
            case Some(executor) =>
              val browserResult = executor.execute(bv)
              val durationMs = System.currentTimeMillis() - startTime
              (v, browserResult.outcome, durationMs, browserResult.observations, browserResult.artifactRefs)
            case None =>
              val durationMs = System.currentTimeMillis() - startTime
              (v, VerifierOutcome.Error("No browser executor available for BrowserFlowVerifier"): VerifierOutcome, durationMs, List.empty[Observation], List.empty[String])
          }
        case _ =>
          val outcome = VerifierExecutor.execute(v)
          val durationMs = System.currentTimeMillis() - startTime
          (v, outcome, durationMs, List.empty[Observation], List.empty[String])
      }
    }

    val verdicts = outcomes.map { case (verifier, outcome, durationMs, observations, evidenceRefs) =>
      val (status, failureClass, failureMessage) = outcome match {
        case VerifierOutcome.Passed =>
          (VerdictStatus.Pass, None, None)
        case VerifierOutcome.Failed(msg) =>
          val fc = verifier match {
            case _: BrowserFlowVerifier => Some(FailureClass.FrontendRenderError)
            case _ => Some(FailureClass.UnknownFailure)
          }
          (VerdictStatus.Fail, fc, Some(msg))
        case VerifierOutcome.Error(msg) =>
          (VerdictStatus.Fail, Some(FailureClass.UnknownFailure), Some(msg))
        case VerifierOutcome.TimedOut =>
          val fc = verifier match {
            case _: BrowserFlowVerifier => Some(FailureClass.BrowserTimingFlake)
            case _ => None
          }
          (VerdictStatus.Timeout, fc, Some("Verifier timed out"))
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
        observations = observations,
        evidenceRefs = evidenceRefs,
        failureClass = failureClass,
        failureMessage = failureMessage,
        suggestedRerunScope = None,
        confidence = 1.0,
        producedAt = Instant.now(),
      )
    }

    val outcomeList = outcomes.map { case (v, o, _, _, _) => (v.id, o) }
    val aggregate = VerdictAggregator.aggregate(outcomeList)

    VerificationResult(verdicts = verdicts, aggregate = aggregate)
  }
}
