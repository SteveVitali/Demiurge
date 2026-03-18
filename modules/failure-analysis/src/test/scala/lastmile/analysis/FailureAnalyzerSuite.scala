package lastmile.analysis

import java.time.Instant
import java.util.UUID

import lastmile.model._
import lastmile.inference._

class FailureAnalyzerSuite extends munit.FunSuite {

  private def mkVerdict(
    status: VerdictStatus = VerdictStatus.Fail,
    failureClass: Option[FailureClass] = Some(FailureClass.BackendContractFailure),
    failureMessage: Option[String] = Some("Expected 200, got 500"),
  ): RequirementVerdict = RequirementVerdict(
    verdictId = UUID.randomUUID().toString,
    runId = "run-1",
    attemptNumber = 1,
    requirementId = s"req-${UUID.randomUUID().toString.take(8)}",
    verifierId = s"ver-${UUID.randomUUID().toString.take(8)}",
    status = status,
    executionDurationMs = 100,
    retryCount = 0,
    observations = Nil,
    evidenceRefs = List("artifact-1"),
    failureClass = failureClass,
    failureMessage = failureMessage,
    suggestedRerunScope = None,
    confidence = 0.8,
    producedAt = Instant.now(),
  )

  private def mkGraph(): RequirementGraph = RequirementGraph(
    graphId = UUID.randomUUID().toString,
    runId = "run-1",
    nodes = Nil,
    edges = Nil,
    generatedAt = Instant.now(),
    inferenceRequestId = None,
    warnings = Nil,
  )

  test("rule-based analysis produces low-confidence FailurePacket") {
    val analyzer = new FailureAnalyzerImpl(inferenceService = None)
    val verdicts = List(mkVerdict(), mkVerdict(status = VerdictStatus.Timeout, failureClass = Some(FailureClass.BrowserTimingFlake)))

    val packet = analyzer.analyze("run-1", 1, verdicts, mkGraph(), "Fix the login page", Some(List("src/login.tsx")))

    assertEquals(packet.runId, "run-1")
    assertEquals(packet.attemptNumber, 1)
    assert(packet.affectedRequirementIds.nonEmpty)
    assert(packet.suspectedRootCauses.nonEmpty)
    assertEquals(packet.suspectedRootCauses.head.confidence, 0.3)
    assert(packet.inferenceRequestId.isEmpty)
  }

  test("inference-backed analysis produces higher-confidence packet") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val backend = new MockInferenceBackend()
    val svc = new InferenceServiceImpl(backend, budget, cache)
    val analyzer = new FailureAnalyzerImpl(inferenceService = Some(svc))

    val verdicts = List(mkVerdict())
    val packet = analyzer.analyze("run-1", 1, verdicts, mkGraph(), "Fix the API", Some(List("src/api.ts")))

    assertEquals(packet.runId, "run-1")
    assert(packet.inferenceRequestId.isDefined)
    assert(packet.suspectedRootCauses.head.confidence > 0.3)
  }

  test("inference failure falls back to rule-based packet") {
    val budget = new InferenceBudgetState
    val cache = new InMemoryInferenceCache
    val err = InferenceError.ProviderError("req-1", 500, "Server error")
    val backend = new MockInferenceBackend(
      responses = Map("failure_analyzer" -> Left(err))
    )
    val svc = new InferenceServiceImpl(backend, budget, cache)
    val analyzer = new FailureAnalyzerImpl(inferenceService = Some(svc))

    val verdicts = List(mkVerdict())
    val packet = analyzer.analyze("run-1", 1, verdicts, mkGraph(), "Fix bug", None)

    // Should fall back to rule-based (no inferenceRequestId, low confidence)
    assert(packet.inferenceRequestId.isEmpty)
    assertEquals(packet.suspectedRootCauses.head.confidence, 0.3)
  }

  test("empty verdicts produce UnknownFailure packet") {
    val analyzer = new FailureAnalyzerImpl(inferenceService = None)
    val packet = analyzer.analyze("run-1", 1, Nil, mkGraph(), "task", None)

    assertEquals(packet.primaryFailureClass, FailureClass.UnknownFailure)
    assert(packet.affectedRequirementIds.isEmpty)
  }

  test("primary failure class is most common among verdicts") {
    val analyzer = new FailureAnalyzerImpl(inferenceService = None)
    val verdicts = List(
      mkVerdict(failureClass = Some(FailureClass.BackendContractFailure)),
      mkVerdict(failureClass = Some(FailureClass.BackendContractFailure)),
      mkVerdict(failureClass = Some(FailureClass.PersistenceFailure)),
    )
    val packet = analyzer.analyze("run-1", 1, verdicts, mkGraph(), "task", None)
    assertEquals(packet.primaryFailureClass, FailureClass.BackendContractFailure)
  }

  test("reproduction steps generated from failed verdicts") {
    val analyzer = new FailureAnalyzerImpl(inferenceService = None)
    val verdicts = List(mkVerdict(), mkVerdict())
    val packet = analyzer.analyze("run-1", 1, verdicts, mkGraph(), "task", None)

    assertEquals(packet.reproductionSteps.size, 2)
    assertEquals(packet.reproductionSteps.head.order, 1)
    assertEquals(packet.reproductionSteps(1).order, 2)
  }
}
