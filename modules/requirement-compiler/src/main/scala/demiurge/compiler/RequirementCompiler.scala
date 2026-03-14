package demiurge.compiler

import demiurge.model.{RepoInspectionReport, RequirementGraph}

// Spec §6: Requirement Compiler trait — compile-only placeholder for Phase 2
trait RequirementCompiler {
  def compile(runId: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph
}
