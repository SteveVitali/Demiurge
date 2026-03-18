package demiurge.model

import java.time.Instant

// Spec §3.2: RequirementGraph
case class RequirementGraph(
  graphId:            String,
  runId:              String,
  nodes:              List[RequirementNode],
  edges:              List[DependencyEdge],
  generatedAt:        Instant,
  inferenceRequestId: Option[String],
  warnings:           List[GraphWarning],
)

// Spec §3.2: DependencyEdge
case class DependencyEdge(
  fromRequirementId:  String,
  toRequirementId:    String,
  edgeType:           DependencyEdgeType,
)

// Spec §3.2: GraphWarning
case class GraphWarning(
  code:               String,
  message:            String,
  affectedNodeIds:    List[String],
)

// Spec §3.2: RequirementNode
case class RequirementNode(
  requirementId:      String,
  humanDescription:   String,
  machineDescription: String,
  priority:           RequirementPriority,
  category:           RequirementCategory,
  dependencies:       Set[String],
  verifiers:          List[VerifierSpec],
  evidenceRequired:   List[ArtifactType],
  destructiveRiskLevel: Int,
  inferredFrom:       List[String],
  confidence:         Double,
  stopOnFailure:      Boolean,
)
