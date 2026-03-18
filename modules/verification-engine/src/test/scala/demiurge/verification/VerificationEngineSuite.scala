package demiurge.verification

import munit.FunSuite
import java.time.{Duration, Instant}
import java.nio.file.Files
import demiurge.model._

class VerificationEngineSuite extends FunSuite {

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

  private def makeStateNode(reqId: String): RequirementNode = {
    RequirementNode(
      requirementId = reqId,
      humanDescription = s"Test $reqId",
      machineDescription = s"Test $reqId",
      priority = RequirementPriority.Required,
      category = RequirementCategory.PersistenceState,
      dependencies = Set.empty,
      verifiers = List(VerifierSpec(
        verifierId = s"v-$reqId",
        verifierType = VerifierType.StateAssertion,
        displayName = s"Test $reqId",
        requirementId = reqId,
        executionLayer = 0,
        parallelSafe = true,
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
      )),
      evidenceRequired = Nil,
      destructiveRiskLevel = 0,
      inferredFrom = Nil,
      confidence = 1.0,
      stopOnFailure = true,
    )
  }

  test("runVerification produces verdicts for each verifier") {
    val graph = makeGraph(List(makeStateNode("req-1"), makeStateNode("req-2")))
    val result = VerificationEngine.runVerification("run-1", 1, graph)

    assertEquals(result.verdicts.size, 2)
    assertEquals(result.aggregate.total, 2)
    assertEquals(result.aggregate.overallVerdict, VerdictStatus.Pass)
  }

  test("verdicts have correct runId and attemptNumber") {
    val graph = makeGraph(List(makeStateNode("req-1")))
    val result = VerificationEngine.runVerification("run-42", 3, graph)

    assertEquals(result.verdicts.head.runId, "run-42")
    assertEquals(result.verdicts.head.attemptNumber, 3)
  }

  test("empty graph produces empty verdicts and Pass aggregate") {
    val graph = makeGraph(Nil)
    val result = VerificationEngine.runVerification("run-1", 1, graph)

    assertEquals(result.verdicts.size, 0)
    assertEquals(result.aggregate.overallVerdict, VerdictStatus.Pass)
    assertEquals(result.aggregate.total, 0)
  }

  test("failing verifier produces Fail verdict") {
    val node = RequirementNode(
      requirementId = "req-fail",
      humanDescription = "Failing HTTP",
      machineDescription = "Failing HTTP",
      priority = RequirementPriority.Required,
      category = RequirementCategory.ApiContract,
      dependencies = Set.empty,
      verifiers = List(VerifierSpec(
        verifierId = "v-fail",
        verifierType = VerifierType.HttpApiContract,
        displayName = "Failing HTTP check",
        requirementId = "req-fail",
        executionLayer = 0,
        parallelSafe = true,
        timeout = Duration.ofSeconds(2),
        maxRetries = 0,
        retryDelayMs = 100,
        browserFlowSpec = None,
        apiContractSpec = Some(ApiContractVerifierSpec(
          method = "GET",
          path = "http://localhost:19999/nonexistent",
          expectedStatus = 200,
        )),
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
    val result = VerificationEngine.runVerification("run-1", 1, graph)

    assertEquals(result.aggregate.overallVerdict, VerdictStatus.Fail)
    assert(result.verdicts.head.status != VerdictStatus.Pass)
    assert(result.verdicts.head.failureMessage.isDefined)
  }

  test("each verdict has unique verdictId") {
    val graph = makeGraph(List(makeStateNode("req-1"), makeStateNode("req-2"), makeStateNode("req-3")))
    val result = VerificationEngine.runVerification("run-1", 1, graph)

    val ids = result.verdicts.map(_.verdictId)
    assertEquals(ids.distinct.size, ids.size, "All verdictIds should be unique")
  }

  test("verdict executionDurationMs is non-negative") {
    val graph = makeGraph(List(makeStateNode("req-1")))
    val result = VerificationEngine.runVerification("run-1", 1, graph)

    assert(result.verdicts.head.executionDurationMs >= 0)
  }

  test("stopOnFailure blocks later verifiers for the same requirement") {
    // Two verifiers on the same requirement across layers:
    //   Layer 0: HTTP verifier (will fail — unreachable URL)
    //   Layer 2: StateAssertion verifier (should be blocked by stopOnFailure)
    val reqId = "req-stop"
    val node = RequirementNode(
      requirementId = reqId,
      humanDescription = "Stop on failure test",
      machineDescription = "Stop on failure test",
      priority = RequirementPriority.Required,
      category = RequirementCategory.ApiContract,
      dependencies = Set.empty,
      verifiers = List(
        VerifierSpec(
          verifierId = "v-stop-http",
          verifierType = VerifierType.HttpApiContract,
          displayName = "Failing HTTP",
          requirementId = reqId,
          executionLayer = 0,
          parallelSafe = true,
          timeout = Duration.ofSeconds(2),
          maxRetries = 0,
          retryDelayMs = 0,
          browserFlowSpec = None,
          apiContractSpec = Some(ApiContractVerifierSpec(
            method = "GET",
            path = "http://localhost:19999/nonexistent",
            expectedStatus = 200,
          )),
          stateAssertionSpec = None,
          envReadinessSpec = None,
          consoleLogSpec = None,
          networkSpec = None,
          queueJobSpec = None,
          persistenceSpec = None,
          regressionSpec = None,
        ),
        VerifierSpec(
          verifierId = "v-stop-state",
          verifierType = VerifierType.StateAssertion,
          displayName = "Should be blocked",
          requirementId = reqId,
          executionLayer = 2,
          parallelSafe = true,
          timeout = Duration.ofSeconds(2),
          maxRetries = 0,
          retryDelayMs = 0,
          browserFlowSpec = None,
          apiContractSpec = None,
          stateAssertionSpec = None,
          envReadinessSpec = None,
          consoleLogSpec = None,
          networkSpec = None,
          queueJobSpec = None,
          persistenceSpec = None,
          regressionSpec = None,
        ),
      ),
      evidenceRequired = Nil,
      destructiveRiskLevel = 0,
      inferredFrom = Nil,
      confidence = 1.0,
      stopOnFailure = true,
    )
    val graph = makeGraph(List(node))
    val result = VerificationEngine.runVerification("run-1", 1, graph)

    assertEquals(result.verdicts.size, 2)
    // First verifier fails (HTTP to unreachable URL)
    val httpVerdict = result.verdicts.find(_.verifierId == "v-stop-http").get
    assert(httpVerdict.status == VerdictStatus.Fail || httpVerdict.status == VerdictStatus.Timeout,
      s"Expected Fail or Timeout, got ${httpVerdict.status}")
    // Second verifier should be Blocked due to stopOnFailure
    val stateVerdict = result.verdicts.find(_.verifierId == "v-stop-state").get
    assertEquals(stateVerdict.status, VerdictStatus.Blocked)
    assert(stateVerdict.failureMessage.exists(_.contains("stopped_on_failure")))
  }

  test("Important-priority failure does not block overall success") {
    // Required req passes, Important req fails → overall should still pass
    val requiredNode = makeStateNode("req-required")
    val importantNode = RequirementNode(
      requirementId = "req-important",
      humanDescription = "Important failing HTTP",
      machineDescription = "Important failing HTTP",
      priority = RequirementPriority.Important,
      category = RequirementCategory.ApiContract,
      dependencies = Set.empty,
      verifiers = List(VerifierSpec(
        verifierId = "v-important",
        verifierType = VerifierType.HttpApiContract,
        displayName = "Important HTTP",
        requirementId = "req-important",
        executionLayer = 0,
        parallelSafe = true,
        timeout = Duration.ofSeconds(2),
        maxRetries = 0,
        retryDelayMs = 0,
        browserFlowSpec = None,
        apiContractSpec = Some(ApiContractVerifierSpec(
          method = "GET",
          path = "http://localhost:19999/nonexistent",
          expectedStatus = 200,
        )),
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
    val graph = makeGraph(List(requiredNode, importantNode))
    val result = VerificationEngine.runVerification("run-1", 1, graph)

    // Required passes (StateVerifier always passes), Important fails
    assertEquals(result.aggregate.overallVerdict, VerdictStatus.Pass,
      "Important-priority failure should not block success when Required passes")
  }
}
