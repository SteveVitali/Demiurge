package demiurge.agent

// Design §8.2: Agent result ADT — replaces RepairOutcome for agent-backed repairs.
sealed trait AgentResult

case class AgentCompleted(
  sessionId: String,
  filesChanged: List[String],       // detected via git diff in worktree
  agentVerified: Boolean,           // did the agent's own verify_requirements() pass?
  inputTokens: Long,
  outputTokens: Long,
  costUsd: Double,
  numTurns: Int,
  durationMs: Long,
  summary: String,                  // agent's own summary of what it did
  toolUseLog: List[ToolUseEntry] = Nil,
) extends AgentResult

case class AgentFailed(
  reason: String,
  inputTokens: Long = 0,
  outputTokens: Long = 0,
  costUsd: Double = 0.0,
  durationMs: Long = 0,
) extends AgentResult

case class AgentTimeout(
  timeoutMs: Long,
  inputTokens: Long = 0,
  outputTokens: Long = 0,
  costUsd: Double = 0.0,
  partialSummary: String = "",
) extends AgentResult

case class AgentBudgetExceeded(
  maxBudgetUsd: Double,
  actualCostUsd: Double,
  inputTokens: Long = 0,
  outputTokens: Long = 0,
  partialSummary: String = "",
) extends AgentResult

case class ToolUseEntry(
  toolName: String,
  timestamp: String,
  inputSummary: String,
)
