package demiurge.verification

import munit.FunSuite
import java.time.Instant
import demiurge.model._

class VerdictAggregatorSuite extends FunSuite {

  // --- Legacy aggregate tests ---

  test("all pass yields Pass verdict") {
    val outcomes = List(
      ("v-1", VerifierOutcome.Passed),
      ("v-2", VerifierOutcome.Passed),
      ("v-3", VerifierOutcome.Passed),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Pass)
    assertEquals(result.passCount, 3)
    assertEquals(result.failCount, 0)
    assertEquals(result.total, 3)
  }

  test("any failure yields Fail verdict") {
    val outcomes = List(
      ("v-1", VerifierOutcome.Passed),
      ("v-2", VerifierOutcome.Failed("bad")),
      ("v-3", VerifierOutcome.Passed),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
    assertEquals(result.passCount, 2)
    assertEquals(result.failCount, 1)
  }

  test("errors treated as failure") {
    val outcomes = List(
      ("v-1", VerifierOutcome.Passed),
      ("v-2", VerifierOutcome.Error("crash")),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
    assertEquals(result.errorCount, 1)
  }

  test("timeouts treated as failure") {
    val outcomes = List(
      ("v-1", VerifierOutcome.TimedOut),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
    assertEquals(result.timeoutCount, 1)
  }

  test("empty outcomes yields Pass") {
    val result = VerdictAggregator.aggregate(Nil)
    assertEquals(result.overallVerdict, VerdictStatus.Pass)
    assertEquals(result.total, 0)
  }

  test("mixed failures, errors, and timeouts") {
    val outcomes = List(
      ("v-1", VerifierOutcome.Passed),
      ("v-2", VerifierOutcome.Failed("fail")),
      ("v-3", VerifierOutcome.Error("error")),
      ("v-4", VerifierOutcome.TimedOut),
      ("v-5", VerifierOutcome.Passed),
    )
    val result = VerdictAggregator.aggregate(outcomes)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
    assertEquals(result.passCount, 2)
    assertEquals(result.failCount, 1)
    assertEquals(result.errorCount, 1)
    assertEquals(result.timeoutCount, 1)
    assertEquals(result.total, 5)
  }

  // --- Spec §4.3: requirementVerdict tests ---

  test("requirementVerdict: all Pass → Pass") {
    assertEquals(
      VerdictAggregator.requirementVerdict(List(VerdictStatus.Pass, VerdictStatus.Pass)),
      VerdictStatus.Pass,
    )
  }

  test("requirementVerdict: mix Pass and Flake → Flake") {
    assertEquals(
      VerdictAggregator.requirementVerdict(List(VerdictStatus.Pass, VerdictStatus.Flake)),
      VerdictStatus.Flake,
    )
  }

  test("requirementVerdict: Blocked without Fail → Blocked") {
    assertEquals(
      VerdictAggregator.requirementVerdict(List(VerdictStatus.Pass, VerdictStatus.Blocked)),
      VerdictStatus.Blocked,
    )
  }

  test("requirementVerdict: any Fail → Fail") {
    assertEquals(
      VerdictAggregator.requirementVerdict(List(VerdictStatus.Pass, VerdictStatus.Fail)),
      VerdictStatus.Fail,
    )
  }

  test("requirementVerdict: Timeout → Fail") {
    assertEquals(
      VerdictAggregator.requirementVerdict(List(VerdictStatus.Pass, VerdictStatus.Timeout)),
      VerdictStatus.Fail,
    )
  }

  test("requirementVerdict: only Inconclusive → Inconclusive") {
    assertEquals(
      VerdictAggregator.requirementVerdict(List(VerdictStatus.Inconclusive)),
      VerdictStatus.Inconclusive,
    )
  }

  test("requirementVerdict: empty → Pass") {
    assertEquals(VerdictAggregator.requirementVerdict(Nil), VerdictStatus.Pass)
  }

  // --- Spec §4.4: aggregateWithGraph priority-aware tests ---

  private def makeVerdict(
    reqId: String,
    verifierId: String,
    status: VerdictStatus,
  ): RequirementVerdict = RequirementVerdict(
    verdictId = s"vd-$verifierId",
    runId = "run-1",
    attemptNumber = 1,
    requirementId = reqId,
    verifierId = verifierId,
    status = status,
    executionDurationMs = 100,
    retryCount = 0,
    observations = Nil,
    evidenceRefs = Nil,
    failureClass = None,
    failureMessage = None,
    suggestedRerunScope = None,
    confidence = 1.0,
    producedAt = Instant.EPOCH,
  )

  private def makeNode(
    reqId: String,
    priority: RequirementPriority,
  ): RequirementNode = RequirementNode(
    requirementId = reqId,
    humanDescription = reqId,
    machineDescription = reqId,
    priority = priority,
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
    graphId = "g-1",
    runId = "run-1",
    nodes = nodes,
    edges = Nil,
    generatedAt = Instant.EPOCH,
    inferenceRequestId = None,
    warnings = Nil,
  )

  test("§4.4: Important failure does NOT block success when Required passes") {
    val graph = makeGraph(List(
      makeNode("req-r", RequirementPriority.Required),
      makeNode("req-i", RequirementPriority.Important),
    ))
    val verdicts = List(
      makeVerdict("req-r", "v-r", VerdictStatus.Pass),
      makeVerdict("req-i", "v-i", VerdictStatus.Fail),
    )
    val result = VerdictAggregator.aggregateWithGraph(verdicts, graph)
    assertEquals(result.overallVerdict, VerdictStatus.Pass)
    assertEquals(result.failCount, 1)
  }

  test("§4.4: NiceToHave failure does NOT block success when Required passes") {
    val graph = makeGraph(List(
      makeNode("req-r", RequirementPriority.Required),
      makeNode("req-n", RequirementPriority.NiceToHave),
    ))
    val verdicts = List(
      makeVerdict("req-r", "v-r", VerdictStatus.Pass),
      makeVerdict("req-n", "v-n", VerdictStatus.Fail),
    )
    val result = VerdictAggregator.aggregateWithGraph(verdicts, graph)
    assertEquals(result.overallVerdict, VerdictStatus.Pass)
  }

  test("§4.4: Required failure blocks success") {
    val graph = makeGraph(List(
      makeNode("req-r1", RequirementPriority.Required),
      makeNode("req-r2", RequirementPriority.Required),
    ))
    val verdicts = List(
      makeVerdict("req-r1", "v-r1", VerdictStatus.Pass),
      makeVerdict("req-r2", "v-r2", VerdictStatus.Fail),
    )
    val result = VerdictAggregator.aggregateWithGraph(verdicts, graph)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
  }

  test("§4.5: Required Flake → overall Flake") {
    val graph = makeGraph(List(
      makeNode("req-r", RequirementPriority.Required),
    ))
    val verdicts = List(
      makeVerdict("req-r", "v-r", VerdictStatus.Flake),
    )
    val result = VerdictAggregator.aggregateWithGraph(verdicts, graph)
    assertEquals(result.overallVerdict, VerdictStatus.Flake)
    assertEquals(result.flakeCount, 1)
  }

  test("§4.4: all Required pass, Important and NiceToHave fail → Pass") {
    val graph = makeGraph(List(
      makeNode("req-r", RequirementPriority.Required),
      makeNode("req-i", RequirementPriority.Important),
      makeNode("req-n", RequirementPriority.NiceToHave),
    ))
    val verdicts = List(
      makeVerdict("req-r", "v-r", VerdictStatus.Pass),
      makeVerdict("req-i", "v-i", VerdictStatus.Fail),
      makeVerdict("req-n", "v-n", VerdictStatus.Timeout),
    )
    val result = VerdictAggregator.aggregateWithGraph(verdicts, graph)
    assertEquals(result.overallVerdict, VerdictStatus.Pass)
    assertEquals(result.failCount, 1)
    assertEquals(result.timeoutCount, 1)
    assertEquals(result.total, 3)
  }

  test("§4.4: no Required requirements → Pass") {
    val graph = makeGraph(List(
      makeNode("req-i", RequirementPriority.Important),
    ))
    val verdicts = List(
      makeVerdict("req-i", "v-i", VerdictStatus.Fail),
    )
    val result = VerdictAggregator.aggregateWithGraph(verdicts, graph)
    assertEquals(result.overallVerdict, VerdictStatus.Pass)
  }

  test("§4.3: multiple verifiers per requirement aggregated correctly") {
    val graph = makeGraph(List(
      makeNode("req-r", RequirementPriority.Required),
    ))
    // Two verifiers for the same requirement — one pass, one flake → requirement = Flake
    val verdicts = List(
      makeVerdict("req-r", "v-r1", VerdictStatus.Pass),
      makeVerdict("req-r", "v-r2", VerdictStatus.Flake),
    )
    val result = VerdictAggregator.aggregateWithGraph(verdicts, graph)
    assertEquals(result.overallVerdict, VerdictStatus.Flake)
  }

  test("aggregateWithGraph counts blockedCount") {
    val graph = makeGraph(List(
      makeNode("req-r", RequirementPriority.Required),
    ))
    val verdicts = List(
      makeVerdict("req-r", "v-r", VerdictStatus.Blocked),
    )
    val result = VerdictAggregator.aggregateWithGraph(verdicts, graph)
    assertEquals(result.blockedCount, 1)
    assertEquals(result.overallVerdict, VerdictStatus.Fail)
  }
}
