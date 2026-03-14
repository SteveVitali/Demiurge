package demiurge.verification

import demiurge.model.VerdictStatus

// Phase 4: Verdict aggregation
// all pass → success, any fail → failure, errors treated as fail
object VerdictAggregator {

  case class AggregateResult(
    overallVerdict: VerdictStatus,
    passCount:      Int,
    failCount:      Int,
    errorCount:     Int,
    timeoutCount:   Int,
    total:          Int,
  )

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
      total = total,
    )
  }
}
