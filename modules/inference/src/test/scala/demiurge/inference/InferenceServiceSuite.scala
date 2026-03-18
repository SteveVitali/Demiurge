package demiurge.inference

import java.util.UUID
import demiurge.model._

class InferenceServiceSuite extends munit.FunSuite {

  private def mkRequest(
    component: String = "failure_analyzer",
    cacheable: Boolean = false,
    runId: String = "run-1",
    attemptNumber: Option[Int] = Some(1),
  ): InferenceRequest = InferenceRequest(
    requestId = UUID.randomUUID().toString,
    runId = runId,
    attemptNumber = attemptNumber,
    component = component,
    provider = InferenceProvider.Mock,
    model = "claude-sonnet-4-20250514",
    systemPrompt = "You are a test assistant",
    userPrompt = "Analyze this failure",
    responseFormat = Some("json"),
    jsonSchema = None,
    maxOutputTokens = 4096,
    temperature = 0.0,
    cacheable = cacheable,
    timeoutMs = 120000L,
    metadata = Map.empty,
  )

  test("successful inference call returns response and records usage") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val backend = new MockInferenceBackend()
    val service = new InferenceServiceImpl(backend, budget, cache)

    val result = service.infer(mkRequest())
    assert(result.isRight)
    val response = result.toOption.get
    assertEquals(response.provider, InferenceProvider.Mock)
    assert(response.inputTokens > 0)

    // Usage recorded
    val usage = service.getUsage("run-1")
    assertEquals(usage.size, 1)
    assertEquals(usage.head.component, "failure_analyzer")
  }

  test("disallowed caller is rejected") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val backend = new MockInferenceBackend()
    val service = new InferenceServiceImpl(backend, budget, cache)

    val result = service.infer(mkRequest(component = "unauthorized_component"))
    assert(result.isLeft)
    result.left.foreach {
      case InferenceError.ProviderError(_, code, _) => assertEquals(code, 403)
      case other => fail(s"Unexpected error: $other")
    }
  }

  test("budget exceeded returns BudgetExceeded error") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val backend = new MockInferenceBackend()
    val service = new InferenceServiceImpl(backend, budget, cache)

    // Exhaust budget by recording enough usage
    for (_ <- 1 to 6) {
      budget.recordUsage("run-1", "failure_analyzer", Some(1), 20000L)
    }

    val result = service.infer(mkRequest())
    assert(result.isLeft)
    result.left.foreach {
      case _: InferenceError.BudgetExceeded => // expected
      case other => fail(s"Unexpected error: $other")
    }
  }

  test("cache hit returns cached response without calling backend") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val backend = new MockInferenceBackend()
    val service = new InferenceServiceImpl(backend, budget, cache)

    // First call — populates cache
    val req = mkRequest(cacheable = true)
    val result1 = service.infer(req)
    assert(result1.isRight)
    assertEquals(backend.calls.size, 1)

    // Second call with same params — should hit cache
    val req2 = req.copy(requestId = UUID.randomUUID().toString)
    val result2 = service.infer(req2)
    assert(result2.isRight)
    assert(result2.toOption.get.cachedHit)
    // Backend should NOT have been called again
    assertEquals(backend.calls.size, 1)
  }

  test("replay mode returns cache miss error when no cache entry") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val backend = new MockInferenceBackend()
    val service = new InferenceServiceImpl(backend, budget, cache, replayMode = true)

    val result = service.infer(mkRequest())
    assert(result.isLeft)
    result.left.foreach {
      case InferenceError.ProviderError(_, _, msg) =>
        assert(msg.contains("Replay mode"))
      case other => fail(s"Unexpected error: $other")
    }
  }

  test("replay mode serves from cache when entry exists") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val backend = new MockInferenceBackend()

    // Pre-populate cache
    val service1 = new InferenceServiceImpl(backend, budget, cache, replayMode = false)
    val req = mkRequest(cacheable = true)
    service1.infer(req)

    // Now replay mode
    val service2 = new InferenceServiceImpl(backend, budget, cache, replayMode = true)
    val req2 = req.copy(requestId = UUID.randomUUID().toString)
    val result = service2.infer(req2)
    assert(result.isRight)
    assert(result.toOption.get.cachedHit)
  }

  test("backend error is returned correctly") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val err = InferenceError.ProviderError("req-1", 500, "Internal server error")
    val backend = new MockInferenceBackend(
      responses = Map("failure_analyzer" -> Left(err))
    )
    val service = new InferenceServiceImpl(backend, budget, cache)

    val result = service.infer(mkRequest())
    assert(result.isLeft)
    result.left.foreach {
      case InferenceError.ProviderError(_, code, _) => assertEquals(code, 500)
      case other => fail(s"Unexpected error: $other")
    }
  }

  test("budget status tracks tokens correctly") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val backend = new MockInferenceBackend()
    val service = new InferenceServiceImpl(backend, budget, cache)

    service.infer(mkRequest())
    // failure_analyzer is per-attempt, so pass attemptNumber to get correct budget status
    val status = service.remainingBudget("run-1", "failure_analyzer", Some(1))
    assertEquals(status.component, "failure_analyzer")
    assert(status.usedTokens > 0)
    assert(status.remainingTokens < status.maxTokensPerRun)
    assertEquals(status.usedRequests, 1)
  }

  test("request count budget enforced") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val backend = new MockInferenceBackend()
    val service = new InferenceServiceImpl(backend, budget, cache)

    // failure_analyzer has maxRequestsPerRun=5
    for (_ <- 1 to 5) {
      val result = service.infer(mkRequest())
      assert(result.isRight)
    }
    // 6th call should fail
    val result = service.infer(mkRequest())
    assert(result.isLeft)
    result.left.foreach {
      case _: InferenceError.BudgetExceeded => // expected
      case other => fail(s"Unexpected error: $other")
    }
  }
}
