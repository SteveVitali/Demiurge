package demiurge.model

// Spec §3.2: InferenceRequest
case class InferenceRequest(
  requestId:          String,
  runId:              String,
  attemptNumber:      Option[Int],
  component:          String,
  provider:           InferenceProvider,
  model:              String,
  systemPrompt:       String,
  userPrompt:         String,
  responseFormat:     Option[String],
  jsonSchema:         Option[String],
  maxOutputTokens:    Int,
  temperature:        Double,
  cacheable:          Boolean,
  timeoutMs:          Long,
  metadata:           Map[String, String],
)

// Spec §3.2: InferenceResponse
case class InferenceResponse(
  requestId:          String,
  responseText:       String,
  parsedJson:         Option[String],
  inputTokens:        Long,
  outputTokens:       Long,
  cachedHit:          Boolean,
  durationMs:         Long,
  model:              String,
  provider:           InferenceProvider,
)

// Spec §3.2: InferenceBudgetStatus
case class InferenceBudgetStatus(
  component:          String,
  maxTokensPerRun:    Long,
  usedTokens:         Long,
  remainingTokens:    Long,
  maxRequestsPerRun:  Int,
  usedRequests:       Int,
)
