package lastmile.orchestrator

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}

import lastmile.model._
import lastmile.requirements._
import lastmile.selectors._
import lastmile.compiler.RequirementCompilerImpl
import lastmile.persistence._

class AttemptLoopSuite extends FunSuite with TestFixtures {

  // Helper: create a compiler that produces real requirements with state verifiers (always pass)
  private def passingCompiler: lastmile.compiler.RequirementCompiler = {
    val reqs = RequirementsFile(List(
      RequirementEntry("req-1", "state", "State check 1", None, None, Some(5000L), None, Some("required")),
      RequirementEntry("req-2", "state", "State check 2", None, None, Some(5000L), None, Some("required")),
    ))
    val sels = SelectorsFile(Nil)
    new RequirementCompilerImpl(reqs, sels)
  }

  // Helper: create a compiler that produces a failing exec verifier
  private def failingCompiler: lastmile.compiler.RequirementCompiler = {
    // Use a custom compiler that produces an ExecVerifier with 'false' command
    new lastmile.compiler.RequirementCompiler {
      override def compile(runId: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph = {
        val node = RequirementNode(
          requirementId = "req-fail",
          humanDescription = "Failing requirement",
          machineDescription = "Failing requirement",
          priority = RequirementPriority.Required,
          category = RequirementCategory.ApiContract,
          dependencies = Set.empty,
          verifiers = List(VerifierSpec(
            verifierId = "v-fail",
            verifierType = VerifierType.HttpApiContract,
            displayName = "Failing HTTP check",
            requirementId = "req-fail",
            executionLayer = 0,
            parallelSafe = true,
            timeout = Duration.ofSeconds(2),
            maxRetries = 0,
            retryDelayMs = 100,
            browserFlowSpec = None,
            apiContractSpec = Some(ApiContractVerifierSpec(
              method = "GET",
              urlTemplate = "http://localhost:19999/nonexistent",
              headers = Map.empty,
              bodyTemplate = None,
              expectedStatus = 200,
              responseAssertions = Nil,
              artifactPlan = Nil,
            )),
            stateAssertionSpec = None,
            envReadinessSpec = None,
            consoleLogSpec = None,
            networkSpec = None,
            queueJobSpec = None,
            persistenceSpec = None,
            regressionSpec = None,
          )),
          evidenceRequired = Nil,
          destructiveRiskLevel = 0,
          inferredFrom = Nil,
          confidence = 1.0,
          stopOnFailure = true,
        )
        RequirementGraph(
          graphId = s"graph-$runId",
          runId = runId,
          nodes = List(node),
          edges = Nil,
          generatedAt = Instant.now(),
          inferenceRequestId = None,
          warnings = Nil,
        )
      }
    }
  }

  test("run succeeds when all verifiers pass") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-001"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, passingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Pass))
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("run reaches Exhausted when verifiers fail (no repair)") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-002"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Fail))
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("attempt is created and persisted") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-003"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(
          ctx, StubRepoInspector, passingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        // Verify attempt was created
        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.size, 1)
        assertEquals(attempts.head.attemptNumber, 1)
        assertEquals(attempts.head.runId, runId)
        assert(attempts.head.endedAt.isDefined, "Attempt should have endedAt set")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("attempt status is VerificationPassed when all pass") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-004"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(
          ctx, StubRepoInspector, passingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.head.status, AttemptStatus.VerificationPassed)
        assert(attempts.head.verdictSummary.isDefined, "Should have verdict summary")
        assertEquals(attempts.head.verdictSummary.get.passCount, 2)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("attempt status is VerificationFailed when verifiers fail") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-005"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.head.status, AttemptStatus.VerificationFailed)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("only one attempt is created (single attempt, no repair)") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-006"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.size, 1, "Only one attempt should be created (no repair)")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("verdicts are persisted for the attempt") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-007"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(
          ctx, StubRepoInspector, passingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        // Verify verdicts were persisted
        val verdicts = VerdictRepo.listByRunAndAttempt(runId, 1)
        assertEquals(verdicts.size, 2, "Should have 2 verdicts for 2 requirements")
        assert(verdicts.forall(_.status == VerdictStatus.Pass))
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("run transitions include Verifying state") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-008"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(
          ctx, StubRepoInspector, passingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        val events = EventRepo.listByRunId(runId, limit = 100)
        val transitionEvents = events.filter(_.eventType == "state_transition")
        val toStatuses = transitionEvents.flatMap(_.correlationFields.get("to_status"))

        assert(toStatuses.contains("Verifying"), s"Should have transitioned to Verifying: $toStatuses")
        assert(toStatuses.contains("Succeeded"), s"Should have transitioned to Succeeded: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("run attemptCount is incremented") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-009"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(
          ctx, StubRepoInspector, passingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        val persisted = TaskRunRepo.getById(runId).get
        assertEquals(persisted.attemptCount, 1)
        assert(persisted.currentAttemptId.isDefined, "currentAttemptId should be set")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("final verdict is persisted on the run") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "attempt-test-010"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(
          ctx, StubRepoInspector, passingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        val persisted = TaskRunRepo.getById(runId).get
        assertEquals(persisted.finalVerdict, Some(VerdictStatus.Pass))
        assertEquals(persisted.status, RunStatus.Succeeded)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }
}
