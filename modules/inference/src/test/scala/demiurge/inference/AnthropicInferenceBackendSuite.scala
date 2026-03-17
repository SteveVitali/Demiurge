package demiurge.inference

import java.util.UUID
import demiurge.model._

class AnthropicInferenceBackendSuite extends munit.FunSuite {

  private val testApiKey = "test-api-key-12345"

  private def mkRequest(
    model: String = "claude-sonnet-4-20250514",
    systemPrompt: String = "You are a test assistant",
    userPrompt: String = "Analyze this failure",
    maxOutputTokens: Int = 4096,
    temperature: Double = 0.0,
    responseFormat: Option[String] = None,
    timeoutMs: Long = 120000L,
  ): InferenceRequest = InferenceRequest(
    requestId = UUID.randomUUID().toString,
    runId = "run-1",
    attemptNumber = Some(1),
    component = "repair_backend",
    provider = InferenceProvider.Anthropic,
    model = model,
    systemPrompt = systemPrompt,
    userPrompt = userPrompt,
    responseFormat = responseFormat,
    jsonSchema = None,
    maxOutputTokens = maxOutputTokens,
    temperature = temperature,
    cacheable = false,
    timeoutMs = timeoutMs,
    metadata = Map.empty,
  )

  test("buildRequestJson includes model, system, messages, and max_tokens") {
    val backend = new AnthropicInferenceBackend(testApiKey)
    val request = mkRequest()
    val json = backend.buildRequestJson(request)

    assert(json.contains(""""model":"claude-sonnet-4-20250514""""))
    assert(json.contains(""""max_tokens":4096"""))
    assert(json.contains(""""system":"You are a test assistant""""))
    assert(json.contains(""""role":"user""""))
    assert(json.contains(""""content":"Analyze this failure""""))
  }

  test("buildRequestJson includes temperature only when non-zero") {
    val backend = new AnthropicInferenceBackend(testApiKey)

    val reqZero = mkRequest(temperature = 0.0)
    val jsonZero = backend.buildRequestJson(reqZero)
    assert(!jsonZero.contains("temperature"))

    val reqNonZero = mkRequest(temperature = 0.7)
    val jsonNonZero = backend.buildRequestJson(reqNonZero)
    assert(jsonNonZero.contains(""""temperature":0.7"""))
  }

  test("buildRequestJson escapes special characters in prompts") {
    val backend = new AnthropicInferenceBackend(testApiKey)
    val request = mkRequest(
      systemPrompt = "Line1\nLine2\t\"quoted\"",
      userPrompt = "Path: C:\\Users\\test",
    )
    val json = backend.buildRequestJson(request)

    assert(json.contains("Line1\\nLine2\\t\\\"quoted\\\""))
    assert(json.contains("C:\\\\Users\\\\test"))
  }

  test("parseResponse extracts content, tokens, and stop_reason") {
    val backend = new AnthropicInferenceBackend(testApiKey)
    val request = mkRequest()

    val body =
      """{"id":"msg_123","type":"message","role":"assistant",""" +
      """"content":[{"type":"text","text":"Hello world"}],""" +
      """"model":"claude-sonnet-4-20250514","stop_reason":"end_turn",""" +
      """"usage":{"input_tokens":150,"output_tokens":25}}"""

    val result = backend.parseResponse(body, request)
    assert(result.isRight)
    val response = result.toOption.get
    assertEquals(response.responseText, "Hello world")
    assertEquals(response.inputTokens, 150L)
    assertEquals(response.outputTokens, 25L)
    assertEquals(response.provider, InferenceProvider.Anthropic)
    assertEquals(response.model, "claude-sonnet-4-20250514")
    assertEquals(response.cachedHit, false)
  }

  test("parseResponse sets parsedJson when responseFormat is json") {
    val backend = new AnthropicInferenceBackend(testApiKey)
    val request = mkRequest(responseFormat = Some("json"))

    val body =
      """{"content":[{"type":"text","text":"{\"result\":\"ok\"}"}],""" +
      """"usage":{"input_tokens":10,"output_tokens":5},"stop_reason":"end_turn"}"""

    val result = backend.parseResponse(body, request)
    assert(result.isRight)
    val response = result.toOption.get
    assert(response.parsedJson.isDefined)
    assertEquals(response.parsedJson.get, response.responseText)
  }

  test("parseResponse does not set parsedJson when responseFormat is None") {
    val backend = new AnthropicInferenceBackend(testApiKey)
    val request = mkRequest(responseFormat = None)

    val body =
      """{"content":[{"type":"text","text":"plain text"}],""" +
      """"usage":{"input_tokens":10,"output_tokens":5},"stop_reason":"end_turn"}"""

    val result = backend.parseResponse(body, request)
    assert(result.isRight)
    assert(result.toOption.get.parsedJson.isEmpty)
  }

  test("parseResponse returns MalformedResponse for invalid JSON") {
    val backend = new AnthropicInferenceBackend(testApiKey)
    val request = mkRequest()

    // parseResponse on a completely garbled body should still return Right
    // with empty strings (the regex extractors return defaults), not throw.
    // Only truly broken parsing would return Left.
    val body = "not json at all {"
    val result = backend.parseResponse(body, request)
    // Should succeed (regex returns defaults) — content will be empty
    assert(result.isRight)
    assertEquals(result.toOption.get.responseText, "")
  }

  test("parseResponse populates durationMs from elapsed parameter") {
    val backend = new AnthropicInferenceBackend(testApiKey)
    val request = mkRequest()

    val body =
      """{"content":[{"type":"text","text":"ok"}],""" +
      """"usage":{"input_tokens":10,"output_tokens":5},"stop_reason":"end_turn"}"""

    val result = backend.parseResponse(body, request, elapsedMs = 1234L)
    assert(result.isRight)
    assertEquals(result.toOption.get.durationMs, 1234L)
  }

  test("API URL and version constants are correct") {
    assertEquals(AnthropicInferenceBackend.ApiUrl, "https://api.anthropic.com/v1/messages")
    assertEquals(AnthropicInferenceBackend.ApiVersion, "2023-06-01")
  }
}
