package lastmile.model

// Spec §3.2: OrderedVerifierPlan
case class OrderedVerifierPlan(
  layers:             List[VerifierLayer],
  totalVerifierCount: Int,
  parallelGroups:     List[ParallelGroup],
)

// Spec §3.2: VerifierLayer
case class VerifierLayer(
  layerIndex:         Int,
  verifiers:          List[VerifierSpec],
)

// Spec §3.2: ParallelGroup
case class ParallelGroup(
  layerIndex:         Int,
  verifierIds:        List[String],
)

// Spec §3.2: PromptPackageArtifact
case class PromptPackageArtifact(
  artifactRecord:     ArtifactRecord,
  textContent:        String,
  includedArtifacts:  List[String],
  truncatedArtifacts: List[String],
  omittedArtifacts:   List[String],
)
