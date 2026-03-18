package demiurge.agent

import java.nio.file.Path

import demiurge.repair.RepairContext
import demiurge.worker.WorkerProcessManager

// Design §10: ClaudeAgentBackend — concrete implementation of AgentBackend
// that delegates to the Claude Agent SDK via the TypeScript worker process.
class ClaudeAgentBackend(
  workerManager: WorkerProcessManager,
  repoRoot: Path,
) extends AgentBackend {

  override val backendId: String = "claude-agent-sdk"

  override def executeRepair(context: RepairContext, config: AgentConfig): AgentResult = {
    AgentExecutor.execute(workerManager, context, config, repoRoot)
  }
}
