package demiurge.verification

import java.time.Instant

import munit.FunSuite
import demiurge.model._
import demiurge.inference.{MockInferenceBackend, InferenceServiceImpl, InferenceBudgetState, InMemoryInferenceCache}

class SelectorDiscoverySuite extends FunSuite {

  private def fakeConfig(): ResolvedConfig =
    ResolvedConfig(
      app = ResolvedAppConfig("fullstack", "http://localhost:3000", None),
      services = Nil,
      fixtures = None,
      auth = None,
      verification = ResolvedVerificationConfig(30000, 15000, 1, 1000, false, false, false),
      inference = ResolvedInferenceConfig(InferenceProvider.Mock, Map.empty),
      policies = ResolvedPoliciesConfig(5, 3600000L, 900000L, 2000, 536870912L,
        List("localhost"), List("http://localhost:*"), false, false),
      observability = None,
      provenance = ConfigProvenance(ConfigSource.Inferred, Map.empty, Map.empty, Instant.now()),
    )

  private def specWithMissingSelectors(): BrowserFlowVerifierSpec =
    BrowserFlowVerifierSpec(
      entryUrl = "http://localhost:3000/login",
      selectorMapRef = None,
      entryConditions = Nil,
      actions = List(
        BrowserAction("fill", None, Some("test@example.com"), None, None, "Fill email field"),
        BrowserAction("fill", None, Some("password123"), None, None, "Fill password field"),
        BrowserAction("click", None, None, None, None, "Click login button"),
      ),
      assertions = List(
        Assertion("visible", None, Some("/dashboard"), None, None, "Redirected to dashboard"),
      ),
      artifactPlan = Nil,
      cleanup = Nil,
    )

  private def specWithSelectors(): BrowserFlowVerifierSpec =
    BrowserFlowVerifierSpec(
      entryUrl = "http://localhost:3000/login",
      selectorMapRef = None,
      entryConditions = Nil,
      actions = List(
        BrowserAction("fill", Some(SelectorRef("css", "input[name='email']", None)), Some("test@example.com"), None, None, "Fill email"),
      ),
      assertions = List(
        Assertion("visible", Some(SelectorRef("css", ".dashboard", None)), None, None, None, "Dashboard visible"),
      ),
      artifactPlan = Nil,
      cleanup = Nil,
    )

  test("needsDiscovery returns true when actions have missing selectors") {
    assert(SelectorDiscovery.needsDiscovery(specWithMissingSelectors()))
  }

  test("needsDiscovery returns false when all selectors present") {
    assert(!SelectorDiscovery.needsDiscovery(specWithSelectors()))
  }

  test("discoverSelectors returns spec unchanged when no selectors missing") {
    val spec = specWithSelectors()
    val mockBackend = new MockInferenceBackend()
    val svc = new InferenceServiceImpl(mockBackend, new InferenceBudgetState(), new InMemoryInferenceCache())

    val result = SelectorDiscovery.discoverSelectors(spec, "<html></html>", "run-1", fakeConfig(), svc)
    assertEquals(result, spec)
  }

  test("discoverSelectors fills in selectors from LLM response") {
    val llmResponse = InferenceResponse(
      requestId = "req-1",
      responseText = """{"selectors": [
        {"index": 0, "type": "action", "strategy": "css", "value": "input[name='email']"},
        {"index": 1, "type": "action", "strategy": "css", "value": "input[name='password']"},
        {"index": 2, "type": "action", "strategy": "test-id", "value": "[data-testid='login-btn']"},
        {"index": 0, "type": "assertion", "strategy": "css", "value": ".dashboard-container"}
      ]}""",
      parsedJson = Some("""{"selectors": [
        {"index": 0, "type": "action", "strategy": "css", "value": "input[name='email']"},
        {"index": 1, "type": "action", "strategy": "css", "value": "input[name='password']"},
        {"index": 2, "type": "action", "strategy": "test-id", "value": "[data-testid='login-btn']"},
        {"index": 0, "type": "assertion", "strategy": "css", "value": ".dashboard-container"}
      ]}"""),
      inputTokens = 200,
      outputTokens = 100,
      cachedHit = false,
      durationMs = 500,
      model = "test-model",
      provider = InferenceProvider.Mock,
    )

    val mockBackend = new MockInferenceBackend(defaultResponse = Some(Right(llmResponse)))
    val svc = new InferenceServiceImpl(mockBackend, new InferenceBudgetState(), new InMemoryInferenceCache())

    val spec = specWithMissingSelectors()
    val result = SelectorDiscovery.discoverSelectors(spec, "<html><input name='email'/></html>", "run-1", fakeConfig(), svc)

    assert(result.actions(0).selector.isDefined, "First action should have selector")
    assertEquals(result.actions(0).selector.get.value, "input[name='email']")
    assert(result.actions(2).selector.isDefined, "Third action should have selector")
    assertEquals(result.actions(2).selector.get.strategy, "test-id")
    assert(result.assertions(0).selector.isDefined, "First assertion should have selector")
  }

  test("discoverSelectors returns original spec when LLM fails") {
    val mockBackend = new MockInferenceBackend(
      defaultResponse = Some(Left(InferenceError.Timeout("req-1", 30000))),
    )
    val svc = new InferenceServiceImpl(mockBackend, new InferenceBudgetState(), new InMemoryInferenceCache())

    val spec = specWithMissingSelectors()
    val result = SelectorDiscovery.discoverSelectors(spec, "<html></html>", "run-1", fakeConfig(), svc)

    // Should return original unchanged
    assertEquals(result.actions(0).selector, None)
  }

  test("parseSelectorResponse handles empty response gracefully") {
    val response = InferenceResponse(
      requestId = "req-1",
      responseText = "{}",
      parsedJson = Some("{}"),
      inputTokens = 10,
      outputTokens = 5,
      cachedHit = false,
      durationMs = 100,
      model = "test",
      provider = InferenceProvider.Mock,
    )
    val spec = specWithMissingSelectors()
    val result = SelectorDiscovery.parseSelectorResponse(spec, response)
    // Should return Some(spec) with no changes since no selectors in response
    assert(result.isDefined)
  }
}
