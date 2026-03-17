package demiurge.orchestrator

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.Instant

import demiurge.model._
import demiurge.persistence._
import demiurge.repair._

// Gap 6: Tests for resume functionality in RunOrchestrator.
// Validates that resuming from various states correctly skips completed phases,
// loads persisted data, and uses the correct attempt number.
class ResumeSuite extends FunSuite with TestFixtures {

  // --- Helpers to pre-populate DB with run artifacts ---

  private def stubInspection(runId: String): RepoInspectionReport =
    StubRepoInspector.inspect(runId, Path.of("/tmp/fake"), None)

  private def insertInspectionReport(runId: String)(implicit conn: java.sql.Connection): RepoInspectionReport = {
    val report = stubInspection(runId)
    RepoInspectionReportRepo.insert(report)
    report
  }

  private def insertRequirementGraph(runId: String, inspection: RepoInspectionReport)(implicit conn: java.sql.Connection): RequirementGraph = {
    val graph = StubRequirementCompiler.compile(runId, inspection, "test")
    RequirementGraphRepo.insert(graph)
    graph
  }

  private def insertRuntimePlan(runId: String, inspection: RepoInspectionReport, graph: RequirementGraph)(implicit conn: java.sql.Connection): RuntimePlan = {
    val plan = StubEnvironmentPlanner.plan(runId, inspection, graph)
    RuntimePlanRepo.insert(plan)
    plan
  }

  private def insertAttempt(runId: String, attemptNumber: Int)(implicit conn: java.sql.Connection): Unit = {
    val attempt = Attempt(
      attemptId = s"attempt-$runId-$attemptNumber",
      runId = runId,
      attemptNumber = attemptNumber,
      status = AttemptStatus.VerificationPassed,
      startedAt = Instant.now(),
      endedAt = Some(Instant.now()),
      repairBackend = None,
      patchRecordId = None,
      failurePacketId = None,
      rerunPlanId = None,
      repairRetriesUsed = 0,
      verdictSummary = None,
    )
    AttemptRepo.insert(attempt)
  }

  private def insertPatchRecord(runId: String, attemptNumber: Int)(implicit conn: java.sql.Connection): Unit = {
    val record = PatchRepo.PatchRecord(
      patchRecordId = s"patch-$runId-$attemptNumber",
      runId = runId,
      attemptNumber = attemptNumber,
      filesChangedJson = "[]",
      totalLinesAdded = 10,
      totalLinesRemoved = 2,
      repairBackend = "test-backend",
      repairSummary = s"Repair attempt $attemptNumber",
      hypothesesJson = "[]",
      requiresEnvRebuild = false,
      appliedAt = Instant.now(),
    )
    PatchRepo.insert(record)
  }

  // ---- Tests ----

  test("resume from ReadyToVerify skips early phases") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "resume-test-001"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()

        // TaskRun must be inserted first (FK constraint)
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        // Pre-populate DB with artifacts from a prior run
        val inspection = insertInspectionReport(runId)
        val graph = insertRequirementGraph(runId, inspection)
        insertRuntimePlan(runId, inspection, graph)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          resumeFromStatus = Some(RunStatus.ReadyToVerify),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Verify early phases were NOT entered
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))

        assert(!toStatuses.contains("InspectingRepo"),
          s"Should have skipped InspectingRepo: $toStatuses")
        assert(!toStatuses.contains("CompilingRequirements"),
          s"Should have skipped CompilingRequirements: $toStatuses")
        assert(!toStatuses.contains("PlanningEnvironment"),
          s"Should have skipped PlanningEnvironment: $toStatuses")
        assert(!toStatuses.contains("BootstrappingEnvironment"),
          s"Should have skipped BootstrappingEnvironment: $toStatuses")
        assert(!toStatuses.contains("SeedingFixtures"),
          s"Should have skipped SeedingFixtures: $toStatuses")

        // Should have entered ReadyToVerify → Verifying → Succeeded
        assert(toStatuses.contains("ReadyToVerify"),
          s"Should have entered ReadyToVerify: $toStatuses")
        assert(toStatuses.contains("Verifying"),
          s"Should have entered Verifying: $toStatuses")
        assert(toStatuses.contains("Succeeded"),
          s"Should have reached Succeeded: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("resume from PlanningEnvironment re-runs from PlanningEnvironment onward") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "resume-test-002"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()

        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        // Pre-populate DB with inspection and requirements from prior run
        val inspection = insertInspectionReport(runId)
        insertRequirementGraph(runId, inspection)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          resumeFromStatus = Some(RunStatus.PlanningEnvironment),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Verify early phases were skipped
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))

        assert(!toStatuses.contains("InspectingRepo"),
          s"Should have skipped InspectingRepo: $toStatuses")
        assert(!toStatuses.contains("CompilingRequirements"),
          s"Should have skipped CompilingRequirements: $toStatuses")

        // Should have re-run from PlanningEnvironment
        assert(toStatuses.contains("PlanningEnvironment"),
          s"Should have entered PlanningEnvironment: $toStatuses")
        assert(toStatuses.contains("BootstrappingEnvironment"),
          s"Should have entered BootstrappingEnvironment: $toStatuses")
        assert(toStatuses.contains("Succeeded"),
          s"Should have reached Succeeded: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("resume data loader returns empty state for run with no artifacts") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "resume-test-empty"

      // TaskRun must exist (FK constraint) but no artifacts inserted
      val dummyPath = java.nio.file.Path.of("/tmp/dummy")
      val dummyRun = makeRun(runId, dummyPath, dummyPath, dummyPath)
      TaskRunRepo.insert(dummyRun)

      val data = ResumeDataLoader.load(runId)

      assert(data.inspection.isEmpty, "Should have no inspection report")
      assert(data.graph.isEmpty, "Should have no requirement graph")
      assert(data.plan.isEmpty, "Should have no runtime plan")
      assertEquals(data.patchHistory, Nil, "Should have no patches")
      assertEquals(data.lastAttemptNumber, 0, "Should have lastAttemptNumber 0")
    }
  }

  test("resume loads persisted data correctly") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "resume-test-003"

      // TaskRun must exist first (FK constraint)
      val dummyPath = java.nio.file.Path.of("/tmp/dummy")
      val dummyRun = makeRun(runId, dummyPath, dummyPath, dummyPath)
      TaskRunRepo.insert(dummyRun)

      // Insert artifacts
      val inspection = insertInspectionReport(runId)
      val graph = insertRequirementGraph(runId, inspection)
      insertRuntimePlan(runId, inspection, graph)
      insertAttempt(runId, 1)
      insertAttempt(runId, 2)
      insertPatchRecord(runId, 1)

      val data = ResumeDataLoader.load(runId)

      assert(data.inspection.isDefined, "Should load inspection report")
      assertEquals(data.inspection.get.runId, runId)

      assert(data.graph.isDefined, "Should load requirement graph")
      assertEquals(data.graph.get.runId, runId)

      assert(data.plan.isDefined, "Should load runtime plan")
      assertEquals(data.plan.get.runId, runId)

      assertEquals(data.patchHistory.size, 1, "Should load 1 patch record")
      assertEquals(data.patchHistory.head.runId, runId)

      assertEquals(data.lastAttemptNumber, 2, "Should report max attempt number as 2")
    }
  }

  test("resume uses correct attemptNumber") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "resume-test-004"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()

        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        // Pre-populate: prior run completed 2 attempts
        val inspection = insertInspectionReport(runId)
        val graph = insertRequirementGraph(runId, inspection)
        insertRuntimePlan(runId, inspection, graph)
        insertAttempt(runId, 1)
        insertAttempt(runId, 2)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          resumeFromStatus = Some(RunStatus.ReadyToVerify),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // The new attempt should be #3 (lastAttemptNumber=2 → start at 3)
        val attempts = AttemptRepo.listByRunId(runId)
        val attemptNumbers = attempts.map(_.attemptNumber)
        assert(attemptNumbers.contains(3),
          s"Should have created attempt #3, but found: $attemptNumbers")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("resume from BootstrappingAuth re-runs auth then continues") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "resume-test-005"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()

        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        // Pre-populate all prior state
        val inspection = insertInspectionReport(runId)
        val graph = insertRequirementGraph(runId, inspection)
        insertRuntimePlan(runId, inspection, graph)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          resumeFromStatus = Some(RunStatus.BootstrappingAuth),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Verify early phases were skipped
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))

        assert(!toStatuses.contains("InspectingRepo"),
          s"Should have skipped InspectingRepo: $toStatuses")
        assert(!toStatuses.contains("PlanningEnvironment"),
          s"Should have skipped PlanningEnvironment: $toStatuses")
        assert(!toStatuses.contains("BootstrappingEnvironment"),
          s"Should have skipped BootstrappingEnvironment: $toStatuses")
        assert(!toStatuses.contains("SeedingFixtures"),
          s"Should have skipped SeedingFixtures: $toStatuses")

        // Should have ReadyToVerify and Verifying
        assert(toStatuses.contains("ReadyToVerify"),
          s"Should have entered ReadyToVerify: $toStatuses")
        assert(toStatuses.contains("Verifying"),
          s"Should have entered Verifying: $toStatuses")
        assert(toStatuses.contains("Succeeded"),
          s"Should have reached Succeeded: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("resume without resumeFromStatus runs from scratch (backward compatible)") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "resume-test-006"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        // No resumeFromStatus — should run the full pipeline as before
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // All phases should have been executed
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))

        assert(toStatuses.contains("InspectingRepo"),
          s"Should have entered InspectingRepo: $toStatuses")
        assert(toStatuses.contains("CompilingRequirements"),
          s"Should have entered CompilingRequirements: $toStatuses")
        assert(toStatuses.contains("PlanningEnvironment"),
          s"Should have entered PlanningEnvironment: $toStatuses")
        assert(toStatuses.contains("BootstrappingEnvironment"),
          s"Should have entered BootstrappingEnvironment: $toStatuses")
        assert(toStatuses.contains("ReadyToVerify"),
          s"Should have entered ReadyToVerify: $toStatuses")
        assert(toStatuses.contains("Succeeded"),
          s"Should have reached Succeeded: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }
}
