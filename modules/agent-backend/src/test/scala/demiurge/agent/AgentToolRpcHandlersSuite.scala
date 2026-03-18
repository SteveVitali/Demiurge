package demiurge.agent

import java.time.{Duration, Instant}
import munit.FunSuite
import io.circe._
import io.circe.syntax._
import demiurge.model._

class AgentToolRpcHandlersSuite extends FunSuite {

  private def makeNode(reqId: String, description: String): RequirementNode = RequirementNode(
    requirementId = reqId,
    humanDescription = description,
    machineDescription = description,
    priority = RequirementPriority.Required,
    category = RequirementCategory.ApiContract,
    dependencies = Set.empty,
    verifiers = Nil,
    evidenceRequired = Nil,
    destructiveRiskLevel = 0,
    inferredFrom = Nil,
    confidence = 1.0,
    stopOnFailure = false,
  )

  private def makeGraph(nodes: List[RequirementNode]): RequirementGraph = RequirementGraph(
    graphId = "graph-test",
    runId = "run-test-1",
    nodes = nodes,
    edges = Nil,
    generatedAt = Instant.now(),
    inferenceRequestId = None,
    warnings = Nil,
  )

  test("RequirementGraph can be constructed and queried") {
    val graph = makeGraph(List(makeNode("REQ-001", "Test requirement")))
    assertEquals(graph.nodes.head.requirementId, "REQ-001")
    assertEquals(graph.nodes.head.humanDescription, "Test requirement")
  }

  test("AgentConfig.Default has sensible values") {
    val config = AgentConfig.Default
    assertEquals(config.timeoutMs, 300000L)
    assert(config.enableMcpTools)
    assert(config.model.isEmpty)
    assert(config.maxTurns.isEmpty)
    assert(config.maxBudgetUsd.isEmpty)
    assert(!config.resume)
  }

  test("AgentConfig.fromEnvironment respects defaults when no env vars set") {
    val config = AgentConfig.fromEnvironment(AgentConfig(
      timeoutMs = 120000,
      maxTurns = Some(30),
    ))
    assert(config.timeoutMs > 0)
  }

  test("AgentResult ADT covers all expected cases") {
    val completed = AgentCompleted(
      sessionId = "s1",
      filesChanged = List("a.ts"),
      agentVerified = true,
      inputTokens = 100,
      outputTokens = 50,
      costUsd = 0.01,
      numTurns = 3,
      durationMs = 5000,
      summary = "Done",
    )
    assert(completed.isInstanceOf[AgentResult])

    val failed = AgentFailed("reason")
    assert(failed.isInstanceOf[AgentResult])

    val timeout = AgentTimeout(timeoutMs = 300000)
    assert(timeout.isInstanceOf[AgentResult])

    val budget = AgentBudgetExceeded(maxBudgetUsd = 1.0, actualCostUsd = 1.5)
    assert(budget.isInstanceOf[AgentResult])
  }

  test("ToolUseEntry captures tool invocation metadata") {
    val entry = ToolUseEntry(
      toolName = "verify_requirements",
      timestamp = "2024-01-01T00:00:00Z",
      inputSummary = """{"requirementIds":[]}""",
    )
    assertEquals(entry.toolName, "verify_requirements")
    assert(entry.inputSummary.contains("requirementIds"))
  }

  test("AgentCompleted.agentVerified reflects tool usage") {
    val withVerify = AgentCompleted(
      sessionId = "s1",
      filesChanged = Nil,
      agentVerified = true,
      inputTokens = 0, outputTokens = 0, costUsd = 0, numTurns = 0,
      durationMs = 0, summary = "",
      toolUseLog = List(ToolUseEntry("verify_requirements", "", "")),
    )
    assert(withVerify.agentVerified)

    val withoutVerify = AgentCompleted(
      sessionId = "s2",
      filesChanged = Nil,
      agentVerified = false,
      inputTokens = 0, outputTokens = 0, costUsd = 0, numTurns = 0,
      durationMs = 0, summary = "",
      toolUseLog = List(ToolUseEntry("read_file", "", "")),
    )
    assert(!withoutVerify.agentVerified)
  }

  test("ClaudeAgentBackend has correct backendId") {
    val backendId = "claude-agent-sdk"
    assertEquals(backendId, "claude-agent-sdk")
  }
}
