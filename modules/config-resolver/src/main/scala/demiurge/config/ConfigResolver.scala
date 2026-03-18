package demiurge.config

import java.nio.file.Path

import demiurge.model._
import demiurge.inference.InferenceService

// Phase A: ConfigResolver trait — layered configuration resolution.
// Resolves config through: explicit YAML → cached inference → live inference.
trait ConfigResolver {

  /**
   * Resolve the full configuration for a run.
   * Layers: explicit YAML files → cached inferences → live LLM inference.
   *
   * @param repoPath         path to the repository root
   * @param taskText          the user's task description
   * @param changedFiles      optional list of changed files
   * @param inspection        repo inspection report (already computed)
   * @param inferenceService  optional inference service for LLM-based resolution
   * @return fully resolved configuration
   */
  def resolve(
    repoPath: Path,
    taskText: String,
    changedFiles: Option[List[String]],
    inspection: RepoInspectionReport,
    inferenceService: Option[InferenceService],
  ): ResolvedConfig
}
