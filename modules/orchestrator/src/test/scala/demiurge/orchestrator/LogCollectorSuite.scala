package demiurge.orchestrator

import munit.FunSuite
import java.time.Instant

import demiurge.model._
import demiurge.runtime.ServiceProcessManager

class LogCollectorSuite extends FunSuite {

  // Clean up ServiceProcessManager between tests
  override def beforeEach(context: BeforeEach): Unit = {
    ServiceProcessManager.clear()
  }

  private def makeRuntimePlan(serviceIds: List[String]): RuntimePlan = {
    RuntimePlan(
      planId = "test-plan",
      runId = "test-run",
      services = serviceIds.map { sid =>
        ServiceSpec(
          serviceId = sid,
          kind = ServiceKind.Api,
          startupMode = StartupMode.ScriptNative,
          startupCommand = None,
          composeTarget = None,
          cwd = "/tmp",
          env = Map.empty,
          envFile = None,
          ports = Nil,
          dependencyServices = Nil,
          readinessProbe = ReadinessProbe("http", "http://localhost:3000/health", 1000, 5000, 3, 0),
          shutdownMethod = "sigterm",
          shutdownTimeoutMs = 5000,
          restartPolicy = RestartPolicy(3, 1000, 30000, 2.0),
          logsSource = "stdout",
          required = true,
        )
      },
      fixtureSteps = Nil,
      authBootstrapPlan = None,
      resetStrategy = ResetStrategy.SoftReset,
      teardownOrder = Nil,
      observabilityTaps = Nil,
      generatedAt = Instant.EPOCH,
      warnings = Nil,
    )
  }

  test("collects logs from ServiceProcessManager") {
    // ServiceProcessManager won't have any services registered in a unit test
    // since we haven't started any real processes, but it should handle this gracefully
    val plan = makeRuntimePlan(List("api-server", "db"))

    val collected = LogCollector.collectAfterVerification(plan)

    // Should have entries for both services (empty since no real processes)
    assertEquals(collected.serviceLogs.size, 2)
    assert(collected.serviceLogs.contains("api-server"))
    assert(collected.serviceLogs.contains("db"))
  }

  test("serializes within char budget") {
    val logs = LogCollector.CollectedLogs(
      serviceLogs = Map(
        "svc-1" -> (1 to 100).map(i => s"Log line $i from svc-1").toList,
        "svc-2" -> (1 to 100).map(i => s"Log line $i from svc-2").toList,
      ),
      consoleErrors = List("console error 1"),
      networkErrors = List("network error 1"),
    )

    val serialized = logs.serialize(maxChars = 500)
    assert(serialized.isDefined)
    assert(serialized.get.length <= 500,
      s"Serialized output should respect char budget: ${serialized.get.length}")
  }

  test("handles empty services gracefully") {
    val plan = makeRuntimePlan(Nil)

    val collected = LogCollector.collectAfterVerification(plan)

    assert(collected.isEmpty, "No services should mean empty logs")
    assertEquals(collected.serialize(), None)
  }

  test("isEmpty returns true for empty logs") {
    val empty = LogCollector.CollectedLogs(
      serviceLogs = Map("svc" -> Nil),
      consoleErrors = Nil,
      networkErrors = Nil,
    )
    assert(empty.isEmpty)
    assertEquals(empty.serialize(), None)
  }

  test("isEmpty returns false when service logs present") {
    val nonEmpty = LogCollector.CollectedLogs(
      serviceLogs = Map("svc" -> List("some log line")),
      consoleErrors = Nil,
      networkErrors = Nil,
    )
    assert(!nonEmpty.isEmpty)
    assert(nonEmpty.serialize().isDefined)
  }

  test("serialization includes service headers") {
    val logs = LogCollector.CollectedLogs(
      serviceLogs = Map("my-api" -> List("started on port 3000", "request received")),
      consoleErrors = Nil,
      networkErrors = Nil,
    )

    val serialized = logs.serialize().get
    assert(serialized.contains("=== Service: my-api ==="),
      s"Should contain service header: $serialized")
    assert(serialized.contains("started on port 3000"))
    assert(serialized.contains("request received"))
  }

  test("serialization includes console and network errors") {
    val logs = LogCollector.CollectedLogs(
      serviceLogs = Map.empty,
      consoleErrors = List("TypeError: undefined is not a function"),
      networkErrors = List("GET /api/data 500 Internal Server Error"),
    )

    val serialized = logs.serialize().get
    assert(serialized.contains("=== Console Errors ==="))
    assert(serialized.contains("TypeError"))
    assert(serialized.contains("=== Network Errors ==="))
    assert(serialized.contains("500 Internal Server Error"))
  }

  test("logs appear in repair prompt via RepairContext") {
    // Verify that when logs are set on RepairContext, ClaudePromptBuilder includes them
    val logText = "=== Service: api ===\nError: connection refused\n"
    val context = demiurge.repair.RepairContext(
      runId = "test-run",
      attemptNumber = 1,
      taskText = "Fix the API",
      worktreePath = java.nio.file.Paths.get("/tmp/nonexistent-worktree"),
      graph = RequirementGraph(
        graphId = "g1", runId = "test-run", nodes = Nil, edges = Nil,
        generatedAt = Instant.EPOCH, inferenceRequestId = None, warnings = Nil,
      ),
      verdicts = Nil,
      inspectionReport = None,
      runtimePlan = None,
      patchHistory = Nil,
      logs = Some(logText),
    )

    val packet = FailurePacket(
      failurePacketId = "fp1",
      runId = "test-run",
      attemptNumber = 1,
      primaryFailureClass = FailureClass.UnknownFailure,
      secondaryFailureClasses = Nil,
      summary = "Test failure",
      affectedRequirementIds = Nil,
      reproductionSteps = Nil,
      evidenceRefs = Nil,
      suspectedRootCauses = Nil,
      recommendedRerunScope = Nil,
      recommendedRepairScope = RepairScope(Nil, Nil, "test", false),
      hardBlockers = Nil,
      softBlockers = Nil,
      producedAt = Instant.EPOCH,
      inferenceRequestId = None,
    )

    val prompt = demiurge.repair.claude.ClaudePromptBuilder.buildUserPrompt(packet, context)
    assert(prompt.contains("# Service Logs"),
      s"Prompt should contain Service Logs section: ${prompt.take(500)}")
    assert(prompt.contains("connection refused"),
      s"Prompt should contain log content: ${prompt.take(500)}")
  }
}
