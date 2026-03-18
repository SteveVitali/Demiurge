package lastmile.verification

import munit.FunSuite
import lastmile.model.VerdictStatus

class VerdictAggregatorSuite extends FunSuite {

  test("all pass yields Pass verdict") {
    val outcomes = List(
      ("v-1", VerifierOutcome.Passed),
      ("v-2", VerifierOutcome.Passed),
      ("v-3", VerifierOutcome.Passed),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Pass)
    assertEquals(result.passCount, 3)
    assertEquals(result.failCount, 0)
    assertEquals(result.total, 3)
  }

  test("any failure yields Fail verdict") {
    val outcomes = List(
      ("v-1", VerifierOutcome.Passed),
      ("v-2", VerifierOutcome.Failed("bad")),
      ("v-3", VerifierOutcome.Passed),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
    assertEquals(result.passCount, 2)
    assertEquals(result.failCount, 1)
  }

  test("errors treated as failure") {
    val outcomes = List(
      ("v-1", VerifierOutcome.Passed),
      ("v-2", VerifierOutcome.Error("crash")),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
    assertEquals(result.errorCount, 1)
  }

  test("timeouts treated as failure") {
    val outcomes = List(
      ("v-1", VerifierOutcome.TimedOut),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
    assertEquals(result.timeoutCount, 1)
  }

  test("empty outcomes yields Pass") {
    val result = VerdictAggregator.aggregate(Nil)
    assertEquals(result.overallVerdict, VerdictStatus.Pass)
    assertEquals(result.total, 0)
  }

  test("mixed failures, errors, and timeouts") {
    val outcomes = List(
      ("v-1", VerifierOutcome.Passed),
      ("v-2", VerifierOutcome.Failed("fail")),
      ("v-3", VerifierOutcome.Error("error")),
      ("v-4", VerifierOutcome.TimedOut),
      ("v-5", VerifierOutcome.Passed),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
    assertEquals(result.passCount, 2)
    assertEquals(result.failCount, 1)
    assertEquals(result.errorCount, 1)
    assertEquals(result.timeoutCount, 1)
    assertEquals(result.total, 5)
  }
}
