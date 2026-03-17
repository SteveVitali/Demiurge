package demiurge.orchestrator

import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress

import demiurge.model._
import demiurge.persistence._
import demiurge.repair._
import demiurge.config.ConfigResolver
import demiurge.inference.InferenceService

// Gap 8: End-to-end test harness for integration tests.
// Wires all orchestrator components with configurable stub/mock backends
// using in-memory SQLite and deterministic behavior.
object EndToEndTestHarness {

  // --- Configurable verifier behavior ---
  sealed trait VerifierBehavior
  object VerifierBehavior {
    /** All verifiers pass immediately */
    case object AlwaysPass extends VerifierBehavior
    /** All verifiers fail on every attempt */
    case object AlwaysFail extends VerifierBehavior
    /** Verifiers fail for the first N attempts, then pass */
    case class PassAfterAttempts(failCount: Int) extends VerifierBehavior
  }

  // --- Configurable repair behavior ---
  sealed trait RepairBehavior
  object RepairBehavior {
    /** Repair always succeeds (produces a patch) */
    case object AlwaysSucceed extends RepairBehavior
    /** Repair always fails */
    case object AlwaysFail extends RepairBehavior
    /** Repair succeeds on the Nth call */
    case class SucceedOnAttempt(n: Int) extends RepairBehavior
  }

  // --- Test run context returned by setup() ---
  case class TestRunContext(
    repoRoot:     Path,
    worktreePath: Path,
    lockPath:     Path,
    conn:         java.sql.Connection,
    run:          TaskRun,
    ctx:          RunContext,
    httpServer:   Option[HttpServer],
    httpPort:     Option[Int],
    compiler:     demiurge.compiler.RequirementCompiler,
    repairBackend: Option[RepairBackend],
    configResolver: Option[ConfigResolver],
  ) {
    def cleanup(): Unit = {
      httpServer.foreach(_.stop(0))
      try { LockManager.release(repoRoot) } catch { case _: Exception => }
      try { WorktreeManager.remove(repoRoot, run.runId) } catch { case _: Exception => }
    }
  }

  // --- Controllable HTTP server for verifier behavior ---
  private class ControllableServer(behavior: VerifierBehavior) {
    private val requestCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val server: HttpServer = HttpServer.create(new InetSocketAddress(0), 0)
    val port: Int = server.getAddress.getPort

    server.createContext("/check", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        val count = requestCount.incrementAndGet()
        val status = behavior match {
          case VerifierBehavior.AlwaysPass => 200
          case VerifierBehavior.AlwaysFail => 500
          case VerifierBehavior.PassAfterAttempts(failCount) =>
            if (count > failCount) 200 else 500
        }
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
      }
    })
    server.setExecutor(null)
    server.start()

    def stop(): Unit = server.stop(0)
  }

  // --- Repair backend with configurable behavior ---
  class ConfigurableRepairBackend(behavior: RepairBehavior) extends RepairBackend {
    private var callCount = 0

    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      behavior match {
        case RepairBehavior.AlwaysSucceed =>
          makeSuccessResponse(context)
        case RepairBehavior.AlwaysFail =>
          RepairResponse.Failed("Repair failed (configured to always fail)")
        case RepairBehavior.SucceedOnAttempt(n) =>
          if (callCount >= n) makeSuccessResponse(context)
          else RepairResponse.Failed(s"Repair failed (attempt $callCount, succeeds on $n)")
      }
    }

    private def makeSuccessResponse(context: RepairContext): RepairResponse = {
      val proposal = PatchProposal(
        patchId = s"patch-${context.runId}-${callCount}",
        runId = context.runId,
        attemptNumber = context.attemptNumber,
        backendId = "test-repair",
        edits = Nil,
        newFiles = List(NewFile(s"fix-${callCount}.txt", "fix content")),
        deletions = Nil,
        summary = s"Test repair #${callCount}",
        hypotheses = List("test hypothesis"),
        createdAt = Instant.now(),
      )
      RepairResponse.Success(proposal)
    }
  }

  // --- Compiler that produces an HTTP verifier spec ---
  private def httpCompiler(port: Int): demiurge.compiler.RequirementCompiler =
    new demiurge.compiler.RequirementCompiler {
      override def compile(runId: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph = {
        val node = RequirementNode(
          requirementId = s"req-$runId",
          humanDescription = "HTTP health check",
          machineDescription = "HTTP health check",
          priority = RequirementPriority.Required,
          category = RequirementCategory.ApiContract,
          dependencies = Set.empty,
          verifiers = List(VerifierSpec(
            verifierId = s"v-$runId",
            verifierType = VerifierType.HttpApiContract,
            displayName = "HTTP check",
            requirementId = s"req-$runId",
            executionLayer = 0,
            parallelSafe = true,
            timeout = Duration.ofSeconds(5),
            maxRetries = 0,
            retryDelayMs = 100,
            browserFlowSpec = None,
            apiContractSpec = Some(ApiContractVerifierSpec(
              method = "GET",
              urlTemplate = s"http://localhost:$port/check",
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
          generatedAt = Instant.EPOCH,
          inferenceRequestId = None,
          warnings = Nil,
        )
      }
    }

  /**
   * Set up a complete end-to-end test environment.
   *
   * @param runId            Unique run ID
   * @param repoRoot         Temp git repo root (from TestFixtures.withTempGitRepoAndDb)
   * @param conn             DB connection
   * @param verifierBehavior How the HTTP verifier should behave
   * @param repairBehavior   How the repair backend should behave (None = no repair)
   * @param runMode          RunMode (Full or Build)
   * @param maxAttempts      Max attempts for the run
   * @param withAuth         Whether to configure auth (StaticTestToken)
   */
  def setup(
    runId: String,
    repoRoot: Path,
    conn: java.sql.Connection,
    verifierBehavior: VerifierBehavior = VerifierBehavior.AlwaysPass,
    repairBehavior: Option[RepairBehavior] = None,
    runMode: RunMode = RunMode.Full,
    maxAttempts: Int = 5,
    withAuth: Boolean = false,
  ): TestRunContext = {
    implicit val c: java.sql.Connection = conn

    val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
    val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

    // HTTP server for verifier
    val server = verifierBehavior match {
      case VerifierBehavior.AlwaysPass =>
        // Use stub compiler (no HTTP server needed) for pass-only
        None
      case _ =>
        Some(new ControllableServer(verifierBehavior))
    }

    val compiler: demiurge.compiler.RequirementCompiler = server match {
      case Some(s) => httpCompiler(s.port)
      case None    => StubRequirementCompiler
    }

    val repairBackend: Option[RepairBackend] = repairBehavior.map(b =>
      new ConfigurableRepairBackend(b))

    val configResolver: Option[ConfigResolver] = if (withAuth) {
      Some(new ConfigResolver {
        override def resolve(
          repoPath: Path,
          taskText: String,
          changedFiles: Option[List[String]],
          inspection: RepoInspectionReport,
          inferenceService: Option[InferenceService],
        ): ResolvedConfig = makeResolvedConfigWithAuth()
      })
    } else None

    SignalHandler.reset()

    val run = TaskRun(
      runId = runId,
      repoPath = repoRoot,
      worktreePath = worktreePath,
      gitRef = Some("HEAD"),
      taskText = "E2E test task",
      changedFiles = None,
      status = RunStatus.Created,
      runMode = runMode,
      createdAt = Instant.now(),
      startedAt = None,
      endedAt = None,
      maxAttempts = maxAttempts,
      attemptCount = 0,
      envBootAttempts = 0,
      currentAttemptId = None,
      finalVerdict = None,
      finalSummary = None,
      policySnapshotId = "ps-e2e",
      lockFilePath = lockPath,
      artifactRootPath = repoRoot.resolve(".runs").resolve(runId),
    )
    TaskRunRepo.insert(run)

    val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)

    TestRunContext(
      repoRoot = repoRoot,
      worktreePath = worktreePath,
      lockPath = lockPath,
      conn = conn,
      run = run,
      ctx = ctx,
      httpServer = server.map(_.server),
      httpPort = server.map(_.port),
      compiler = compiler,
      repairBackend = repairBackend,
      configResolver = configResolver,
    )
  }

  private def makeResolvedConfigWithAuth(): ResolvedConfig = {
    ResolvedConfig(
      app = ResolvedAppConfig(appType = "web", rootUrl = "http://localhost:3000", apiUrl = None),
      services = Nil,
      fixtures = None,
      auth = Some(ResolvedAuthConfig(
        mode = AuthMode.StaticTestToken,
        loginUrl = None,
        credentials = Map.empty,
        staticToken = Some("e2e-test-token"),
        storageStateOutput = None,
      )),
      verification = ResolvedVerificationConfig(
        defaultVerifierTimeoutMs = 5000,
        defaultBrowserActionTimeoutMs = 5000,
        maxRetries = 0,
        retryDelayMs = 100,
        screenshotOnFailure = false,
        screenshotOnComplete = false,
        traceEnabled = false,
      ),
      inference = ResolvedInferenceConfig(
        defaultProvider = InferenceProvider.Mock,
        models = Map.empty,
      ),
      policies = ResolvedPoliciesConfig(
        maxAttempts = 5,
        runTimeoutMs = 300000L,
        attemptTimeoutMs = 60000L,
        maxPatchLines = 1000,
        maxArtifactDiskBytes = 104857600L,
        allowedHosts = Nil,
        browserAllowedOrigins = Nil,
        allowGitPush = false,
        allowDbDrop = false,
      ),
      observability = None,
      provenance = ConfigProvenance(
        manifestSource = ConfigSource.Default,
        requirementSources = Map.empty,
        serviceSources = Map.empty,
        resolvedAt = Instant.now(),
      ),
    )
  }
}
