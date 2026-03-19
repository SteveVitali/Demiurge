package demiurge.agent

import java.nio.file.Path

import io.circe._
import io.circe.syntax._

import demiurge.repair.RepairContext
import demiurge.worker.WorkerProcessManager

import scala.sys.process._

// Design §10, §5: AgentExecutor — invokes the agent via the worker's `agent/execute` JSON-RPC method.
// Bridges the Scala orchestrator with the TypeScript worker that hosts the Claude Agent SDK.
object AgentExecutor {

  /**
   * Execute an agent session via the worker process.
   * Sends agent/execute JSON-RPC, waits for completion, parses the result.
   */
  def execute(
    workerManager: WorkerProcessManager,
    context: RepairContext,
    config: AgentConfig,
    repoRoot: Path,
  ): AgentResult = {
    val systemPrompt = AgentSystemPromptBuilder.buildSystemPrompt(context, config.enableBrowserTools)
    val userPrompt = AgentSystemPromptBuilder.buildUserPrompt(context)

    // Design §5.2: Build JSON-RPC params for agent/execute
    val params = Json.obj(
      "runId"        -> context.runId.asJson,
      "systemPrompt" -> systemPrompt.asJson,
      "userPrompt"   -> userPrompt.asJson,
      "worktreePath" -> context.worktreePath.toAbsolutePath.toString.asJson,
      "repoRoot"     -> repoRoot.toAbsolutePath.toString.asJson,
      "serviceIds"   -> Json.arr(), // populated from runtime plan if available
      "agentConfig"  -> Json.obj(
        "model"          -> config.model.asJson,
        "maxTurns"       -> config.maxTurns.asJson,
        "maxBudgetUsd"   -> config.maxBudgetUsd.asJson,
        "timeoutMs"      -> config.timeoutMs.asJson,
        "enableMcpTools" -> config.enableMcpTools.asJson,
        "enableBrowserTools" -> config.enableBrowserTools.asJson,
        "headedBrowser"  -> config.headedBrowser.asJson,
        "sessionId"      -> config.sessionId.asJson,
        "resume"         -> config.resume.asJson,
        "pathToClaudeCodeExecutable" -> config.pathToClaudeCodeExecutable.asJson,
      ),
    )

    // Send to worker with generous timeout (agent sessions can be long)
    val rpcTimeoutMs = config.timeoutMs + 30000 // extra 30s buffer for RPC overhead

    if (!workerManager.isAlive || !workerManager.isInitialized) {
      return AgentFailed("Worker not available: not alive or not initialized")
    }

    val rpcResult = try {
      workerManager.sendRawRequest("agent/execute", params, rpcTimeoutMs)
    } catch {
      case e: Exception =>
        return AgentFailed(s"Worker RPC failed: ${e.getMessage}")
    }

    rpcResult match {
      case Left(err) =>
        AgentFailed(s"agent/execute failed: $err")

      case Right(json) =>
        parseAgentResult(json, context.worktreePath)
    }
  }

  /**
   * Parse the JSON-RPC result from the worker into an AgentResult.
   * Also detects changed files via git diff in the worktree.
   */
  private[agent] def parseAgentResult(json: Json, worktreePath: Path): AgentResult = {
    val c = json.hcursor

    val success        = c.downField("success").as[Boolean].getOrElse(false)
    val sessionId      = c.downField("sessionId").as[String].getOrElse("")
    val resultText     = c.downField("resultText").as[String].getOrElse("")
    val inputTokens    = c.downField("inputTokens").as[Long].getOrElse(0L)
    val outputTokens   = c.downField("outputTokens").as[Long].getOrElse(0L)
    val costUsd        = c.downField("costUsd").as[Double].getOrElse(0.0)
    val numTurns       = c.downField("numTurns").as[Int].getOrElse(0)
    val durationMs     = c.downField("durationMs").as[Long].getOrElse(0L)
    val isInterrupted  = c.downField("isInterrupted").as[Boolean].getOrElse(false)
    val isBudgetExceeded = c.downField("isBudgetExceeded").as[Boolean].getOrElse(false)

    val toolUseLog = c.downField("toolUseLog").as[List[Json]].getOrElse(Nil).map { entry =>
      val ec = entry.hcursor
      ToolUseEntry(
        toolName     = ec.downField("toolName").as[String].getOrElse("unknown"),
        timestamp    = ec.downField("timestamp").as[String].getOrElse(""),
        inputSummary = ec.downField("inputSummary").as[String].getOrElse(""),
      )
    }

    if (isInterrupted) {
      return AgentTimeout(
        timeoutMs      = durationMs,
        inputTokens    = inputTokens,
        outputTokens   = outputTokens,
        costUsd        = costUsd,
        partialSummary = resultText,
      )
    }

    if (isBudgetExceeded) {
      return AgentBudgetExceeded(
        maxBudgetUsd   = 0.0, // not available from worker response
        actualCostUsd  = costUsd,
        inputTokens    = inputTokens,
        outputTokens   = outputTokens,
        partialSummary = resultText,
      )
    }

    if (!success) {
      return AgentFailed(
        reason       = resultText,
        inputTokens  = inputTokens,
        outputTokens = outputTokens,
        costUsd      = costUsd,
        durationMs   = durationMs,
      )
    }

    // Design §8.2: Detect changed files via git diff in worktree
    val filesChanged = detectChangedFiles(worktreePath)

    // Design §8.2: Check if agent ran verify_requirements successfully
    val agentVerified = toolUseLog.exists(_.toolName == "verify_requirements")

    AgentCompleted(
      sessionId      = sessionId,
      filesChanged   = filesChanged,
      agentVerified  = agentVerified,
      inputTokens    = inputTokens,
      outputTokens   = outputTokens,
      costUsd        = costUsd,
      numTurns       = numTurns,
      durationMs     = durationMs,
      summary        = resultText,
      toolUseLog     = toolUseLog,
    )
  }

  /** Detect changed files via `git diff --name-only` in the worktree. */
  private[agent] def detectChangedFiles(worktreePath: Path): List[String] = {
    try {
      val output = Process(
        Seq("git", "diff", "--name-only", "HEAD"),
        worktreePath.toFile,
      ).!!.trim
      if (output.isEmpty) Nil
      else output.split('\n').toList.map(_.trim).filter(_.nonEmpty)
    } catch {
      case _: Exception => Nil
    }
  }
}
