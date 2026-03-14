package demiurge.model

import java.time.Instant

// Spec §3.2: UsageRecord
case class UsageRecord(
  usageRecordId:      String,
  runId:              String,
  attemptNumber:      Option[Int],
  component:          String,
  provider:           InferenceProvider,
  model:              String,
  inputTokens:        Long,
  outputTokens:       Long,
  totalTokens:        Long,
  durationMs:         Long,
  estimatedCostUsd:   Option[Double],
  requestCount:       Int,
  cachedTokens:       Long,
  createdAt:          Instant,
)
