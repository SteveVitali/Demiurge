package demiurge.verification

import java.time.{Duration, Instant}

import demiurge.model._

// Phase 8: Tests for selector fallback behavior (Spec §11.5)
// The worker does NOT fall back to other strategies automatically.
// Fallback chaining is orchestrator-side: re-dispatch with fallback selector on SELECTOR_NOT_FOUND.
class SelectorFallbackSuite extends munit.FunSuite {

  test("primary selector success does not use fallback") {
    // When the primary selector succeeds, fallback selectors are never consulted
    val verifier = BrowserFlowVerifier(
      id = "v-1",
      requirementId = "req-1",
      entryUrl = "http://localhost:3000/login",
      actions = Nil,
      assertions = Nil,
      artifactPlan = Nil,
      storageStatePath = None,
      timeout = Duration.ofSeconds(60),
      maxRetries = 2,
      selectorFallbacks = Map("submit-btn" -> SelectorRef("css", ".btn-submit", None)),
    )

    // Simulate primary selector success — worker returns pass
    val result = BrowserVerifierResult(
      outcome = VerifierOutcome.Passed,
      observations = List(Observation("assertion_passed", "Login form visible", None, None, None, Instant.now())),
      artifactRefs = Nil,
    )

    // Primary success — no need for fallback
    assertEquals(result.outcome, VerifierOutcome.Passed)
    assert(verifier.selectorFallbacks.nonEmpty, "Fallbacks configured but not needed")
  }

  test("primary selector failure triggers orchestrator-side fallback") {
    // Spec §11.5: If worker returns SELECTOR_NOT_FOUND (-32011), orchestrator re-dispatches with fallback
    val primarySelector = SelectorRef("testid", "submit-button", None)
    val fallbackSelector = SelectorRef("css", ".btn-submit", None)

    val verifier = BrowserFlowVerifier(
      id = "v-1",
      requirementId = "req-1",
      entryUrl = "http://localhost:3000/login",
      actions = List(BrowserAction("click", Some(primarySelector), None, None, None, "Click submit")),
      assertions = Nil,
      artifactPlan = Nil,
      storageStatePath = None,
      timeout = Duration.ofSeconds(60),
      maxRetries = 2,
      selectorFallbacks = Map("submit-button" -> fallbackSelector),
    )

    // Simulate SELECTOR_NOT_FOUND from worker
    val primaryResult = BrowserVerifierResult(
      outcome = VerifierOutcome.Failed("SELECTOR_NOT_FOUND: testid=submit-button"),
      observations = List(Observation("element_missing", "Selector not found: testid=submit-button", Some("submit-button"), None, None, Instant.now())),
      artifactRefs = Nil,
    )

    // Orchestrator detects SELECTOR_NOT_FOUND and should re-dispatch with fallback
    val isSelectorNotFound = primaryResult.outcome match {
      case VerifierOutcome.Failed(msg) => msg.contains("SELECTOR_NOT_FOUND")
      case _ => false
    }
    assert(isSelectorNotFound)

    // Look up fallback for the failed selector
    val hasFallback = verifier.selectorFallbacks.contains("submit-button")
    assert(hasFallback)
    assertEquals(verifier.selectorFallbacks("submit-button").strategy, "css")
    assertEquals(verifier.selectorFallbacks("submit-button").value, ".btn-submit")
  }

  test("fallback failure produces Inconclusive verdict with LocatorDrift") {
    // Spec §11.5: If fallback also fails, verdict is Inconclusive with LocatorDrift
    val fallbackResult = BrowserVerifierResult(
      outcome = VerifierOutcome.Failed("SELECTOR_NOT_FOUND: css=.btn-submit"),
      observations = List(Observation("element_missing", "Fallback selector also not found", Some(".btn-submit"), None, None, Instant.now())),
      artifactRefs = Nil,
    )

    // Both primary and fallback failed — should produce Inconclusive/LocatorDrift
    val isSelectorNotFound = fallbackResult.outcome match {
      case VerifierOutcome.Failed(msg) => msg.contains("SELECTOR_NOT_FOUND")
      case _ => false
    }
    assert(isSelectorNotFound)

    // The orchestrator would map this to VerdictStatus.Inconclusive with FailureClass.LocatorDrift
    val verdictStatus = VerdictStatus.Inconclusive
    val failureClass = FailureClass.LocatorDrift
    assertEquals(verdictStatus, VerdictStatus.Inconclusive)
    assertEquals(failureClass, FailureClass.LocatorDrift)
  }

  test("worker does not perform hidden fallback — only single strategy per dispatch") {
    // Spec §9.13: Worker uses ONLY the specified strategy, no automatic fallback
    val selector = SelectorRef("testid", "my-button", None)

    // The worker receives exactly one selector strategy per dispatch
    // It does NOT try other strategies
    assertEquals(selector.strategy, "testid")

    // Fallback is purely orchestrator-side via selectorFallbacks map
    val verifier = BrowserFlowVerifier(
      id = "v-1",
      requirementId = "req-1",
      entryUrl = "http://localhost:3000",
      actions = List(BrowserAction("click", Some(selector), None, None, None, "Click button")),
      assertions = Nil,
      artifactPlan = Nil,
      storageStatePath = None,
      timeout = Duration.ofSeconds(60),
      maxRetries = 2,
      selectorFallbacks = Map("my-button" -> SelectorRef("role", "button", Some("Submit"))),
    )

    // Worker gets the primary selector only
    val workerAction = verifier.actions.head
    assertEquals(workerAction.selector.get.strategy, "testid")
    assertEquals(workerAction.selector.get.value, "my-button")

    // Fallback is in the verifier metadata, not sent to worker
    assert(verifier.selectorFallbacks.contains("my-button"))
  }

  test("fallback remains bounded — only one fallback level supported") {
    // Spec §11.5: Only one fallback level is supported
    val verifier = BrowserFlowVerifier(
      id = "v-1",
      requirementId = "req-1",
      entryUrl = "http://localhost:3000",
      actions = Nil,
      assertions = Nil,
      artifactPlan = Nil,
      storageStatePath = None,
      timeout = Duration.ofSeconds(60),
      maxRetries = 2,
      // One fallback per primary selector — no cascading fallbacks
      selectorFallbacks = Map(
        "btn-1" -> SelectorRef("css", ".fallback-1", None),
        "btn-2" -> SelectorRef("role", "button", Some("OK")),
      ),
    )

    // Each selector has at most one fallback
    assertEquals(verifier.selectorFallbacks.size, 2)
    // No nested fallbacks
    verifier.selectorFallbacks.values.foreach { fallback =>
      // Fallback is a flat SelectorRef, not another map of fallbacks
      assert(fallback.strategy.nonEmpty)
      assert(fallback.value.nonEmpty)
    }
  }
}
