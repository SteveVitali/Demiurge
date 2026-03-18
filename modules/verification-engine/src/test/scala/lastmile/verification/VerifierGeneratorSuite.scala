package lastmile.verification

import munit.FunSuite
import java.time.{Duration, Instant}
import lastmile.model._

class VerifierGeneratorSuite extends FunSuite {

  private def makeNode(
    reqId: String,
    verifierType: VerifierType,
    apiSpec: Option[ApiContractVerifierSpec] = None,
    envSpec: Option[EnvReadinessVerifierSpec] = None,
    logSpec: Option[ConsoleLogVerifierSpec] = None,
  ): RequirementNode = {
    RequirementNode(
      requirementId = reqId,
      humanDescription = s"Test $reqId",
      machineDescription = s"Test $reqId",
      priority = RequirementPriority.Required,
      category = RequirementCategory.ApiContract,
      dependencies = Set.empty,
      verifiers = List(VerifierSpec(
        verifierId = s"v-$reqId",
        verifierType = verifierType,
        displayName = s"Test $reqId",
        requirementId = reqId,
        executionLayer = 0,
        parallelSafe = true,
        timeout = Duration.ofSeconds(5),
        maxRetries = 0,
        retryDelayMs = 1000,
        browserFlowSpec = None,
        apiContractSpec = apiSpec,
        stateAssertionSpec = None,
        envReadinessSpec = envSpec,
        consoleLogSpec = logSpec,
        networkSpec = None,
        queueJobSpec = None,
        persistenceSpec = None,
        regressionSpec = None,
      )),
      evidenceRequired = Nil,
      destructiveRiskLevel = 0,
      inferredFrom = Nil,
      confidence = 1.0,
      stopOnFailure = true,
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

  test("generates HttpVerifier from HttpApiContract spec") {
    val apiSpec = ApiContractVerifierSpec(
      method = "GET",
      urlTemplate = "http://localhost:3000/health",
      headers = Map("Accept" -> "application/json"),
      bodyTemplate = None,
      expectedStatus = 200,
      responseAssertions = Nil,
      artifactPlan = Nil,
    )
    val graph = makeGraph(List(makeNode("req-1", VerifierType.HttpApiContract, apiSpec = Some(apiSpec))))
    val verifiers = VerifierGenerator.generate(graph)

    assertEquals(verifiers.size, 1)
    verifiers.head match {
      case v: HttpVerifier =>
        assertEquals(v.id, "v-req-1")
        assertEquals(v.method, "GET")
        assertEquals(v.url, "http://localhost:3000/health")
        assertEquals(v.expectedStatus, 200)
      case other => fail(s"Expected HttpVerifier, got $other")
    }
  }

  test("generates StateVerifier stub from EnvironmentReadiness spec (no port resolution in Phase 4)") {
    val envSpec = EnvReadinessVerifierSpec(
      serviceId = "db",
      probeOverride = None,
      requiredLogPatterns = Nil,
    )
    val graph = makeGraph(List(makeNode("req-1", VerifierType.EnvironmentReadiness, envSpec = Some(envSpec))))
    val verifiers = VerifierGenerator.generate(graph)

    assertEquals(verifiers.size, 1)
    assert(verifiers.head.isInstanceOf[StateVerifier])
  }

  test("generates LogContainsVerifier from ConsoleLogSanity spec") {
    val logSpec = ConsoleLogVerifierSpec(
      targetUrl = "/tmp/test.log",
      forbiddenPatterns = List("ERROR"),
      allowedPatterns = Nil,
      maxErrors = 0,
      captureLevel = "error",
    )
    val graph = makeGraph(List(makeNode("req-1", VerifierType.ConsoleLogSanity, logSpec = Some(logSpec))))
    val verifiers = VerifierGenerator.generate(graph)

    assertEquals(verifiers.size, 1)
    verifiers.head match {
      case v: LogContainsVerifier =>
        assertEquals(v.logPath, "/tmp/test.log")
        assertEquals(v.pattern, "ERROR")
        assert(v.forbidden)
      case other => fail(s"Expected LogContainsVerifier, got $other")
    }
  }

  test("generates StateVerifier for unsupported types") {
    // Phase 6: BrowserFlow now requires browserFlowSpec; use QueueJob as truly unsupported
    val graph = makeGraph(List(makeNode("req-1", VerifierType.QueueJob)))
    val verifiers = VerifierGenerator.generate(graph)

    assertEquals(verifiers.size, 1)
    assert(verifiers.head.isInstanceOf[StateVerifier])
  }

  test("generates BrowserFlowVerifier from BrowserFlow spec") {
    val browserSpec = BrowserFlowVerifierSpec(
      entryUrl = "http://localhost:3000",
      selectorMapRef = None,
      entryConditions = Nil,
      actions = List(BrowserAction("click", Some(SelectorRef("css", "#btn", None)), None, None, None, "Click button")),
      assertions = List(Assertion("elementVisible", Some(SelectorRef("css", "#result", None)), None, None, None, "Result visible")),
      artifactPlan = Nil,
      cleanup = Nil,
    )
    val node = RequirementNode(
      requirementId = "req-browser",
      humanDescription = "Browser test",
      machineDescription = "Browser test",
      priority = RequirementPriority.Required,
      category = RequirementCategory.UiFlow,
      dependencies = Set.empty,
      verifiers = List(VerifierSpec(
        verifierId = "v-browser",
        verifierType = VerifierType.BrowserFlow,
        displayName = "Browser flow",
        requirementId = "req-browser",
        executionLayer = 0,
        parallelSafe = false,
        timeout = Duration.ofSeconds(30),
        maxRetries = 0,
        retryDelayMs = 1000,
        browserFlowSpec = Some(browserSpec),
        apiContractSpec = None,
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
      stopOnFailure = true,
    )
    val graph = makeGraph(List(node))
    val verifiers = VerifierGenerator.generate(graph)

    assertEquals(verifiers.size, 1)
    verifiers.head match {
      case v: BrowserFlowVerifier =>
        assertEquals(v.entryUrl, "http://localhost:3000")
        assertEquals(v.actions.size, 1)
        assertEquals(v.assertions.size, 1)
      case other => fail(s"Expected BrowserFlowVerifier, got $other")
    }
  }

  test("BrowserFlow without browserFlowSpec throws") {
    val graph = makeGraph(List(makeNode("req-1", VerifierType.BrowserFlow)))
    intercept[IllegalStateException] {
      VerifierGenerator.generate(graph)
    }
  }

  test("deterministic generation - same input produces same output") {
    val apiSpec = ApiContractVerifierSpec("GET", "http://localhost:3000", Map.empty, None, 200, Nil, Nil)
    val graph = makeGraph(List(
      makeNode("req-1", VerifierType.HttpApiContract, apiSpec = Some(apiSpec)),
      makeNode("req-2", VerifierType.StateAssertion),
    ))

    val verifiers1 = VerifierGenerator.generate(graph)
    val verifiers2 = VerifierGenerator.generate(graph)

    assertEquals(verifiers1.size, verifiers2.size)
    verifiers1.zip(verifiers2).foreach { case (v1, v2) =>
      assertEquals(v1.id, v2.id)
      assertEquals(v1.requirementId, v2.requirementId)
    }
  }

  test("empty graph produces no verifiers") {
    val graph = makeGraph(Nil)
    val verifiers = VerifierGenerator.generate(graph)
    assertEquals(verifiers.size, 0)
  }

  test("generates one verifier per spec") {
    val apiSpec = ApiContractVerifierSpec("GET", "http://localhost:3000", Map.empty, None, 200, Nil, Nil)
    val graph = makeGraph(List(
      makeNode("req-1", VerifierType.HttpApiContract, apiSpec = Some(apiSpec)),
      makeNode("req-2", VerifierType.StateAssertion),
      makeNode("req-3", VerifierType.StateAssertion),
    ))
    val verifiers = VerifierGenerator.generate(graph)
    assertEquals(verifiers.size, 3)
  }
}
