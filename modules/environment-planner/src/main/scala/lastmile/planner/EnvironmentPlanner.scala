package lastmile.planner

import lastmile.model.{RepoInspectionReport, RequirementGraph, RuntimePlan}

// Spec §8: Environment Planner trait — compile-only placeholder for Phase 2
trait EnvironmentPlanner {
  def plan(runId: String, inspection: RepoInspectionReport, requirements: RequirementGraph): RuntimePlan
}
