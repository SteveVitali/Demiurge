package demiurge.orchestrator

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}

import demiurge.model._
import demiurge.requirements._
import demiurge.selectors._
import demiurge.compiler.RequirementCompilerImpl
import demiurge.persistence._
import demiurge.repair._

class RepairLoopSuite extends FunSuite with TestFixtures {

  // Compiler that produces a failing exec verifier (HTTP to non-existent endpoint)
  private def failingCompiler: demiurge.compiler.RequirementCompiler = {
    new demiurge.compiler.RequirementCompiler {
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
              path = "http://localhost:19999/nonexistent",
              expectedStatus = 200,
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

  // Compiler that produces passing state verifiers
  private def passingCompiler: demiurge.compiler.RequirementCompiler = {
    val reqs = RequirementsFile(List(
      RequirementEntry("req-1", "state", "State check 1", None, None, Some(5000L), None, Some("required")),
    ))
    val sels = SelectorsFile(Nil)
    new RequirementCompilerImpl(reqs, sels)
  }

  // Mock repair backend that returns a patch creating a new file
  private class FixingRepairBackend extends RepairBackend {
    var callCount = 0
    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      val proposal = PatchProposal(
        patchId = s"patch-${callCount}",
        runId = context.runId,
        attemptNumber = context.attemptNumber,
        backendId = "mock-fixer",
        edits = Nil,
        newFiles = List(NewFile(s"fix-${callCount}.txt", "fixed content")),
        deletions = Nil,
        summary = s"Applied fix #${callCount}",
        hypotheses = List("Root cause identified"),
        createdAt = Instant.now(),
      )
      RepairResponse.Success(proposal)
    }
  }

  // Mock repair backend that always fails
  private class AlwaysFailingRepairBackend extends RepairBackend {
    var callCount = 0
    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      RepairResponse.Failed("Mock backend always fails")
    }
  }

  test("repair loop: run succeeds after repair when verifiers pass on second attempt") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-001"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        // Use passing compiler + fixing repair backend
        // First verification passes → Succeeded directly (no repair needed)
        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FixingRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, passingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Pass))
        // No repair was needed since verifiers passed
        assertEquals(backend.callCount, 0)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("repair loop: run reaches Exhausted after failed repair") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-002"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 2)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new AlwaysFailingRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Fail))
        // Repair rejected on first attempt → immediate Exhausted
        assertEquals(backend.callCount, 1, "Backend should be called exactly once")
        assert(finalRun.finalSummary.exists(_.contains("Repair failed")))
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("repair loop: run reaches Exhausted when all attempts fail despite repairs") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-003"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        // maxAttempts=2 to keep test fast and predictable
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 2)
        TaskRunRepo.insert(run)

        // Failing compiler + fixing backend = repair applied but verifiers still fail
        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FixingRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Fail))
        // With maxAttempts=2: attempt 1 fails → repair → attempt 2 fails → exhausted
        assertEquals(backend.callCount, 1, "Backend should be called once (repair after attempt 1)")
        assert(finalRun.finalSummary.exists(_.contains("attempt")),
          s"Summary should mention attempts: ${finalRun.finalSummary}")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("repair is attempted up to maxAttempts-1 times") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-004"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        // maxAttempts=3: repairs happen after attempts 1 and 2; attempt 3 is final
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 3)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FixingRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        // Backend called maxAttempts-1 times (repair after each failing attempt except last)
        assertEquals(backend.callCount, 2)
        assertEquals(finalRun.status, RunStatus.Exhausted)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("no repair backend means Exhausted on first failure") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-005"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        // No repair backend — should go straight to Exhausted
        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = None,
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Fail))
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("repair transitions include AnalyzingFailure and PlanningRepair and Repairing states") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-006"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 2)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FixingRepairBackend()
        RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        val events = EventRepo.listByRunId(runId, limit = 100)
        val transitionEvents = events.filter(_.eventType == "state_transition")
        val toStatuses = transitionEvents.flatMap(_.correlationFields.get("to_status"))

        assert(toStatuses.contains("AnalyzingFailure"), s"Should have AnalyzingFailure: $toStatuses")
        assert(toStatuses.contains("PlanningRepair"), s"Should have PlanningRepair: $toStatuses")
        assert(toStatuses.contains("Repairing"), s"Should have Repairing: $toStatuses")
        assert(toStatuses.contains("SoftResettingEnvironment"), s"Should have SoftResettingEnvironment: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("environment restarts after patch") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-007"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        // maxAttempts=2 so we get exactly 1 repair + 1 reboot
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 2)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FixingRepairBackend()
        RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        // Check that ReadyToVerify appears twice (initial + after reboot)
        val events = EventRepo.listByRunId(runId, limit = 100)
        val transitionEvents = events.filter(_.eventType == "state_transition")
        val readyToVerifyCount = transitionEvents.count(
          _.correlationFields.get("to_status").contains("ReadyToVerify"))
        assertEquals(readyToVerifyCount, 2, "ReadyToVerify should appear twice (initial + after reboot)")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("verifiers rerun after repair") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-008"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        // maxAttempts=2 so we get exactly 2 attempts
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 2)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FixingRepairBackend()
        RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        // Check that 2 attempts were created
        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.size, 2, "Should have 2 attempts (original + rerun)")
        assertEquals(attempts.head.attemptNumber, 1)
        assertEquals(attempts(1).attemptNumber, 2)

        // Check verdicts for both attempts
        val verdicts1 = VerdictRepo.listByRunAndAttempt(runId, 1)
        val verdicts2 = VerdictRepo.listByRunAndAttempt(runId, 2)
        assert(verdicts1.nonEmpty, "Should have verdicts for attempt 1")
        assert(verdicts2.nonEmpty, "Should have verdicts for attempt 2")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("failure packet is persisted") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-009"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 2)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FixingRepairBackend()
        RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        // Check failure packet was persisted
        val packet = FailurePacketRepo.getByRunAndAttempt(runId, 1)
        assert(packet.isDefined, "Failure packet should be persisted")
        assertEquals(packet.get.runId, runId)
        assertEquals(packet.get.attemptNumber, 1)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("patch record is persisted after successful repair") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-010"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 2)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FixingRepairBackend()
        RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        // Check patch record was persisted
        val patches = PatchRepo.listByRunId(runId)
        assertEquals(patches.size, 1, "Should have exactly one patch record")
        assertEquals(patches.head.repairBackend, "mock-fixer")
        assert(patches.head.repairSummary.contains("Applied fix"))
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("worktree is modified after patch") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "repair-test-011"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 2)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FixingRepairBackend()
        RunOrchestrator.execute(
          ctx, StubRepoInspector, failingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        // Check that the new file was created in the worktree
        assert(Files.exists(worktreePath.resolve("fix-1.txt")),
          "Worktree should contain the fix file created by the repair backend")
        val content = new String(Files.readAllBytes(worktreePath.resolve("fix-1.txt")))
        assertEquals(content, "fixed content")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }
}
