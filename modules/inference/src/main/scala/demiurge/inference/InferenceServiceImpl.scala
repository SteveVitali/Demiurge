package demiurge.inference

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

import demiurge.model._

// Spec §5: InferenceService implementation with budget, cache, timeout, replay, auditing.
// Uses an InferenceBackend trait for actual LLM API calls (pluggable for mock/real).
class InferenceServiceImpl(
  backend: InferenceBackend,
  budgetState: InferenceBudgetState,
  cache: InferenceCache,
  replayMode: Boolean = false,
) extends InferenceService {

  private val usageRecords = scala.collection.mutable.ListBuffer.empty[UsageRecord]

  // Spec §5.3: infer with budget check, cache, timeout, retry, auditing
  override def infer(request: InferenceRequest): Either[InferenceError, InferenceResponse] = {
    // Spec §5.2: Validate caller is allowed
    if (!InferenceBudgetTracker.allowedCallers.contains(request.component)) {
      return Left(InferenceError.ProviderError(
        request.requestId, 403, s"Component '${request.component}' is not allowed to call inference"))
    }

    // Spec §5.5: Check budget before calling
    budgetState.checkBudget(request.runId, request.component, request.attemptNumber) match {
      case Some(err) => return Left(err.asInstanceOf[InferenceError.BudgetExceeded].copy(requestId = request.requestId))
      case None => // budget ok
    }

    // Spec §5.7: Check cache if cacheable
    if (request.cacheable) {
      val cacheKey = computeCacheKey(request)
      cache.get(cacheKey) match {
        case Some(cached) =>
          val response = cached.copy(requestId = request.requestId, cachedHit = true)
          // Record usage for cached hit (Spec §5.9)
          budgetState.recordUsage(request.runId, request.component, request.attemptNumber, cached.inputTokens + cached.outputTokens)
          recordUsage(request, response)
          return Right(response)
        case None => // cache miss
      }
    }

    // Spec §5.8: Replay mode — serve from cache only
    if (replayMode) {
      val cacheKey = computeCacheKey(request)
      cache.get(cacheKey) match {
        case Some(cached) =>
          val response = cached.copy(requestId = request.requestId, cachedHit = true)
          budgetState.recordUsage(request.runId, request.component, request.attemptNumber, cached.inputTokens + cached.outputTokens)
          recordUsage(request, response)
          return Right(response)
        case None =>
          return Left(InferenceError.ProviderError(
            request.requestId, 0, "Replay mode: cache miss, no live API calls allowed"))
      }
    }

    // Spec §5.6: Execute with timeout and retry (max 1 retry per call)
    val maxRetries = 1
    var lastError: Option[InferenceError] = None

    for (attempt <- 0 to maxRetries) {
      if (attempt > 0) {
        // Spec §5.10: 2s backoff before retry
        Thread.sleep(2000)
      }

      val startMs = System.currentTimeMillis()
      val result = try {
        backend.call(request)
      } catch {
        case _: Exception =>
          Left(InferenceError.Timeout(request.requestId, System.currentTimeMillis() - startMs))
      }

      result match {
        case Right(response) =>
          // Record usage
          val totalTokens = response.inputTokens + response.outputTokens
          budgetState.recordUsage(request.runId, request.component, request.attemptNumber, totalTokens)
          recordUsage(request, response)

          // Cache if cacheable (Spec §5.7)
          if (request.cacheable) {
            val cacheKey = computeCacheKey(request)
            cache.put(cacheKey, response)
          }

          return Right(response)

        case Left(err) =>
          lastError = Some(err)
          // Only retry on Timeout or RateLimited
          err match {
            case _: InferenceError.Timeout => // retryable
            case _: InferenceError.RateLimited => // retryable
            case _ => return Left(err) // non-retryable
          }
      }
    }

    // Spec §5.10: All retries exhausted — record failed usage and return error
    val err = lastError.getOrElse(InferenceError.Timeout(request.requestId, request.timeoutMs))
    recordFailedUsage(request)
    Left(err)
  }

  override def remainingBudget(runId: String, component: String, attemptNumber: Option[Int] = None): InferenceBudgetStatus = {
    budgetState.getStatus(runId, component, attemptNumber)
  }

  override def getUsage(runId: String): List[UsageRecord] = {
    usageRecords.filter(_.runId == runId).toList
  }

  // Spec §5.7: Cache key = SHA-256(provider + model + systemPrompt + userPrompt + responseFormat)
  private def computeCacheKey(request: InferenceRequest): String = {
    val input = s"${request.provider}|${request.model}|${request.systemPrompt}|${request.userPrompt}|${request.responseFormat.getOrElse("")}"
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(input.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  // Spec §5.9: Record usage for successful inference
  private def recordUsage(request: InferenceRequest, response: InferenceResponse): Unit = {
    val record = UsageRecord(
      usageRecordId = UUID.randomUUID().toString,
      runId = request.runId,
      attemptNumber = request.attemptNumber,
      component = request.component,
      provider = response.provider,
      model = response.model,
      inputTokens = response.inputTokens,
      outputTokens = response.outputTokens,
      totalTokens = response.inputTokens + response.outputTokens,
      durationMs = response.durationMs,
      estimatedCostUsd = None,
      requestCount = 1,
      cachedTokens = if (response.cachedHit) response.inputTokens + response.outputTokens else 0L,
      createdAt = Instant.now(),
    )
    usageRecords += record
  }

  // Record failed usage (Spec §5.10 step 2)
  private def recordFailedUsage(request: InferenceRequest): Unit = {
    val record = UsageRecord(
      usageRecordId = UUID.randomUUID().toString,
      runId = request.runId,
      attemptNumber = request.attemptNumber,
      component = request.component,
      provider = request.provider,
      model = request.model,
      inputTokens = 0L,
      outputTokens = 0L,
      totalTokens = 0L,
      durationMs = 0L,
      estimatedCostUsd = None,
      requestCount = 1,
      cachedTokens = 0L,
      createdAt = Instant.now(),
    )
    usageRecords += record
  }
}
