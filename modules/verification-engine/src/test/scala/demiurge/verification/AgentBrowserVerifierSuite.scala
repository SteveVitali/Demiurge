package demiurge.verification

import munit.FunSuite
import java.time.{Duration, Instant}
import demiurge.model._
import demiurge.model.Observation

class AgentBrowserVerifierSuite extends FunSuite {

  private def makeAgentBrowserNode(
    reqId: String,
    entryUrl: String = "http://localhost:3000",
    featureDescription: String = "Test feature",
    viewports: List[Viewport] = Nil,
    tasteSensitivity: TasteSensitivity = TasteSensitivity.Normal,
    tasteTriggersRepair: Boolean = true,
    maxBudgetUsd: Double = 50.0,
  ): RequirementNode = {
    RequirementNode(
      requirementId = reqId,
      humanDescription = s"Browser verification for $reqId",
      machineDescription = s"Browser verification for $reqId",
      priority = RequirementPriority.Required,
      category = RequirementCategory.UiFlow,
      dependencies = Set.empty,
      verifiers = List(VerifierSpec(
        verifierId = s"v-$reqId",
        verifierType = VerifierType.AgentBrowser,
        displayName = s"Agent browser: $reqId",
        requirementId = reqId,
        executionLayer = 0,
        parallelSafe = false,
        timeout = Duration.ofSeconds(120),
        maxRetries = 0,
        retryDelayMs = 1000,
        browserFlowSpec = None,
        apiContractSpec = None,
        stateAssertionSpec = None,
        envReadinessSpec = None,
        consoleLogSpec = None,
        networkSpec = None,
        queueJobSpec = None,
        persistenceSpec = None,
        regressionSpec = None,
        agentBrowserSpec = Some(AgentBrowserVerifierSpec(
          entryUrl = entryUrl,
          featureDescription = featureDescription,
          viewports = viewports,
          tasteSensitivity = tasteSensitivity,
          tasteTriggersRepair = tasteTriggersRepair,
          maxBudgetUsd = maxBudgetUsd,
        )),
      )),
      evidenceRequired = Nil,
      destructiveRiskLevel = 0,
      inferredFrom = Nil,
      confidence = 1.0,
      stopOnFailure = false,
    )
  }

  private def makeGraph(nodes: List[RequirementNode]): RequirementGraph = {
    RequirementGraph(
      graphId = "test-graph",
      runId = "test-run",
      nodes = nodes,
      edges = Nil,
      generatedAt = Instant.EPOCH,
      inferenceRequestId = None,
      warnings = Nil,
    )
  }

  test("VerifierGenerator produces AgentBrowserVerifier from AgentBrowser spec") {
    val node = makeAgentBrowserNode("req-ui-1",
      entryUrl = "http://localhost:3000/dashboard",
      featureDescription = "Dashboard shows user stats",
    )
    val graph = makeGraph(List(node))
    val verifiers = VerifierGenerator.generate(graph)

    assertEquals(verifiers.size, 1)
    verifiers.head match {
      case v: AgentBrowserVerifier =>
        assertEquals(v.id, "v-req-ui-1")
        assertEquals(v.requirementId, "req-ui-1")
        assertEquals(v.entryUrl, "http://localhost:3000/dashboard")
        assertEquals(v.featureDescription, "Dashboard shows user stats")
        assertEquals(v.maxBudgetUsd, 50.0)
        assertEquals(v.tasteSensitivity, TasteSensitivity.Normal)
        assert(v.tasteTriggersRepair)
      case other => fail(s"Expected AgentBrowserVerifier, got ${other.getClass.getSimpleName}")
    }
  }

  test("VerifierGenerator preserves viewports from spec") {
    val node = makeAgentBrowserNode("req-ui-2",
      viewports = List(Viewport(375, 667), Viewport(1920, 1080)),
    )
    val graph = makeGraph(List(node))
    val verifiers = VerifierGenerator.generate(graph)

    verifiers.head match {
      case v: AgentBrowserVerifier =>
        assertEquals(v.viewports.size, 2)
        assertEquals(v.viewports.head, Viewport(375, 667))
        assertEquals(v.viewports.last, Viewport(1920, 1080))
      case other => fail(s"Expected AgentBrowserVerifier, got ${other.getClass.getSimpleName}")
    }
  }

  test("VerifierGenerator preserves taste sensitivity settings") {
    val node = makeAgentBrowserNode("req-ui-3",
      tasteSensitivity = TasteSensitivity.Strict,
      tasteTriggersRepair = false,
    )
    val graph = makeGraph(List(node))
    val verifiers = VerifierGenerator.generate(graph)

    verifiers.head match {
      case v: AgentBrowserVerifier =>
        assertEquals(v.tasteSensitivity, TasteSensitivity.Strict)
        assert(!v.tasteTriggersRepair)
      case other => fail(s"Expected AgentBrowserVerifier, got ${other.getClass.getSimpleName}")
    }
  }

  test("AgentBrowser without agentBrowserSpec throws IllegalStateException") {
    val node = RequirementNode(
      requirementId = "req-missing",
      humanDescription = "Missing spec",
      machineDescription = "Missing spec",
      priority = RequirementPriority.Required,
      category = RequirementCategory.UiFlow,
      dependencies = Set.empty,
      verifiers = List(VerifierSpec(
        verifierId = "v-missing",
        verifierType = VerifierType.AgentBrowser,
        displayName = "Missing spec test",
        requirementId = "req-missing",
        executionLayer = 0,
        parallelSafe = false,
        timeout = Duration.ofSeconds(5),
        maxRetries = 0,
        retryDelayMs = 1000,
        browserFlowSpec = None,
        apiContractSpec = None,
        stateAssertionSpec = None,
        envReadinessSpec = None,
        consoleLogSpec = None,
        networkSpec = None,
        queueJobSpec = None,
        persistenceSpec = None,
        regressionSpec = None,
        // agentBrowserSpec is None (default)
      )),
      evidenceRequired = Nil,
      destructiveRiskLevel = 0,
      inferredFrom = Nil,
      confidence = 1.0,
      stopOnFailure = false,
    )
    val graph = makeGraph(List(node))
    intercept[IllegalStateException] {
      VerifierGenerator.generate(graph)
    }
  }

  test("VerifierExecutor.executeOnce returns error for AgentBrowserVerifier") {
    val verifier = AgentBrowserVerifier(
      id = "v-test",
      requirementId = "req-test",
      entryUrl = "http://localhost:3000",
      featureDescription = "Test",
      timeout = Duration.ofSeconds(5),
      maxRetries = 0,
    )
    val outcome = VerifierExecutor.executeOnce(verifier)
    outcome match {
      case VerifierOutcome.Error(msg) =>
        assert(msg.contains("AgentBrowserExecutor"))
      case other => fail(s"Expected Error outcome, got $other")
    }
  }

  test("VerificationEngine returns error when no AgentBrowserExecutor provided") {
    val node = makeAgentBrowserNode("req-no-executor")
    val graph = makeGraph(List(node))

    val result = VerificationEngine.runVerification(
      "test-run", 1, graph,
      agentBrowserExecutor = None,
    )

    assertEquals(result.verdicts.size, 1)
    assertEquals(result.verdicts.head.status, VerdictStatus.Fail)
    assert(result.verdicts.head.failureMessage.exists(_.contains("No agent browser executor")))
  }

  test("VerificationEngine delegates to AgentBrowserExecutor when provided") {
    val node = makeAgentBrowserNode("req-with-executor",
      entryUrl = "http://localhost:3000/page",
      featureDescription = "Page renders correctly",
    )
    val graph = makeGraph(List(node))

    // Create a mock executor that returns Pass
    val mockExecutor = new VerificationEngine.AgentBrowserExecutor {
      override def execute(verifier: AgentBrowserVerifier): BrowserVerifierResult = {
        BrowserVerifierResult(
          outcome = VerifierOutcome.Passed,
          observations = List(Observation("ui-test", "Page renders correctly", None, None, None, Instant.now())),
          artifactRefs = List("screenshot-001.png"),
        )
      }
    }

    val result = VerificationEngine.runVerification(
      "test-run", 1, graph,
      agentBrowserExecutor = Some(mockExecutor),
    )

    assertEquals(result.verdicts.size, 1)
    assertEquals(result.verdicts.head.status, VerdictStatus.Pass)
  }

  test("VerificationEngine handles AgentBrowserVerifier failure") {
    val node = makeAgentBrowserNode("req-fail")
    val graph = makeGraph(List(node))

    val mockExecutor = new VerificationEngine.AgentBrowserExecutor {
      override def execute(verifier: AgentBrowserVerifier): BrowserVerifierResult = {
        BrowserVerifierResult(
          outcome = VerifierOutcome.Failed("Feature not implemented"),
          observations = Nil,
          artifactRefs = Nil,
        )
      }
    }

    val result = VerificationEngine.runVerification(
      "test-run", 1, graph,
      agentBrowserExecutor = Some(mockExecutor),
    )

    assertEquals(result.verdicts.size, 1)
    assertEquals(result.verdicts.head.status, VerdictStatus.Fail)
    assertEquals(result.verdicts.head.failureClass, Some(FailureClass.FrontendRenderError))
    assert(result.verdicts.head.failureMessage.exists(_.contains("Feature not implemented")))
  }

  test("VerificationEngine handles AgentBrowserVerifier timeout") {
    val node = makeAgentBrowserNode("req-timeout")
    val graph = makeGraph(List(node))

    val mockExecutor = new VerificationEngine.AgentBrowserExecutor {
      override def execute(verifier: AgentBrowserVerifier): BrowserVerifierResult = {
        BrowserVerifierResult(
          outcome = VerifierOutcome.TimedOut,
          observations = Nil,
          artifactRefs = Nil,
        )
      }
    }

    val result = VerificationEngine.runVerification(
      "test-run", 1, graph,
      agentBrowserExecutor = Some(mockExecutor),
    )

    assertEquals(result.verdicts.size, 1)
    assertEquals(result.verdicts.head.status, VerdictStatus.Timeout)
    assertEquals(result.verdicts.head.failureClass, Some(FailureClass.BrowserTimingFlake))
  }

  test("AgentBrowserVerifier and HttpVerifier can coexist in same graph") {
    val uiNode = makeAgentBrowserNode("req-ui")
    val apiSpec = ApiContractVerifierSpec(
      method = "GET",
      path = "http://localhost:3000/health",
      expectedStatus = 200,
    )
    val apiNode = RequirementNode(
      requirementId = "req-api",
      humanDescription = "Health check",
      machineDescription = "Health check",
      priority = RequirementPriority.Required,
      category = RequirementCategory.ApiContract,
      dependencies = Set.empty,
      verifiers = List(VerifierSpec(
        verifierId = "v-req-api",
        verifierType = VerifierType.HttpApiContract,
        displayName = "GET /health",
        requirementId = "req-api",
        executionLayer = 0,
        parallelSafe = true,
        timeout = Duration.ofSeconds(5),
        maxRetries = 0,
        retryDelayMs = 1000,
        browserFlowSpec = None,
        apiContractSpec = Some(apiSpec),
        stateAssertionSpec = None,
        envReadinessSpec = None,
        consoleLogSpec = None,
        networkSpec = None,
        queueJobSpec = None,
        persistenceSpec = None,
        regressionSpec = None,
      )),
      evidenceRequired = Nil,
      destructiveRiskLevel = 0,
      inferredFrom = Nil,
      confidence = 1.0,
      stopOnFailure = false,
    )
    val graph = makeGraph(List(uiNode, apiNode))
    val verifiers = VerifierGenerator.generate(graph)

    assertEquals(verifiers.size, 2)
    assert(verifiers.exists(_.isInstanceOf[AgentBrowserVerifier]))
    assert(verifiers.exists(_.isInstanceOf[HttpVerifier]))
  }

  test("TasteSensitivity values are distinct") {
    val values = List(
      TasteSensitivity.Strict,
      TasteSensitivity.Normal,
      TasteSensitivity.Lenient,
      TasteSensitivity.Off,
    )
    assertEquals(values.distinct.size, 4)
  }

  test("Viewport case class equality") {
    val v1 = Viewport(1920, 1080)
    val v2 = Viewport(1920, 1080)
    val v3 = Viewport(375, 667)
    assertEquals(v1, v2)
    assertNotEquals(v1, v3)
  }
}
