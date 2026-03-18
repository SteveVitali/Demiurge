package demiurge.inference

import demiurge.model._

// Spec §5.5: Budget limits per component per run.
// Tracks token usage and request counts, enforces ceilings.
object InferenceBudgetTracker {

  // Spec §5.5: Default budget limits per component
  case class ComponentBudget(
    maxTokensPerRun: Long,
    maxRequestsPerRun: Int,
    perAttempt: Boolean = false, // if true, budgets reset per attempt
  )

  val defaultBudgets: Map[String, ComponentBudget] = Map(
    "requirement_compiler"    -> ComponentBudget(100000L, 5),
    "requirement_generator"   -> ComponentBudget(100000L, 5),
    "verifier_generator"      -> ComponentBudget(200000L, 20),
    "failure_analyzer"        -> ComponentBudget(100000L, 5, perAttempt = true),
    "impact_analysis"         -> ComponentBudget(50000L, 3),
    "exploratory_verifier"    -> ComponentBudget(100000L, 10, perAttempt = true),
  )

  // Spec §5.2: Only these components may call inference
  val allowedCallers: Set[String] = Set(
    "requirement_compiler",
    "requirement_generator",
    "verifier_generator",
    "failure_analyzer",
    "impact_analysis",
    "exploratory_verifier",
  )
}

// Mutable budget state for a single run. Thread-safe via synchronized access.
class InferenceBudgetState {
  import java.util.concurrent.ConcurrentHashMap

  // key -> (usedTokens, usedRequests)
  private val usage = new ConcurrentHashMap[String, (Long, Int)]()

  private def key(runId: String, component: String, attemptNumber: Option[Int]): String = {
    val budget = InferenceBudgetTracker.defaultBudgets.get(component)
    if (budget.exists(_.perAttempt)) {
      s"$runId:$component:${attemptNumber.getOrElse(0)}"
    } else {
      s"$runId:$component"
    }
  }

  def recordUsage(runId: String, component: String, attemptNumber: Option[Int], tokens: Long): Unit = {
    val k = key(runId, component, attemptNumber)
    usage.compute(k, (_, existing) => {
      val (existingTokens, existingCount) = if (existing == null) (0L, 0) else existing
      (existingTokens + tokens, existingCount + 1)
    })
  }

  def getStatus(runId: String, component: String, attemptNumber: Option[Int] = None): InferenceBudgetStatus = {
    val k = key(runId, component, attemptNumber)
    val budget = InferenceBudgetTracker.defaultBudgets.getOrElse(
      component, InferenceBudgetTracker.ComponentBudget(100000L, 10))
    val entry = usage.get(k)
    val (usedTokens, usedRequests) = if (entry == null) (0L, 0) else entry
    InferenceBudgetStatus(
      component = component,
      maxTokensPerRun = budget.maxTokensPerRun,
      usedTokens = usedTokens,
      remainingTokens = math.max(0, budget.maxTokensPerRun - usedTokens),
      maxRequestsPerRun = budget.maxRequestsPerRun,
      usedRequests = usedRequests,
    )
  }

  def checkBudget(runId: String, component: String, attemptNumber: Option[Int]): Option[InferenceError] = {
    val status = getStatus(runId, component, attemptNumber)
    if (status.remainingTokens <= 0 || status.usedRequests >= status.maxRequestsPerRun) {
      Some(InferenceError.BudgetExceeded(
        requestId = "",
        component = component,
        remainingTokens = status.remainingTokens,
        requestedTokens = 0,
      ))
    } else {
      None
    }
  }
}
