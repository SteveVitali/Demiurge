package demiurge.verification

import munit.FunSuite
import java.time.{Duration, Instant}
import demiurge.model._

class VerificationPlannerSuite extends FunSuite {

  private def makeSpec(
    id: String,
    reqId: String,
    vType: VerifierType,
    layer: Int = -1,
    parallelSafe: Boolean = true,
  ): VerifierSpec = {
    VerifierSpec(
      verifierId = id,
      verifierType = vType,
      displayName = s"Test $id",
      requirementId = reqId,
      executionLayer = layer,
      parallelSafe = parallelSafe,
      timeout = Duration.ofSeconds(5),
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
    )
  }

  private def makeNode(reqId: String, specs: List[VerifierSpec]): RequirementNode = {
    RequirementNode(
      requirementId = reqId,
      humanDescription = s"Req $reqId",
      machineDescription = s"Req $reqId",
      priority = RequirementPriority.Required,
      category = RequirementCategory.UiFlow,
      dependencies = Set.empty,
      verifiers = specs,
      evidenceRequired = Nil,
      destructiveRiskLevel = 0,
      inferredFrom = Nil,
      confidence = 1.0,
      stopOnFailure = false,
    )
  }

  private def makeGraph(nodes: List[RequirementNode], edges: List[DependencyEdge] = Nil): RequirementGraph = {
    RequirementGraph(
      graphId = "test-graph",
      runId = "test-run",
      nodes = nodes,
      edges = edges,
      generatedAt = Instant.EPOCH,
      inferenceRequestId = None,
      warnings = Nil,
    )
  }

  test("buildPlan assigns default layers by verifier type") {
    val specs = List(
      makeSpec("v1", "r1", VerifierType.EnvironmentReadiness),
      makeSpec("v2", "r2", VerifierType.HttpApiContract),
      makeSpec("v3", "r3", VerifierType.BrowserFlow),
      makeSpec("v4", "r4", VerifierType.QueueJob),
      makeSpec("v5", "r5", VerifierType.TargetedRegression),
    )
    val nodes = specs.map(s => makeNode(s.requirementId, List(s)))
    val graph = makeGraph(nodes)

    val plan = VerificationPlanner.buildPlan(graph)

    assertEquals(plan.totalVerifierCount, 5)
    assertEquals(plan.layers.size, 5)
    // Layer 0: EnvironmentReadiness
    assertEquals(plan.layers(0).verifiers.map(_.verifierId), List("v1"))
    // Layer 1: HttpApiContract
    assertEquals(plan.layers(1).verifiers.map(_.verifierId), List("v2"))
    // Layer 2: BrowserFlow
    assertEquals(plan.layers(2).verifiers.map(_.verifierId), List("v3"))
    // Layer 3: QueueJob
    assertEquals(plan.layers(3).verifiers.map(_.verifierId), List("v4"))
    // Layer 4: TargetedRegression
    assertEquals(plan.layers(4).verifiers.map(_.verifierId), List("v5"))
  }

  test("explicit executionLayer overrides default") {
    val spec = makeSpec("v1", "r1", VerifierType.HttpApiContract, layer = 4)
    val graph = makeGraph(List(makeNode("r1", List(spec))))

    val plan = VerificationPlanner.buildPlan(graph)

    // Should be in layer 4, not default layer 1
    assert(plan.layers(4).verifiers.exists(_.verifierId == "v1"))
    assert(plan.layers(1).verifiers.isEmpty)
  }

  test("BrowserFlow verifiers are never parallel-safe") {
    val specs = List(
      makeSpec("v1", "r1", VerifierType.BrowserFlow, parallelSafe = true),
      makeSpec("v2", "r2", VerifierType.BrowserFlow, parallelSafe = true),
    )
    val nodes = specs.map(s => makeNode(s.requirementId, List(s)))
    val graph = makeGraph(nodes)

    val plan = VerificationPlanner.buildPlan(graph)

    // Each BrowserFlow should be in its own sequential group
    val layer2Groups = plan.parallelGroups.filter(_.layerIndex == 2)
    assert(layer2Groups.forall(_.verifierIds.size == 1),
      "BrowserFlow verifiers should each be in their own group")
  }

  test("parallel-safe verifiers in same layer form a single group") {
    val specs = List(
      makeSpec("v1", "r1", VerifierType.HttpApiContract),
      makeSpec("v2", "r2", VerifierType.HttpApiContract),
      makeSpec("v3", "r3", VerifierType.ConsoleLogSanity),
    )
    val nodes = specs.map(s => makeNode(s.requirementId, List(s)))
    val graph = makeGraph(nodes)

    val plan = VerificationPlanner.buildPlan(graph)

    val layer1Groups = plan.parallelGroups.filter(_.layerIndex == 1)
    // All 3 should be in one parallel group
    val parallelGroup = layer1Groups.find(_.verifierIds.size > 1)
    assert(parallelGroup.isDefined, "Should have a parallel group with multiple verifiers")
    assertEquals(parallelGroup.get.verifierIds.size, 3)
  }

  test("empty graph produces empty plan") {
    val graph = makeGraph(Nil)
    val plan = VerificationPlanner.buildPlan(graph)

    assertEquals(plan.totalVerifierCount, 0)
    assertEquals(plan.parallelGroups.size, 0)
  }

  test("isBlocked returns true when hard dependency not passed") {
    val spec = makeSpec("v2", "r2", VerifierType.HttpApiContract)
    val graph = makeGraph(
      List(makeNode("r1", Nil), makeNode("r2", List(spec))),
      edges = List(DependencyEdge("r1", "r2", DependencyEdgeType.Hard)),
    )

    // r1 not yet in verdicts — should be blocked
    val blocked = VerificationPlanner.isBlocked(spec, graph, Map.empty)
    assert(blocked, "Should be blocked when hard dependency has no verdict")
  }

  test("isBlocked returns false when hard dependency passed") {
    val spec = makeSpec("v2", "r2", VerifierType.HttpApiContract)
    val graph = makeGraph(
      List(makeNode("r1", Nil), makeNode("r2", List(spec))),
      edges = List(DependencyEdge("r1", "r2", DependencyEdgeType.Hard)),
    )

    val notBlocked = VerificationPlanner.isBlocked(spec, graph, Map("r1" -> VerdictStatus.Pass))
    assert(!notBlocked, "Should not be blocked when hard dependency passed")
  }

  test("isBlocked returns false when hard dependency is Flake") {
    val spec = makeSpec("v2", "r2", VerifierType.HttpApiContract)
    val graph = makeGraph(
      List(makeNode("r1", Nil), makeNode("r2", List(spec))),
      edges = List(DependencyEdge("r1", "r2", DependencyEdgeType.Hard)),
    )

    val notBlocked = VerificationPlanner.isBlocked(spec, graph, Map("r1" -> VerdictStatus.Flake))
    assert(!notBlocked, "Should not be blocked when hard dependency is Flake")
  }

  test("isBlocked ignores Soft dependencies") {
    val spec = makeSpec("v2", "r2", VerifierType.HttpApiContract)
    val graph = makeGraph(
      List(makeNode("r1", Nil), makeNode("r2", List(spec))),
      edges = List(DependencyEdge("r1", "r2", DependencyEdgeType.Soft)),
    )

    val notBlocked = VerificationPlanner.isBlocked(spec, graph, Map.empty)
    assert(!notBlocked, "Soft dependencies should not block execution")
  }

  test("parallelSafe=false forces sequential execution") {
    val specs = List(
      makeSpec("v1", "r1", VerifierType.HttpApiContract, parallelSafe = false),
      makeSpec("v2", "r2", VerifierType.HttpApiContract, parallelSafe = true),
    )
    val nodes = specs.map(s => makeNode(s.requirementId, List(s)))
    val graph = makeGraph(nodes)

    val plan = VerificationPlanner.buildPlan(graph)

    val layer1Groups = plan.parallelGroups.filter(_.layerIndex == 1)
    // v1 should be in its own group, v2 in another
    assert(layer1Groups.exists(g => g.verifierIds == List("v1")),
      "parallelSafe=false verifier should be in its own group")
  }
}
