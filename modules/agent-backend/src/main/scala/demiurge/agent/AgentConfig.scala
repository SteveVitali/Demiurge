package demiurge.agent

// Design §10.2: Configuration DTOs for the agent backend.
case class AgentConfig(
  model: Option[String] = None,                // override default model
  maxTurns: Option[Int] = None,                // limit agent turns
  maxBudgetUsd: Option[Double] = None,         // native SDK budget limit per session
  timeoutMs: Long = 300000,                    // 5 min default per attempt
  enableMcpTools: Boolean = true,              // expose Demiurge MCP tools
  enableBrowserTools: Boolean = false,         // expose Playwright MCP browser tools
  headedBrowser: Boolean = false,              // launch browser in headed (visible) mode
  sessionId: Option[String] = None,            // for session resume
  resume: Boolean = false,                     // continue from previous session
  pathToClaudeCodeExecutable: Option[String] = None, // override bundled CLI path
)

object AgentConfig {
  val Default: AgentConfig = AgentConfig()

  /** Build config from environment variables and defaults. */
  def fromEnvironment(overrides: AgentConfig = Default): AgentConfig = {
    val timeoutMs = Option(System.getenv("DEMIURGE_AGENT_TIMEOUT_MS"))
      .flatMap(s => scala.util.Try(s.toLong).toOption)
      .getOrElse(overrides.timeoutMs)

    val maxTurns = Option(System.getenv("DEMIURGE_AGENT_MAX_TURNS"))
      .flatMap(s => scala.util.Try(s.toInt).toOption)
      .orElse(overrides.maxTurns)

    val maxBudgetUsd = Option(System.getenv("DEMIURGE_AGENT_MAX_BUDGET_USD"))
      .flatMap(s => scala.util.Try(s.toDouble).toOption)
      .orElse(overrides.maxBudgetUsd)

    val claudePath = Option(System.getenv("CLAUDE_CODE_EXECUTABLE"))
      .orElse(overrides.pathToClaudeCodeExecutable)
      .orElse(detectClaudeExecutable())

    overrides.copy(
      timeoutMs = timeoutMs,
      maxTurns = maxTurns,
      maxBudgetUsd = maxBudgetUsd,
      pathToClaudeCodeExecutable = claudePath,
    )
  }

  /** Auto-detect the claude CLI binary via `which claude`. */
  private def detectClaudeExecutable(): Option[String] = {
    try {
      val path = scala.sys.process.Process(Seq("which", "claude")).!!.trim
      if (path.nonEmpty) Some(path) else None
    } catch {
      case _: Exception => None
    }
  }
}
