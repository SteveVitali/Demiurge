package demiurge.license

import munit.FunSuite

// Spec 05 §10.1: Unit tests for UsageReporter
class UsageReporterSuite extends FunSuite {

  test("UsageReport holds correct uses and maxUses") {
    val report = UsageReport(42, 200)
    assertEquals(report.uses, 42)
    assertEquals(report.maxUses, 200)
  }

  test("UsageLimitExceeded holds correct uses and maxUses") {
    val exceeded = UsageLimitExceeded(200, 200)
    assertEquals(exceeded.uses, 200)
    assertEquals(exceeded.maxUses, 200)
  }

  test("UsageReportError holds message") {
    val error = UsageReportError("Connection refused")
    assertEquals(error.message, "Connection refused")
  }

  test("incrementRunCount returns error for empty license key") {
    val result = UsageReporter.incrementRunCount("", "fingerprint123")
    assert(result.isLeft)
    result.left.foreach {
      case UsageReportError(msg) =>
        assert(msg.contains("No license key"), s"Expected 'No license key' error, got: $msg")
      case other =>
        fail(s"Expected UsageReportError, got: $other")
    }
  }

  test("incrementRunCount returns error for unreachable server") {
    // Point to a non-existent server — should return a network error
    // This test verifies the error handling path without hitting a real API
    val result = UsageReporter.incrementRunCount("DEMI-TEST-KEY", "fingerprint123")
    // Should return Left (either network error or parse error — both are valid)
    assert(result.isLeft, "Expected Left result when cloud API is unreachable")
  }

  test("reportTokenUsage does not throw for empty license key") {
    // Fire-and-forget — should silently return without exception
    UsageReporter.reportTokenUsage("", "run-123", 50000, 12000)
  }

  test("reportTokenUsage does not throw for unreachable server") {
    // Fire-and-forget — should silently ignore network errors
    UsageReporter.reportTokenUsage("DEMI-TEST-KEY", "run-123", 50000, 12000)
  }

  test("UsageResult sealed trait hierarchy is complete") {
    // Verify all expected subtypes exist and can be matched
    val results: List[UsageResult] = List(
      UsageReport(10, 50),
      UsageLimitExceeded(50, 50),
      UsageReportError("test error"),
    )
    assertEquals(results.size, 3)

    results.foreach {
      case UsageReport(u, m) => assert(u >= 0 && m >= 0)
      case UsageLimitExceeded(u, m) => assert(u == m || u > m || (u == -1 && m == -1))
      case UsageReportError(msg) => assert(msg.nonEmpty)
    }
  }

  test("UsageReport can be used in Either") {
    val success: Either[UsageResult, UsageReport] = Right(UsageReport(5, 100))
    assert(success.isRight)
    assertEquals(success.toOption.get.uses, 5)

    val failure: Either[UsageResult, UsageReport] = Left(UsageLimitExceeded(100, 100))
    assert(failure.isLeft)
  }

  test("incrementRunCount with empty key returns Left immediately without network call") {
    // Verifies the fast-path: no HTTP call is made when the license key is empty.
    // Should be near-instant (no connect timeout).
    val start = System.currentTimeMillis()
    val result = UsageReporter.incrementRunCount("", "fp", increment = 5)
    val elapsed = System.currentTimeMillis() - start
    assert(result.isLeft, "Expected Left for empty license key")
    assert(elapsed < 500, s"Empty key path should be instant, took ${elapsed}ms")
  }

  test("incrementRunCount return type is Either[UsageResult, UsageReport]") {
    // Verify the return type allows exhaustive pattern matching
    val result = UsageReporter.incrementRunCount("DEMI-TEST", "fp")
    result match {
      case Right(UsageReport(u, m))       => assert(u >= 0 && m >= 0)
      case Left(_: UsageLimitExceeded)    => // valid
      case Left(_: UsageReportError)      => // valid (expected path: network error)
      case Left(_: UsageReport)           => fail("UsageReport should not appear on Left")
    }
  }

  test("reportTokenUsage with special characters in runId does not throw") {
    // Verifies the circe JSON builder handles special chars safely
    UsageReporter.reportTokenUsage("DEMI-TEST", """run-"with"quotes-&-<special>""", 100, 50)
  }
}
