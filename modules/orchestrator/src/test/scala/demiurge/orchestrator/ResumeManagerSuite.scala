package demiurge.orchestrator

import demiurge.model._

// Phase 8: Tests for resume behavior (Spec §2.1 Resume Rules, §7.6)
class ResumeManagerSuite extends munit.FunSuite {

  test("resumable states map to correct resume state") {
    // Spec §2.1: Resumable states re-enter from same or equivalent state
    assertEquals(ResumeManager.resumeStateFor(RunStatus.Created, 0, 5), RunStatus.Created)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.InspectingRepo, 0, 5), RunStatus.InspectingRepo)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.CompilingRequirements, 0, 5), RunStatus.CompilingRequirements)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.PlanningEnvironment, 0, 5), RunStatus.PlanningEnvironment)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.BootstrappingEnvironment, 0, 5), RunStatus.BootstrappingEnvironment)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.EnvironmentFailed, 0, 5), RunStatus.EnvironmentFailed)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.SeedingFixtures, 0, 5), RunStatus.SeedingFixtures)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.BootstrappingAuth, 0, 5), RunStatus.BootstrappingAuth)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.ReadyToVerify, 0, 5), RunStatus.ReadyToVerify)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.RebuildingEnvironment, 0, 5), RunStatus.RebuildingEnvironment)
  }

  test("non-resumable states abort current attempt and resume at ReadyToVerify if budget allows") {
    // Spec §2.1: Verifying through SoftResettingEnvironment are not resumable
    assertEquals(ResumeManager.resumeStateFor(RunStatus.Verifying, 1, 5), RunStatus.ReadyToVerify)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.AnalyzingFailure, 2, 5), RunStatus.ReadyToVerify)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.PlanningRepair, 1, 5), RunStatus.ReadyToVerify)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.Repairing, 3, 5), RunStatus.ReadyToVerify)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.RepairFailed, 2, 5), RunStatus.ReadyToVerify)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.PlanningRerun, 1, 5), RunStatus.ReadyToVerify)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.SoftResettingEnvironment, 4, 5), RunStatus.ReadyToVerify)
  }

  test("non-resumable states transition to Exhausted when no attempts remain") {
    // Spec §2.1: If attempt_count >= max_attempts, transition to Exhausted
    assertEquals(ResumeManager.resumeStateFor(RunStatus.Verifying, 5, 5), RunStatus.Exhausted)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.Repairing, 5, 5), RunStatus.Exhausted)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.SoftResettingEnvironment, 5, 5), RunStatus.Exhausted)
  }

  test("build mode states resume at same state") {
    assertEquals(ResumeManager.resumeStateFor(RunStatus.PlanningFeature, 0, 8), RunStatus.PlanningFeature)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.GeneratingCode, 0, 8), RunStatus.GeneratingCode)
  }

  test("terminal states cannot be resumed") {
    assertEquals(ResumeManager.resumeStateFor(RunStatus.Succeeded, 3, 5), RunStatus.Exhausted)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.Exhausted, 5, 5), RunStatus.Exhausted)
    assertEquals(ResumeManager.resumeStateFor(RunStatus.Cancelled, 2, 5), RunStatus.Exhausted)
  }
}
