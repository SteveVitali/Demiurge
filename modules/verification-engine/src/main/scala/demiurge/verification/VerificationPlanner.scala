package demiurge.verification

import demiurge.model._

// Spec §12.3–12.4: Layer-based verification planning.
// Assigns verifiers to execution layers 0–4, computes parallel groups,
// and enforces blocked detection via dependency graph.
object VerificationPlanner {

  /** Ordered plan: layers contain parallel groups of verifier IDs. */
  case class OrderedVerifierPlan(
    layers:             List[VerifierLayer],
    totalVerifierCount: Int,
    parallelGroups:     List[ParallelGroup],
  )

  case class VerifierLayer(
    layerIndex: Int,
    verifiers:  List[VerifierSpec],
  )

  case class ParallelGroup(
    layerIndex:  Int,
    verifierIds: List[String],
  )

  /**
   * Build an ordered execution plan from a RequirementGraph.
   * Spec §12.3: Layer assignment rules:
   *   Layer 0 — EnvironmentReadiness
   *   Layer 1 — HttpApiContract, ConsoleLogSanity, NetworkExpectation
   *   Layer 2 — BrowserFlow, StateAssertion (default)
   *   Layer 3 — QueueJob, PersistenceReload
   *   Layer 4 — TargetedRegression
   * Explicit executionLayer in VerifierSpec overrides the default.
   */
  def buildPlan(graph: RequirementGraph): OrderedVerifierPlan = {
    val allSpecs = graph.nodes.flatMap(_.verifiers)
    val edgeMap = buildDependencyMap(graph)
    val reqMap = graph.nodes.map(n => n.requirementId -> n).toMap

    // Group by layer
    val byLayer = allSpecs.groupBy(effectiveLayer).toList.sortBy(_._1)

    val layers = (0 to 4).map { idx =>
      val specs = byLayer.find(_._1 == idx).map(_._2).getOrElse(Nil)
      VerifierLayer(idx, specs)
    }.toList

    // Compute parallel groups per layer
    val parallelGroups = layers.flatMap { layer =>
      computeParallelGroups(layer, edgeMap, reqMap)
    }

    OrderedVerifierPlan(
      layers = layers,
      totalVerifierCount = allSpecs.size,
      parallelGroups = parallelGroups,
    )
  }

  /** Spec §12.3: Default layer assignment by verifier type. */
  private def defaultLayer(vt: VerifierType): Int = vt match {
    case VerifierType.EnvironmentReadiness => 0
    case VerifierType.HttpApiContract      => 1
    case VerifierType.ConsoleLogSanity     => 1
    case VerifierType.NetworkExpectation   => 1
    case VerifierType.BrowserFlow          => 2
    case VerifierType.StateAssertion       => 2
    case VerifierType.QueueJob             => 3
    case VerifierType.PersistenceReload    => 3
    case VerifierType.TargetedRegression   => 4
  }

  /** Use explicit executionLayer if set (>= 0), otherwise default. */
  private def effectiveLayer(spec: VerifierSpec): Int = {
    if (spec.executionLayer >= 0) spec.executionLayer
    else defaultLayer(spec.verifierType)
  }

  /**
   * Spec §12.4: Compute parallel groups within a layer.
   * A verifier has parallelSafe = true if ALL of:
   *   1. Type is NOT BrowserFlow and NOT PersistenceReload
   *   2. Type is NOT StateAssertion with readOnly=false (approximated: always safe for now)
   *   3. No Hard/Ordering dependency on another verifier in the same group
   *   4. Does not share a serviceId with a destructiveRiskLevel > 0 verifier
   *   5. Manifest does not explicitly set parallelSafe = false
   */
  private def computeParallelGroups(
    layer:    VerifierLayer,
    edgeMap:  Map[String, Set[String]],
    reqMap:   Map[String, RequirementNode],
  ): List[ParallelGroup] = {
    if (layer.verifiers.isEmpty) return Nil

    // Partition into parallel-safe and sequential
    val (safe, sequential) = layer.verifiers.partition { spec =>
      isParallelSafe(spec, layer.verifiers, edgeMap, reqMap)
    }

    val groups = scala.collection.mutable.ListBuffer[ParallelGroup]()

    // Safe verifiers form one parallel group
    if (safe.nonEmpty) {
      groups += ParallelGroup(layer.layerIndex, safe.map(_.verifierId))
    }

    // Sequential verifiers each get their own group (ordered by dependency)
    sequential.foreach { spec =>
      groups += ParallelGroup(layer.layerIndex, List(spec.verifierId))
    }

    groups.toList
  }

  private def isParallelSafe(
    spec:       VerifierSpec,
    layerSpecs: List[VerifierSpec],
    edgeMap:    Map[String, Set[String]],
    reqMap:     Map[String, RequirementNode],
  ): Boolean = {
    // Rule 1: BrowserFlow and PersistenceReload are never parallel-safe
    if (spec.verifierType == VerifierType.BrowserFlow) return false
    if (spec.verifierType == VerifierType.PersistenceReload) return false

    // Rule 5: Explicit parallelSafe = false
    if (!spec.parallelSafe) return false

    // Rule 3: Check for Hard/Ordering dependency on another verifier in the same layer
    val layerReqIds = layerSpecs.map(_.requirementId).toSet
    val deps = edgeMap.getOrElse(spec.requirementId, Set.empty)
    if (deps.intersect(layerReqIds).nonEmpty) return false

    // Rule 4: Check for shared serviceId with destructiveRiskLevel > 0
    val myNode = reqMap.get(spec.requirementId)
    val myRisk = myNode.map(_.destructiveRiskLevel).getOrElse(0)
    if (myRisk > 0) {
      // Check if any other verifier in the layer shares requirement category
      val otherHighRisk = layerSpecs.filter(_.verifierId != spec.verifierId).exists { other =>
        val otherNode = reqMap.get(other.requirementId)
        otherNode.exists(_.destructiveRiskLevel > 0)
      }
      if (otherHighRisk) return false
    }

    true
  }

  /** Build requirement dependency map: reqId → set of reqIds it depends on (Hard + Ordering). */
  private def buildDependencyMap(graph: RequirementGraph): Map[String, Set[String]] = {
    graph.edges
      .filter(e => e.edgeType == DependencyEdgeType.Hard || e.edgeType == DependencyEdgeType.Ordering)
      .groupBy(_.toRequirementId)
      .map { case (reqId, edges) => reqId -> edges.map(_.fromRequirementId).toSet }
  }

  /**
   * Spec §12.4: Blocked detection.
   * Before executing a verifier, check that all Hard dependency requirements
   * have verdict Pass or Flake in this attempt.
   */
  def isBlocked(
    spec:     VerifierSpec,
    graph:    RequirementGraph,
    verdicts: Map[String, VerdictStatus],
  ): Boolean = {
    val hardDeps = graph.edges
      .filter(e => e.edgeType == DependencyEdgeType.Hard && e.toRequirementId == spec.requirementId)
      .map(_.fromRequirementId)

    hardDeps.exists { depReqId =>
      verdicts.get(depReqId) match {
        case Some(VerdictStatus.Pass) | Some(VerdictStatus.Flake) => false
        case _ => true // Not yet run, or failed/blocked/timeout
      }
    }
  }
}
