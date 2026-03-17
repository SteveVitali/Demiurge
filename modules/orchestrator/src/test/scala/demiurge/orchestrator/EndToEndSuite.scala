package demiurge.orchestrator

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.Instant

import demiurge.model._
import demiurge.persistence._
import demiurge.repair._

// Gap 8: End-to-end integration tests exercising the full orchestration pipeline.
// Uses in-memory SQLite and configurable stubs for deterministic, fast tests.
class EndToEndSuite extends FunSuite with TestFixtures {

  test("e2e: full run passes with stub backends") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      val tc = EndToEndTestHarness.setup(
        runId = "e2e-001",
        repoRoot = repoRoot,
        conn = conn,
        verifierBehavior = EndToEndTestHarness.VerifierBehavior.AlwaysPass,
      )

      try {
        val finalRun = RunOrchestrator.execute(
          tc.ctx, StubRepoInspector, tc.compiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = tc.repairBackend,
          configResolver = tc.configResolver,
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Pass))

        // Verify persisted state
        implicit val c: java.sql.Connection = conn
        val persisted = TaskRunRepo.getById("e2e-001")
        assert(persisted.isDefined)
        assertEquals(persisted.get.status, RunStatus.Succeeded)

        // Verify inspection, graph, and plan were persisted
        assert(RepoInspectionReportRepo.getByRunId("e2e-001").isDefined)
        assert(RequirementGraphRepo.getByRunId("e2e-001").isDefined)
        assert(RuntimePlanRepo.getByRunId("e2e-001").isDefined)
      } finally {
        tc.cleanup()
      }
    }
  }

  test("e2e: build mode enters PlanningFeature and GeneratingCode") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      val tc = EndToEndTestHarness.setup(
        runId = "e2e-002",
        repoRoot = repoRoot,
        conn = conn,
        verifierBehavior = EndToEndTestHarness.VerifierBehavior.AlwaysPass,
        repairBehavior = Some(EndToEndTestHarness.RepairBehavior.AlwaysSucceed),
        runMode = RunMode.Build,
      )

      try {
        val finalRun = RunOrchestrator.execute(
          tc.ctx, StubRepoInspector, tc.compiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = tc.repairBackend,
          configResolver = tc.configResolver,
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Verify build mode states were entered
        implicit val c: java.sql.Connection = conn
        val events = EventRepo.listByRunId("e2e-002", limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))

        assert(toStatuses.contains("PlanningFeature"),
          s"Build mode should enter PlanningFeature: $toStatuses")
        assert(toStatuses.contains("GeneratingCode"),
          s"Build mode should enter GeneratingCode: $toStatuses")

        // PlanningFeature before PlanningEnvironment
        val pfIdx = toStatuses.indexOf("PlanningFeature")
        val peIdx = toStatuses.indexOf("PlanningEnvironment")
        assert(pfIdx < peIdx, s"PlanningFeature ($pfIdx) should come before PlanningEnvironment ($peIdx)")
      } finally {
        tc.cleanup()
      }
    }
  }

  test("e2e: multi-attempt repair succeeds on third try") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      val tc = EndToEndTestHarness.setup(
        runId = "e2e-003",
        repoRoot = repoRoot,
        conn = conn,
        verifierBehavior = EndToEndTestHarness.VerifierBehavior.PassAfterAttempts(2),
        repairBehavior = Some(EndToEndTestHarness.RepairBehavior.AlwaysSucceed),
        maxAttempts = 5,
      )

      try {
        val finalRun = RunOrchestrator.execute(
          tc.ctx, StubRepoInspector, tc.compiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = tc.repairBackend,
          configResolver = tc.configResolver,
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Pass))

        // Verify multiple attempts were created
        implicit val c: java.sql.Connection = conn
        val attempts = AttemptRepo.listByRunId("e2e-003")
        assert(attempts.size >= 2, s"Should have at least 2 attempts, got ${attempts.size}")

        // Verify patches were created for repairs
        val patches = PatchRepo.listByRunId("e2e-003")
        assert(patches.nonEmpty, "Should have repair patches")
      } finally {
        tc.cleanup()
      }
    }
  }

  test("e2e: exhaustion after maxAttempts") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      val tc = EndToEndTestHarness.setup(
        runId = "e2e-004",
        repoRoot = repoRoot,
        conn = conn,
        verifierBehavior = EndToEndTestHarness.VerifierBehavior.AlwaysFail,
        repairBehavior = Some(EndToEndTestHarness.RepairBehavior.AlwaysSucceed),
        maxAttempts = 2,
      )

      try {
        val finalRun = RunOrchestrator.execute(
          tc.ctx, StubRepoInspector, tc.compiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = tc.repairBackend,
          configResolver = tc.configResolver,
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Fail))

        // Verify we actually ran maxAttempts
        implicit val c: java.sql.Connection = conn
        val attempts = AttemptRepo.listByRunId("e2e-004")
        assertEquals(attempts.size, 2, s"Should have exactly 2 attempts, got ${attempts.size}")
      } finally {
        tc.cleanup()
      }
    }
  }

  test("e2e: auth bootstrap state entered when auth configured") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      val tc = EndToEndTestHarness.setup(
        runId = "e2e-005",
        repoRoot = repoRoot,
        conn = conn,
        verifierBehavior = EndToEndTestHarness.VerifierBehavior.AlwaysPass,
        withAuth = true,
      )

      try {
        val finalRun = RunOrchestrator.execute(
          tc.ctx, StubRepoInspector, tc.compiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = tc.repairBackend,
          configResolver = tc.configResolver,
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Verify BootstrappingAuth was entered
        implicit val c: java.sql.Connection = conn
        val events = EventRepo.listByRunId("e2e-005", limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))

        assert(toStatuses.contains("BootstrappingAuth"),
          s"Should have entered BootstrappingAuth: $toStatuses")
      } finally {
        tc.cleanup()
      }
    }
  }

  test("e2e: resume from ReadyToVerify completes correctly") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn

      // First, do a normal run to populate the DB
      val setupTc = EndToEndTestHarness.setup(
        runId = "e2e-006",
        repoRoot = repoRoot,
        conn = conn,
        verifierBehavior = EndToEndTestHarness.VerifierBehavior.AlwaysPass,
      )

      try {
        val firstRun = RunOrchestrator.execute(
          setupTc.ctx, StubRepoInspector, setupTc.compiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )
        assertEquals(firstRun.status, RunStatus.Succeeded)
      } finally {
        setupTc.cleanup()
      }

      // Now simulate a resume: create a new run with same ID concept but use resume
      val resumeRunId = "e2e-006-resume"
      val worktreePath2 = WorktreeManager.create(repoRoot, resumeRunId, gitRef = Some("HEAD"))
      val lockPath2 = LockManager.acquire(repoRoot, resumeRunId, worktreePath2)

      try {
        SignalHandler.reset()

        // TaskRun must be inserted first (FK constraint)
        val run = TaskRun(
          runId = resumeRunId,
          repoPath = repoRoot,
          worktreePath = worktreePath2,
          gitRef = Some("HEAD"),
          taskText = "E2E resume test",
          changedFiles = None,
          status = RunStatus.Created,
          runMode = RunMode.Full,
          createdAt = Instant.now(),
          startedAt = None,
          endedAt = None,
          maxAttempts = 5,
          attemptCount = 0,
          envBootAttempts = 0,
          currentAttemptId = None,
          finalVerdict = None,
          finalSummary = None,
          policySnapshotId = "ps-e2e-resume",
          lockFilePath = lockPath2,
          artifactRootPath = repoRoot.resolve(".runs").resolve(resumeRunId),
        )
        TaskRunRepo.insert(run)

        // Pre-populate artifacts for the resume run
        val report = StubRepoInspector.inspect(resumeRunId, repoRoot, None)
        RepoInspectionReportRepo.insert(report)
        val graph = StubRequirementCompiler.compile(resumeRunId, report, "test")
        RequirementGraphRepo.insert(graph)
        val plan = StubEnvironmentPlanner.plan(resumeRunId, report, graph)
        RuntimePlanRepo.insert(plan)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath2, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          resumeFromStatus = Some(RunStatus.ReadyToVerify),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Verify early phases were skipped
        val events = EventRepo.listByRunId(resumeRunId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))

        assert(!toStatuses.contains("InspectingRepo"),
          s"Resume should skip InspectingRepo: $toStatuses")
        assert(!toStatuses.contains("PlanningEnvironment"),
          s"Resume should skip PlanningEnvironment: $toStatuses")
        assert(toStatuses.contains("ReadyToVerify"),
          s"Should enter ReadyToVerify: $toStatuses")
        assert(toStatuses.contains("Succeeded"),
          s"Should reach Succeeded: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, resumeRunId)
      }
    }
  }

  test("e2e: signal interrupt produces Interrupted status") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      val tc = EndToEndTestHarness.setup(
        runId = "e2e-007",
        repoRoot = repoRoot,
        conn = conn,
        verifierBehavior = EndToEndTestHarness.VerifierBehavior.AlwaysPass,
      )

      try {
        // Pre-set the interrupt flag before running
        SignalHandler.reset()
        SignalHandler.simulateInterrupt()

        val finalRun = RunOrchestrator.execute(
          tc.ctx, StubRepoInspector, tc.compiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = tc.repairBackend,
          configResolver = tc.configResolver,
        )

        assertEquals(finalRun.status, RunStatus.Interrupted)
        assert(finalRun.finalSummary.exists(_.contains("interrupted")),
          s"Summary should mention interruption: ${finalRun.finalSummary}")
      } finally {
        SignalHandler.reset()
        tc.cleanup()
      }
    }
  }
}
