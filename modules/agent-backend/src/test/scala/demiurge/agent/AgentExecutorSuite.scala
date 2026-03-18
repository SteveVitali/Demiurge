package demiurge.agent

import munit.FunSuite
import io.circe._
import io.circe.syntax._

class AgentExecutorSuite extends FunSuite {

  test("parseAgentResult handles successful completion") {
    val json = Json.obj(
      "sessionId"        -> "sess-123".asJson,
      "success"          -> true.asJson,
      "resultText"       -> "Fixed the health endpoint".asJson,
      "inputTokens"      -> 5000L.asJson,
      "outputTokens"     -> 2000L.asJson,
      "costUsd"          -> 0.05.asJson,
      "numTurns"         -> 8.asJson,
      "durationMs"       -> 45000L.asJson,
      "isInterrupted"    -> false.asJson,
      "isBudgetExceeded" -> false.asJson,
      "toolUseLog"       -> Json.arr(
        Json.obj(
          "toolName"     -> "verify_requirements".asJson,
          "timestamp"    -> "2024-01-01T00:00:00Z".asJson,
          "inputSummary" -> "{}".asJson,
        ),
      ),
    )

    val result = invokeParseAgentResult(json)
    result match {
      case c: AgentCompleted =>
        assertEquals(c.sessionId, "sess-123")
        assertEquals(c.inputTokens, 5000L)
        assertEquals(c.outputTokens, 2000L)
        assertEquals(c.costUsd, 0.05)
        assertEquals(c.numTurns, 8)
        assertEquals(c.durationMs, 45000L)
        assert(c.agentVerified, "Should detect verify_requirements in tool log")
        assertEquals(c.summary, "Fixed the health endpoint")
      case other =>
        fail(s"Expected AgentCompleted but got ${other.getClass.getSimpleName}")
    }
  }

  test("parseAgentResult handles failure") {
    val json = Json.obj(
      "sessionId"        -> "sess-456".asJson,
      "success"          -> false.asJson,
      "resultText"       -> "Could not determine root cause".asJson,
      "inputTokens"      -> 3000L.asJson,
      "outputTokens"     -> 1000L.asJson,
      "costUsd"          -> 0.02.asJson,
      "numTurns"         -> 5.asJson,
      "durationMs"       -> 30000L.asJson,
      "isInterrupted"    -> false.asJson,
      "isBudgetExceeded" -> false.asJson,
      "toolUseLog"       -> Json.arr(),
    )

    val result = invokeParseAgentResult(json)
    result match {
      case f: AgentFailed =>
        assertEquals(f.reason, "Could not determine root cause")
        assertEquals(f.inputTokens, 3000L)
        assertEquals(f.costUsd, 0.02)
      case other =>
        fail(s"Expected AgentFailed but got ${other.getClass.getSimpleName}")
    }
  }

  test("parseAgentResult handles timeout") {
    val json = Json.obj(
      "sessionId"        -> "sess-789".asJson,
      "success"          -> false.asJson,
      "resultText"       -> "Agent session interrupted by timeout".asJson,
      "inputTokens"      -> 8000L.asJson,
      "outputTokens"     -> 4000L.asJson,
      "costUsd"          -> 0.10.asJson,
      "numTurns"         -> 20.asJson,
      "durationMs"       -> 300000L.asJson,
      "isInterrupted"    -> true.asJson,
      "isBudgetExceeded" -> false.asJson,
      "toolUseLog"       -> Json.arr(),
    )

    val result = invokeParseAgentResult(json)
    result match {
      case t: AgentTimeout =>
        assertEquals(t.timeoutMs, 300000L)
        assertEquals(t.inputTokens, 8000L)
        assertEquals(t.costUsd, 0.10)
        assertEquals(t.partialSummary, "Agent session interrupted by timeout")
      case other =>
        fail(s"Expected AgentTimeout but got ${other.getClass.getSimpleName}")
    }
  }

  test("parseAgentResult handles budget exceeded") {
    val json = Json.obj(
      "sessionId"        -> "sess-budget".asJson,
      "success"          -> false.asJson,
      "resultText"       -> "Budget limit reached".asJson,
      "inputTokens"      -> 50000L.asJson,
      "outputTokens"     -> 20000L.asJson,
      "costUsd"          -> 5.0.asJson,
      "numTurns"         -> 50.asJson,
      "durationMs"       -> 600000L.asJson,
      "isInterrupted"    -> false.asJson,
      "isBudgetExceeded" -> true.asJson,
      "toolUseLog"       -> Json.arr(),
    )

    val result = invokeParseAgentResult(json)
    result match {
      case b: AgentBudgetExceeded =>
        assertEquals(b.actualCostUsd, 5.0)
        assertEquals(b.inputTokens, 50000L)
        assertEquals(b.partialSummary, "Budget limit reached")
      case other =>
        fail(s"Expected AgentBudgetExceeded but got ${other.getClass.getSimpleName}")
    }
  }

  test("parseAgentResult handles missing fields gracefully") {
    val json = Json.obj(
      "success" -> true.asJson,
    )

    val result = invokeParseAgentResult(json)
    result match {
      case c: AgentCompleted =>
        assertEquals(c.sessionId, "")
        assertEquals(c.inputTokens, 0L)
        assertEquals(c.outputTokens, 0L)
        assertEquals(c.numTurns, 0)
      case other =>
        fail(s"Expected AgentCompleted but got ${other.getClass.getSimpleName}")
    }
  }

  test("parseAgentResult detects agentVerified from tool log") {
    val jsonWithVerify = Json.obj(
      "success"          -> true.asJson,
      "isInterrupted"    -> false.asJson,
      "isBudgetExceeded" -> false.asJson,
      "toolUseLog"       -> Json.arr(
        Json.obj("toolName" -> "read_file".asJson, "timestamp" -> "".asJson, "inputSummary" -> "".asJson),
        Json.obj("toolName" -> "verify_requirements".asJson, "timestamp" -> "".asJson, "inputSummary" -> "".asJson),
      ),
    )

    val result = invokeParseAgentResult(jsonWithVerify)
    assert(result.isInstanceOf[AgentCompleted])
    assert(result.asInstanceOf[AgentCompleted].agentVerified)

    val jsonWithoutVerify = Json.obj(
      "success"          -> true.asJson,
      "isInterrupted"    -> false.asJson,
      "isBudgetExceeded" -> false.asJson,
      "toolUseLog"       -> Json.arr(
        Json.obj("toolName" -> "read_file".asJson, "timestamp" -> "".asJson, "inputSummary" -> "".asJson),
      ),
    )

    val result2 = invokeParseAgentResult(jsonWithoutVerify)
    assert(result2.isInstanceOf[AgentCompleted])
    assert(!result2.asInstanceOf[AgentCompleted].agentVerified)
  }

  test("detectChangedFiles returns empty list for non-existent path") {
    val nonExistent = java.nio.file.Path.of("/tmp/demiurge-test-nonexistent-" + System.nanoTime())
    val result = AgentExecutor.detectChangedFiles(nonExistent)
    assertEquals(result, Nil)
  }

  test("detectChangedFiles works in a real git repo") {
    val tmpDir = java.nio.file.Files.createTempDirectory("demiurge-detect-test")
    try {
      def run(args: String*): String =
        scala.sys.process.Process(args, tmpDir.toFile).!!

      run("git", "init")
      run("git", "config", "user.email", "test@test.com")
      run("git", "config", "user.name", "Test")

      // Create and commit a file
      java.nio.file.Files.writeString(tmpDir.resolve("initial.txt"), "hello")
      run("git", "add", ".")
      run("git", "commit", "-m", "init")

      // No changes yet
      assertEquals(AgentExecutor.detectChangedFiles(tmpDir), Nil)

      // Modify the file
      java.nio.file.Files.writeString(tmpDir.resolve("initial.txt"), "modified")
      val changed = AgentExecutor.detectChangedFiles(tmpDir)
      assertEquals(changed, List("initial.txt"))
    } finally {
      try { scala.sys.process.Process(Seq("rm", "-rf", tmpDir.toString)).!! } catch { case _: Exception => }
    }
  }

  private def invokeParseAgentResult(json: Json): AgentResult = {
    AgentExecutor.parseAgentResult(json, java.nio.file.Path.of("/tmp/test"))
  }
}
