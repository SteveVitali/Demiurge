package lastmile.inference

import lastmile.model._

// Pluggable backend for actual LLM API calls.
// Implementations: MockInferenceBackend (testing), AnthropicBackend (real API).
trait InferenceBackend {
  def call(request: InferenceRequest): Either[InferenceError, InferenceResponse]
}

// Mock backend for testing — returns configurable responses
class MockInferenceBackend(
  responses: Map[String, Either[InferenceError, InferenceResponse]] = Map.empty,
  defaultResponse: Option[Either[InferenceError, InferenceResponse]] = None,
) extends InferenceBackend {

  private val callLog = scala.collection.mutable.ListBuffer.empty[InferenceRequest]

  override def call(request: InferenceRequest): Either[InferenceError, InferenceResponse] = {
    callLog += request
    responses.getOrElse(request.component, defaultResponse.getOrElse(
      Right(InferenceResponse(
        requestId = request.requestId,
        responseText = s"Mock response for ${request.component}",
        parsedJson = request.responseFormat.map(_ => """{"result": "mock"}"""),
        inputTokens = 100,
        outputTokens = 50,
        cachedHit = false,
        durationMs = 10,
        model = request.model,
        provider = request.provider,
      ))
    ))
  }

  def calls: List[InferenceRequest] = callLog.toList
}
