package lastmile.repair

import munit.FunSuite
import java.time.{Duration, Instant}
import java.util.UUID

import lastmile.model._

class FailurePacketSuite extends FunSuite {

  private def makeVerdict(
    requirementId: String,
    status: VerdictStatus,
    failureMessage: Option[String] = None,
    failureClass: Option[FailureClass] = None,
  ): RequirementVerdict = {
    RequirementVerdict(
      verdictId = UUID.randomUUID().toString,
      runId = "run-1",
      attemptNumber = 1,
      requirementId = requirementId,
      verifierId = s"verifier-$requirementId",
      status = status,
      executionDurationMs = 100L,
      retryCount = 0,
      observations = Nil,
      evidenceRefs = Nil,
      failureClass = failureClass,
      failureMessage = failureMessage,
      suggestedRerunScope = None,
      confidence = 1.0,
      producedAt = Instant.now(),
    )
  }

  private def makeGraph(requirementIds: List[String]): RequirementGraph = {
    val nodes = requirementIds.map { id =>
      RequirementNode(
        requirementId = id,
        humanDescription = s"Requirement $id",
        machineDescription = s"Machine desc for $id",
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
    }
    RequirementGraph(
      graphId = "graph-1",
      runId = "run-1",
      nodes = nodes,
      edges = Nil,
      generatedAt = Instant.now(),
      inferenceRequestId = None,
      warnings = Nil,
    )
  }

  test("builds failure packet from failed verdicts") {
    val verdicts = List(
      makeVerdict("req-1", VerdictStatus.Pass),
      makeVerdict("req-2", VerdictStatus.Fail, Some("HTTP 500"), Some(FailureClass.BackendContractFailure)),
      makeVerdict("req-3", VerdictStatus.Fail, Some("Timeout"), Some(FailureClass.SuspectedNondeterminism)),
    )

    val input = FailurePacketBuilder.FailurePacketInput(
      runId = "run-1",
      attemptNumber = 1,
      taskText = "Fix the login page",
      verdicts = verdicts,
      graph = makeGraph(List("req-1", "req-2", "req-3")),
      inspectionReport = None,
      runtimePlan = None,
      patchHistory = Nil,
      logs = None,
    )

    val packet = FailurePacketBuilder.build(input)

    assertEquals(packet.runId, "run-1")
    assertEquals(packet.attemptNumber, 1)
    assertEquals(packet.primaryFailureClass, FailureClass.BackendContractFailure)
    assertEquals(packet.affectedRequirementIds.sorted, List("req-2", "req-3"))
    assert(packet.summary.contains("2 of 3"))
    assert(packet.failurePacketId.nonEmpty)
  }

  test("builds failure packet with unknown failure class when none specified") {
    val verdicts = List(
      makeVerdict("req-1", VerdictStatus.Fail, Some("Something failed")),
    )

    val input = FailurePacketBuilder.FailurePacketInput(
      runId = "run-2",
      attemptNumber = 1,
      taskText = "Fix it",
      verdicts = verdicts,
      graph = makeGraph(List("req-1")),
      inspectionReport = None,
      runtimePlan = None,
      patchHistory = Nil,
      logs = None,
    )

    val packet = FailurePacketBuilder.build(input)
    assertEquals(packet.primaryFailureClass, FailureClass.UnknownFailure)
  }

  test("includes suspected causes from failure messages") {
    val verdicts = List(
      makeVerdict("req-1", VerdictStatus.Fail, Some("Connection refused"), Some(FailureClass.IntegrationFailure)),
    )

    val input = FailurePacketBuilder.FailurePacketInput(
      runId = "run-3",
      attemptNumber = 1,
      taskText = "Deploy service",
      verdicts = verdicts,
      graph = makeGraph(List("req-1")),
      inspectionReport = None,
      runtimePlan = None,
      patchHistory = Nil,
      logs = None,
    )

    val packet = FailurePacketBuilder.build(input)
    assertEquals(packet.suspectedRootCauses.size, 1)
    assertEquals(packet.suspectedRootCauses.head.description, "Connection refused")
  }

  test("handles all-pass verdicts (no failures)") {
    val verdicts = List(
      makeVerdict("req-1", VerdictStatus.Pass),
      makeVerdict("req-2", VerdictStatus.Pass),
    )

    val input = FailurePacketBuilder.FailurePacketInput(
      runId = "run-4",
      attemptNumber = 1,
      taskText = "Test",
      verdicts = verdicts,
      graph = makeGraph(List("req-1", "req-2")),
      inspectionReport = None,
      runtimePlan = None,
      patchHistory = Nil,
      logs = None,
    )

    val packet = FailurePacketBuilder.build(input)
    assertEquals(packet.affectedRequirementIds, Nil)
    assertEquals(packet.primaryFailureClass, FailureClass.UnknownFailure)
    assert(packet.summary.contains("0 of 2"))
  }

  test("failure packet has unique ID") {
    val verdicts = List(makeVerdict("req-1", VerdictStatus.Fail, Some("fail")))
    val input = FailurePacketBuilder.FailurePacketInput(
      runId = "run-5", attemptNumber = 1, taskText = "t",
      verdicts = verdicts, graph = makeGraph(List("req-1")),
      inspectionReport = None, runtimePlan = None, patchHistory = Nil, logs = None,
    )

    val p1 = FailurePacketBuilder.build(input)
    val p2 = FailurePacketBuilder.build(input)
    assertNotEquals(p1.failurePacketId, p2.failurePacketId)
  }
}
