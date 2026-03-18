package demiurge.agent

import demiurge.repair.RepairContext

// Design §10: AgentBackend trait — the interface for agent-backed repair and build.
// Replaces RepairBackend.proposePatch() with an agentic multi-turn execution model.
// The agent reads files, edits code, runs commands, and calls MCP tools directly.
trait AgentBackend {

  /** Execute a repair session: agent reads code, identifies root cause, fixes, verifies. */
  def executeRepair(context: RepairContext, config: AgentConfig): AgentResult

  /** Execute a build session: agent implements a feature from scratch. */
  def executeBuild(context: RepairContext, config: AgentConfig): AgentResult =
    executeRepair(context, config) // default: same flow, mode is in context

  /** Backend identifier string. */
  def backendId: String
}
