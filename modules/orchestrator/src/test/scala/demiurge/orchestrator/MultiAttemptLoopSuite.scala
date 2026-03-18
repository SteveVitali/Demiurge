package demiurge.orchestrator

import munit.FunSuite
import java.nio.file.Path
import java.time.{Duration, Instant}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress

import demiurge.model._
import demiurge.persistence._
import demiurge.repair._
import demiurge.runtime.RuntimeSupervisor

class MultiAttemptLoopSuite extends FunSuite with TestFixtures {

  // --- Controllable HTTP server: returns 200 after passAfterNRequests requests, 500 before ---
  private class ControllableServer(passAfterNRequests: Int) {
    private val requestCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val server: HttpServer = HttpServer.create(new InetSocketAddress(0), 0)
    val port: Int = server.getAddress.getPort

    server.createContext("/check", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        val count = requestCount.incrementAndGet()
        val status = if (count >= passAfterNRequests) 200 else 500
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
      }
    })
    server.setExecutor(null)
    server.start()

    def stop(): Unit = server.stop(0)
  }

  // Compiler that produces an HTTP verifier against a given URL
  private def httpCompilerForUrl(url: String): demiurge.compiler.RequirementCompiler = {
    new demiurge.compiler.RequirementCompiler {
      override def compile(runId: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph = {
        val node = RequirementNode(
          requirementId = "req-http",
          humanDescription = "HTTP check",
          machineDescription = "HTTP check",
          priority = RequirementPriority.Required,
          category = RequirementCategory.ApiContract,
          dependencies = Set.empty,
          verifiers = List(VerifierSpec(
            verifierId = "v-http",
            verifierType = VerifierType.HttpApiContract,
            displayName = "HTTP check",
            requirementId = "req-http",
            executionLayer = 0,
            parallelSafe = true,
            timeout = Duration.ofSeconds(5),
            maxRetries = 0,
            retryDelayMs = 100,
            browserFlowSpec = None,
            apiContractSpec = Some(ApiContractVerifierSpec(
              method = "GET",
              path = url,
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

  private def httpCompiler(port: Int): demiurge.compiler.RequirementCompiler =
    httpCompilerForUrl(s"http://localhost:$port/check")

  // Always fails — no server listening on port 19999
  private def alwaysFailingCompiler: demiurge.compiler.RequirementCompiler =
    httpCompilerForUrl("http://localhost:19999/nonexistent")

  // Repair backend that always rejects
  private class RejectingRepairBackend extends RepairBackend {
    var callCount = 0
    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      RepairResponse.Failed("Repair backend rejected the request")
    }
  }

  // Repair backend that succeeds (produces patches) but creates dummy files
  private class DummyFixRepairBackend extends RepairBackend {
    var callCount = 0
    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      val proposal = PatchProposal(
        patchId = s"patch-${callCount}",
        runId = context.runId,
        attemptNumber = context.attemptNumber,
        backendId = "dummy-fixer",
        edits = Nil,
        newFiles = List(NewFile(s"fix-${callCount}.txt", "fix content")),
        deletions = Nil,
        summary = s"Dummy fix #${callCount}",
        hypotheses = List("dummy hypothesis"),
        createdAt = Instant.now(),
      )
      RepairResponse.Success(proposal)
    }
  }

  // RuntimeSupervisor whose reboot fails on the Nth reboot
  private class FailingRebootSupervisor(failOnRebootNumber: Int = 1) extends RuntimeSupervisor {
    private val bootCount = new java.util.concurrent.atomic.AtomicInteger(0)
    var rebootCount = 0

    override def bootEnvironment(plan: RuntimePlan, repoRoot: Path): RuntimeSupervisor.BootResult = {
      val seq = bootCount.incrementAndGet()
      val snapshot = RuntimeSnapshot(
        snapshotId = s"stub-snapshot-${plan.runId}-$seq",
        runId = plan.runId,
        capturedAt = Instant.EPOCH,
        environmentStatus = EnvironmentStatus.Ready,
        services = Nil,
        activePortMappings = Map.empty,
        resolvedUrls = Map.empty,
        uptimeMs = 0L,
      )
      RuntimeSupervisor.BootSuccess(snapshot)
    }

    override def teardown(plan: RuntimePlan, repoRoot: Path): Unit = {}

    override def restartEnvironment(plan: RuntimePlan, repoRoot: Path): RuntimeSupervisor.BootResult = {
      rebootCount += 1
      if (rebootCount >= failOnRebootNumber) {
        RuntimeSupervisor.BootFailure("Simulated reboot failure", None)
      } else {
        teardown(plan, repoRoot)
        bootEnvironment(plan, repoRoot)
      }
    }
  }

  // --- Test cases ---

  test("single attempt success — verifier passes on attempt 1") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "multi-test-001"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      // Server returns 200 on first request
      val server = new ControllableServer(passAfterNRequests = 1)
      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 5)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new DummyFixRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, httpCompiler(server.port), StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Pass))
        // No repair needed
        assertEquals(backend.callCount, 0)
        // Only 1 attempt created
        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.size, 1)
        val persisted = TaskRunRepo.getById(runId).get
        assertEquals(persisted.attemptCount, 1)
        // No repair transitions
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))
        assert(!toStatuses.contains("AnalyzingFailure"),
          "Should not have repair transitions on first-attempt success")
      } finally {
        server.stop()
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("two-attempt success — fails attempt 1, repair succeeds, attempt 2 passes") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "multi-test-002"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      // Server returns 500 on first request, 200 on second
      val server = new ControllableServer(passAfterNRequests = 2)
      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 5)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new DummyFixRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, httpCompiler(server.port), StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Pass))
        assertEquals(backend.callCount, 1)

        // 2 attempts created
        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.size, 2)
        assertEquals(attempts.head.attemptNumber, 1)
        assertEquals(attempts(1).attemptNumber, 2)

        val persisted = TaskRunRepo.getById(runId).get
        assertEquals(persisted.attemptCount, 2)

        // Verify state transitions
        val events = EventRepo.listByRunId(runId, limit = 200)
        val transitionEvents = events.filter(_.eventType == "state_transition")
        val toStatuses = transitionEvents.flatMap(_.correlationFields.get("to_status"))

        assert(toStatuses.contains("AnalyzingFailure"), s"Missing AnalyzingFailure: $toStatuses")
        assert(toStatuses.contains("PlanningRepair"), s"Missing PlanningRepair: $toStatuses")
        assert(toStatuses.contains("Repairing"), s"Missing Repairing: $toStatuses")
        assert(toStatuses.contains("SoftResettingEnvironment"), s"Missing SoftResettingEnvironment: $toStatuses")
        assert(toStatuses.contains("Succeeded"), s"Missing Succeeded: $toStatuses")
        val readyCount = toStatuses.count(_ == "ReadyToVerify")
        assertEquals(readyCount, 2, s"ReadyToVerify should appear twice: $toStatuses")
        val verifyingCount = toStatuses.count(_ == "Verifying")
        assertEquals(verifyingCount, 2, s"Verifying should appear twice: $toStatuses")

        // 1 patch record
        val patches = PatchRepo.listByRunId(runId)
        assertEquals(patches.size, 1, "Should have 1 patch record")

        assert(finalRun.finalSummary.exists(_.contains("repair")),
          s"Summary should mention repair: ${finalRun.finalSummary}")
      } finally {
        server.stop()
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("three-attempt success — fails twice, passes on third") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "multi-test-003"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      // Server returns 500 on first 2 requests, 200 on third
      val server = new ControllableServer(passAfterNRequests = 3)
      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 5)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new DummyFixRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, httpCompiler(server.port), StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Pass))
        assertEquals(backend.callCount, 2)

        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.size, 3)

        val persisted = TaskRunRepo.getById(runId).get
        assertEquals(persisted.attemptCount, 3)

        // 2 patch records
        val patches = PatchRepo.listByRunId(runId)
        assertEquals(patches.size, 2, "Should have 2 patch records")

        assert(finalRun.finalSummary.exists(_.contains("2 repair")),
          s"Summary should mention 2 repairs: ${finalRun.finalSummary}")
      } finally {
        server.stop()
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("exhausted after maxAttempts — always fails, maxAttempts=3") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "multi-test-004"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 3)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new DummyFixRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, alwaysFailingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Fail))
        // Repair called after attempts 1 and 2 (not after attempt 3 = maxAttempts)
        assertEquals(backend.callCount, 2)

        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.size, 3)

        val persisted = TaskRunRepo.getById(runId).get
        assertEquals(persisted.attemptCount, 3)

        assert(finalRun.finalSummary.exists(_.contains("3 attempt")),
          s"Summary should mention 3 attempts: ${finalRun.finalSummary}")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("repair rejection terminates early") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "multi-test-005"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 5)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new RejectingRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, alwaysFailingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Fail))
        assertEquals(backend.callCount, 1)

        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.size, 1)

        assert(finalRun.finalSummary.exists(_.contains("Repair failed")),
          s"Summary should mention repair failure: ${finalRun.finalSummary}")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("environment reboot failure terminates") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "multi-test-006"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 5)
        TaskRunRepo.insert(run)

        val supervisor = new FailingRebootSupervisor(failOnRebootNumber = 1)
        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new DummyFixRepairBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, alwaysFailingCompiler, StubEnvironmentPlanner, supervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Fail))
        assert(finalRun.finalSummary.exists(_.contains("reboot failed")),
          s"Summary should mention reboot failure: ${finalRun.finalSummary}")
        assertEquals(backend.callCount, 1)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("no repair backend skips repair — Exhausted after first failed attempt") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "multi-test-007"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath).copy(maxAttempts = 5)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, alwaysFailingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = None,
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Fail))

        val attempts = AttemptRepo.listByRunId(runId)
        assertEquals(attempts.size, 1)

        val persisted = TaskRunRepo.getById(runId).get
        assertEquals(persisted.attemptCount, 1)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }
}
