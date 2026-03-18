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
          urlTemplate = "http://localhost:19999/nonexistent",
          headers = Map.empty,
          bodyTemplate = None,
          expectedStatus = 200,
          responseAssertions = Nil,
          artifactPlan = Nil,
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
}
