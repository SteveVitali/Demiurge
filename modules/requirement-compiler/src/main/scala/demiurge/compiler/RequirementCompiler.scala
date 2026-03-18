package demiurge.compiler

import demiurge.model.{RepoInspectionReport, ResolvedConfig, RequirementGraph}
import demiurge.inference.InferenceService

// Spec §6: Requirement Compiler trait.
// Compiles explicit YAML requirements into a RequirementGraph.
// Use `demiurge init --smart` to generate requirements.yaml before running.
trait RequirementCompiler {
  def compile(runId: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph

  /**
   * Compile requirements from YAML with optional config context.
   * Returns a warning graph if no explicit requirements exist.
   * Parameters resolvedConfig and inferenceService are retained for interface
   * compatibility but are no longer used at runtime.
   */
  def compileWithInference(
    runId: String,
    inspection: RepoInspectionReport,
    taskText: String,
    resolvedConfig: Option[ResolvedConfig] = None,
    inferenceService: Option[InferenceService] = None,
  ): RequirementGraph = compile(runId, inspection, taskText)
}
