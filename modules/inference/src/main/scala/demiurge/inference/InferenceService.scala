package demiurge.inference

import demiurge.model._

// Spec §5.3: InferenceService interface.
// Every LLM call must go through this interface for budget, timeout, retry, caching, and auditing.
trait InferenceService {
  /** Execute an inference request with budget/timeout/cache enforcement. */
  def infer(request: InferenceRequest): Either[InferenceError, InferenceResponse]

  /** Get remaining budget for a component within a run. */
  def remainingBudget(runId: String, component: String, attemptNumber: Option[Int] = None): InferenceBudgetStatus

  /** Get all usage records for a run. */
  def getUsage(runId: String): List[UsageRecord]
}
