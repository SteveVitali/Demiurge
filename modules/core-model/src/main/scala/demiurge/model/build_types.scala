package demiurge.model

import java.time.Instant

// Phase B: Build mode types — feature planning and code generation.

// FeaturePlan produced during PlanningFeature state.
// Describes what the LLM plans to implement before generating code.
case class FeaturePlan(
  planId:              String,
  runId:               String,
  taskText:            String,
  summary:             String,
  filesToCreate:       List[PlannedFile],
  filesToModify:       List[PlannedModification],
  filesToDelete:       List[String],
  requiresNewDeps:     List[String],
  requiresMigration:   Boolean,
  estimatedComplexity: String,
  createdAt:           Instant,
)

case class PlannedFile(
  relativePath:  String,
  description:   String,
  category:      String,
)

case class PlannedModification(
  relativePath:  String,
  description:   String,
  changeType:    String,
)

// Distinguishes initial code generation (Build mode) from repair.
// Used by the codegen backend to select the appropriate prompt template.
sealed trait GenerationMode
object GenerationMode {
  case object InitialBuild extends GenerationMode
  case object Repair extends GenerationMode

  val values: List[GenerationMode] = List(InitialBuild, Repair)
}
