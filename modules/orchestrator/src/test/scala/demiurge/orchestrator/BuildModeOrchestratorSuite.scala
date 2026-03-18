package demiurge.orchestrator

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress

import demiurge.model._
import demiurge.persistence._
import demiurge.repair._

class BuildModeOrchestratorSuite extends FunSuite with TestFixtures {

  // --- Controllable HTTP server: returns 200 after N requests, 500 before ---
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

  // Build a single-requirement graph with a given HTTP verifier spec
  private def makeHttpGraph(
    runId: String,
    reqId: String,
    url: String,
    displayName: String,
    timeout: Duration = Duration.ofSeconds(5),
  ): RequirementGraph = {
    val node = RequirementNode(
      requirementId = reqId,
      humanDescription = displayName,
      machineDescription = displayName,
      priority = RequirementPriority.Required,
      category = RequirementCategory.ApiContract,
      dependencies = Set.empty,
      verifiers = List(VerifierSpec(
        verifierId = s"v-$reqId",
        verifierType = VerifierType.HttpApiContract,
        displayName = displayName,
        requirementId = reqId,
        executionLayer = 0,
        parallelSafe = true,
        timeout = timeout,
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

  // Compiler that produces an HTTP verifier against a given port
  private def httpCompiler(port: Int): demiurge.compiler.RequirementCompiler =
    new demiurge.compiler.RequirementCompiler {
      override def compile(runId: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph =
        makeHttpGraph(runId, "req-http", s"http://localhost:$port/check", "HTTP check")
    }

  // Compiler that always produces a failing HTTP verifier (connection refused)
  private def alwaysFailingCompiler: demiurge.compiler.RequirementCompiler =
    new demiurge.compiler.RequirementCompiler {
      override def compile(runId: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph =
        makeHttpGraph(runId, "req-fail", "http://localhost:19999/nonexistent", "Failing HTTP check", Duration.ofSeconds(2))
    }

  // Repair backend that always produces a successful patch
  private class SuccessfulCodeGenBackend extends RepairBackend {
    var callCount = 0
    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      val proposal = PatchProposal(
        patchId = s"patch-${callCount}",
        runId = context.runId,
        attemptNumber = context.attemptNumber,
        backendId = "build-gen",
        edits = Nil,
        newFiles = List(NewFile(s"generated-${callCount}.txt", "generated content")),
        deletions = Nil,
        summary = s"Code generation #${callCount}",
        hypotheses = List("initial implementation"),
        createdAt = Instant.now(),
      )
      RepairResponse.Success(proposal)
    }
  }

  // Repair backend that always fails
  private class FailingCodeGenBackend extends RepairBackend {
    var callCount = 0
    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      RepairResponse.Failed("Code generation failed: could not produce valid code")
    }
  }

  private def makeBuildRun(
    runId: String,
    repoRoot: Path,
    worktreePath: Path,
    lockPath: Path,
    maxAttempts: Int = 5,
  ): TaskRun = makeRun(runId, repoRoot, worktreePath, lockPath).copy(
    runMode = RunMode.Build,
    maxAttempts = maxAttempts,
  )

  test("build mode enters PlanningFeature then GeneratingCode") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "build-test-001"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      val server = new ControllableServer(passAfterNRequests = 1)
      try {
        SignalHandler.reset()
        val run = makeBuildRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new SuccessfulCodeGenBackend()
        RunOrchestrator.execute(
          ctx, StubRepoInspector, httpCompiler(server.port), StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        // Verify PlanningFeature and GeneratingCode states were entered
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))

        assert(toStatuses.contains("PlanningFeature"), s"Missing PlanningFeature: $toStatuses")
        assert(toStatuses.contains("GeneratingCode"), s"Missing GeneratingCode: $toStatuses")

        // PlanningFeature should come before PlanningEnvironment
        val pfIdx = toStatuses.indexOf("PlanningFeature")
        val peIdx = toStatuses.indexOf("PlanningEnvironment")
        assert(pfIdx < peIdx, s"PlanningFeature ($pfIdx) should come before PlanningEnvironment ($peIdx)")
      } finally {
        server.stop()
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("build mode code gen success continues to verification") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "build-test-002"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      val server = new ControllableServer(passAfterNRequests = 1)
      try {
        SignalHandler.reset()
        val run = makeBuildRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new SuccessfulCodeGenBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, httpCompiler(server.port), StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)
        assertEquals(finalRun.finalVerdict, Some(VerdictStatus.Pass))

        // Code gen backend called once (for initial generation, not repair)
        assertEquals(backend.callCount, 1)
      } finally {
        server.stop()
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("build mode code gen failure goes to Exhausted") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "build-test-003"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeBuildRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new FailingCodeGenBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assert(finalRun.finalSummary.exists(_.contains("Code generation failed")),
          s"Summary should mention code gen failure: ${finalRun.finalSummary}")

        // Should not have entered PlanningEnvironment
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))
        assert(!toStatuses.contains("PlanningEnvironment"),
          s"Should not have entered PlanningEnvironment: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("build mode patchHistory carries into repair loop") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "build-test-004"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        // maxAttempts=2: code gen produces patch, verify fails, repair produces patch,
        // verify fails again → Exhausted with 2 patches total
        val run = makeBuildRun(runId, repoRoot, worktreePath, lockPath, maxAttempts = 2)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new SuccessfulCodeGenBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, alwaysFailingCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Exhausted)
        // backend called: 1 for code gen + 1 for repair after attempt 1 = 2
        assertEquals(backend.callCount, 2)

        // 2 patch records in DB (code gen + repair)
        val patches = PatchRepo.listByRunId(runId)
        assertEquals(patches.size, 2, "Should have 2 patch records (code gen + repair)")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("non-build mode skips PlanningFeature/GeneratingCode") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "build-test-005"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        // Regular Full mode run
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val backend = new SuccessfulCodeGenBackend()
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          repairBackend = Some(backend),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Should NOT have PlanningFeature or GeneratingCode
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))
        assert(!toStatuses.contains("PlanningFeature"),
          s"Full mode should not have PlanningFeature: $toStatuses")
        assert(!toStatuses.contains("GeneratingCode"),
          s"Full mode should not have GeneratingCode: $toStatuses")

        // Backend should not have been called (verifiers pass with stub compiler)
        assertEquals(backend.callCount, 0)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }
}
