package demiurge.compiler

import demiurge.model.{RepoInspectionReport, ResolvedConfig, RequirementGraph}
import demiurge.inference.InferenceService

// Spec §6: Requirement Compiler trait.
// Phase A: Extended to support optional InferenceService for LLM-backed requirement generation.
trait RequirementCompiler {
  def compile(runId: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph

  /**
   * Compile requirements with optional LLM inference and resolved config context.
   * When inferenceService is provided and no explicit requirements exist,
   * uses LLM to generate requirements from the task string.
   * Default implementation delegates to the original compile method.
   */
  def compileWithInference(
    runId: String,
    inspection: RepoInspectionReport,
    taskText: String,
    resolvedConfig: Option[ResolvedConfig] = None,
    inferenceService: Option[InferenceService] = None,
  ): RequirementGraph = compile(runId, inspection, taskText)
}
