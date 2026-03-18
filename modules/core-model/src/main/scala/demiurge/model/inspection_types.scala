package demiurge.model

import java.nio.file.Path
import java.time.Instant

// Spec §3.2: RepoInspectionReport
case class RepoInspectionReport(
  reportId:           String,
  runId:              String,
  inspectedAt:        Instant,
  repoRoot:           Path,
  languages:          List[ScoredInference[String]],
  frameworks:         List[ScoredInference[String]],
  candidateServices:  List[CandidateService],
  startupCommands:    List[ScoredInference[String]],
  healthEndpointHints: List[ScoredInference[String]],
  dbDependencies:     List[ScoredInference[String]],
  queueDependencies:  List[ScoredInference[String]],
  frontendEntrypoints: List[ScoredInference[String]],
  apiBasePaths:       List[ScoredInference[String]],
  testFrameworkHints: List[ScoredInference[String]],
  authHints:          List[ScoredInference[String]],
  changedSurfaceMap:  Option[ImpactMap],
  manifestsFound:     List[ManifestRef],
  warnings:           List[String],
)

// Spec §3.2: ScoredInference[T] (generic)
case class ScoredInference[T](
  value:              T,
  confidence:         Double,
  provenance:         String,
)

// Spec §3.2: CandidateService
case class CandidateService(
  serviceId:          String,
  kind:               ServiceKind,
  confidence:         Double,
  provenance:         String,
  startupHint:        Option[String],
  portHint:           Option[Int],
  healthHint:         Option[String],
)

// Spec §3.2: ManifestRef
case class ManifestRef(
  manifestType:       String,
  relativePath:       String,
  parsedSuccessfully: Boolean,
  parseErrors:        List[String],
)

// Spec §3.2: ImpactMap
case class ImpactMap(
  changedFiles:       List[String],
  affectedFrontendRoutes: List[ScoredInference[String]],
  affectedComponents: List[ScoredInference[String]],
  affectedApiHandlers: List[ScoredInference[String]],
  affectedDbModels:   List[ScoredInference[String]],
  affectedMigrations: List[String],
  affectedServiceIds: List[ScoredInference[String]],
  affectedAuthModules: List[ScoredInference[String]],
  inferredAdjacentFlows: List[ScoredInference[String]],
  infraSensitiveChanges: List[String],
  inferenceRequestId: Option[String],
)
