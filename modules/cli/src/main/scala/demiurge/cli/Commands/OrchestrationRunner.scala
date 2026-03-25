package demiurge.cli.Commands

import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.Instant

import io.circe.Json
import io.circe.syntax._

import demiurge.cli.CommandParsers._
import demiurge.model._
import demiurge.persistence._
import demiurge.orchestrator._
import demiurge.api.{LocalApiServer, EventStream}
import demiurge.inspector.RepoInspectorImpl
import demiurge.planner.EnvironmentPlannerImpl
import demiurge.runtime.RuntimeSupervisorImpl
import demiurge.artifact.{ArtifactSinkImpl, EvidenceCollectorImpl}
import demiurge.config.ConfigResolverImpl
import demiurge.inference.{InferenceServiceImpl, InferenceBudgetState, InMemoryInferenceCache, AnthropicInferenceBackend}
import demiurge.repair.{InferenceBackedRepairBackend, RepairBackend}
import demiurge.repair.claude.ClaudePromptBuilder
import demiurge.agent.{AgentBackend, AgentConfig, ClaudeAgentBackend}
import demiurge.worker.WorkerProcessManager

// Shared orchestration runner used by both RunCommand and ResumeCommand.
// Encapsulates: API server lifecycle, SSE wiring, compiler construction,
// orchestrator invocation, worker lifecycle, artifact production, and cleanup.
object OrchestrationRunner {

  /**
   * Execute a full orchestration run for the given TaskRun.
   * Manages API server, SSE events, worker, and artifact production.
   * Caller is responsible for error handling around this method.
   */
  def run(
    taskRun: TaskRun,
    global: GlobalOpts,
    worktreePath: Path,
    conn: Connection,
    resumeFromStatus: Option[RunStatus] = None,
  ): TaskRun = {
    implicit val c: Connection = conn
    val runId = taskRun.runId
    val artifactRoot = taskRun.artifactRootPath
    Files.createDirectories(artifactRoot)

    // Start local API server (best-effort, non-fatal).
    // Skip if the server is already running (e.g. invoked from ServeCommand).
    val serverStartedByUs = !LocalApiServer.isRunning
    if (serverStartedByUs) {
      val dbPath = global.repo.resolve(".demiurge").resolve("demiurge.db")
      try {
        LocalApiServer.start(
          port = 19440,
          dbPath = dbPath,
          artifactRootResolver = rid => Some(global.repo.resolve(".demiurge").resolve("artifacts").resolve(rid)),
        )
      } catch { case _: Exception => }
    }

    // Wire SSE event streaming
    RunTransitionManager.setEventListener(event => EventStream.publish(event))

    val compiler = RunCommand.buildCompiler(worktreePath)
    val (browserExecutor, workerManager) = RunCommand.buildBrowserExecutor(worktreePath, artifactRoot, runId)
    val artifactSink = new ArtifactSinkImpl(artifactRoot)
    val evidenceCollector = new EvidenceCollectorImpl(artifactSink)

    // Phase E: Build InferenceService if API key is available
    val inferenceServiceOpt = buildInferenceService()

    // Design §10: Build AgentBackend (Claude Code) as the primary repair path.
    // Falls back to legacy InferenceBackedRepairBackend only when no worker is available.
    val (agentBackendOpt, agentConfigOpt, agentWorkerManager) =
      buildAgentBackend(workerManager, global.repo, artifactRoot, worktreePath, runId)

    // Legacy repair backend: only used as fallback when agent backend is unavailable
    val repairBackend: Option[RepairBackend] = if (agentBackendOpt.isDefined) {
      None // Agent backend is primary — no legacy fallback needed
    } else {
      inferenceServiceOpt match {
        case Some(svc) =>
          System.err.println("[orchestrator] Agent backend unavailable, falling back to legacy LLM repair")
          Some(new InferenceBackedRepairBackend(svc, ClaudePromptBuilder))
        case None => RunCommand.buildRepairBackend()
      }
    }

    // Use the agent's worker manager if the browser executor didn't create one
    val effectiveWorkerManager = workerManager.orElse(agentWorkerManager)

    val ctx = RunContext(
      run = taskRun,
      repoRoot = global.repo,
      worktreePath = worktreePath,
      conn = conn,
    )

    try {
      val finalRun = RunOrchestrator.execute(
        ctx,
        RepoInspectorImpl,
        compiler,
        EnvironmentPlannerImpl,
        RuntimeSupervisorImpl,
        repairBackend = repairBackend,
        browserExecutor = browserExecutor,
        configResolver = Some(ConfigResolverImpl),
        inferenceService = inferenceServiceOpt,
        workerManager = effectiveWorkerManager,
        resumeFromStatus = resumeFromStatus,
        agentBackend = agentBackendOpt,
        agentConfig = agentConfigOpt,
      )

      writeFinalReport(evidenceCollector, runId, finalRun, conn)
      finalRun
    } finally {
      workerManager.foreach(w => try { w.shutdown() } catch { case _: Exception => })
      // Shut down agent-specific worker if it's a separate instance
      agentWorkerManager.foreach(w => try { w.shutdown() } catch { case _: Exception => })
      RunTransitionManager.clearEventListener()
      EventStream.markRunEnded(runId)
      // Only stop the server if we started it (don't kill ServeCommand's persistent server)
      if (serverStartedByUs) {
        LocalApiServer.stop()
      }
    }
  }

  // Spec §2.5: Build InferenceService with real AnthropicInferenceBackend.
  private def buildInferenceService(): Option[demiurge.inference.InferenceService] = {
    val apiKey = System.getenv("ANTHROPIC_API_KEY")
    if (apiKey != null && apiKey.nonEmpty) {
      try {
        val backend = new AnthropicInferenceBackend(apiKey)
        val budgetState = new InferenceBudgetState()
        val cache = new InMemoryInferenceCache()
        Some(new InferenceServiceImpl(backend, budgetState, cache))
      } catch {
        case _: Exception => None
      }
    } else None
  }

  // Design §10: Build AgentBackend (Claude Code) as the primary repair mechanism.
  // Auto-enabled when ANTHROPIC_API_KEY is set and a worker is available.
  // Set DEMIURGE_AGENT_BACKEND=none to explicitly disable.
  // Returns (agentBackend, agentConfig, optionalNewWorkerManager).
  private def buildAgentBackend(
    existingWorkerManager: Option[WorkerProcessManager],
    repoRoot: Path,
    artifactRoot: Path,
    worktreePath: Path,
    runId: String,
  ): (Option[AgentBackend], AgentConfig, Option[WorkerProcessManager]) = {
    val config = AgentConfig.fromEnvironment()

    // Allow explicit opt-out via DEMIURGE_AGENT_BACKEND=none
    val backendEnv = Option(System.getenv("DEMIURGE_AGENT_BACKEND")).getOrElse("")
    if (backendEnv.equalsIgnoreCase("none") || backendEnv.equalsIgnoreCase("disabled")) {
      return (None, config, None)
    }

    // Require ANTHROPIC_API_KEY (Claude Code CLI needs it)
    val apiKey = System.getenv("ANTHROPIC_API_KEY")
    if (apiKey == null || apiKey.isEmpty) {
      return (None, config, None)
    }

    // Try existing worker manager first, then fall back to DEMIURGE_WORKER_PATH
    val (wm, newWm) = existingWorkerManager match {
      case Some(w) => (w, None)
      case None =>
        val workerPath = Option(System.getenv("DEMIURGE_WORKER_PATH")).map(Path.of(_))
        workerPath match {
          case Some(wp) if Files.exists(wp) =>
            System.err.println(s"[orchestrator] Creating agent worker from DEMIURGE_WORKER_PATH=$wp")
            val w = new WorkerProcessManager(wp)
            w.spawn()
            w.initialize(artifactRoot.toString, worktreePath.toString, runId) match {
              case Right(_) =>
                System.err.println("[orchestrator] Agent worker initialized successfully")
              case Left(err) =>
                System.err.println(s"[orchestrator] Warning: Agent worker init failed: $err")
                return (None, config, None)
            }
            (w, Some(w))
          case Some(wp) =>
            System.err.println(s"[orchestrator] Warning: DEMIURGE_WORKER_PATH=$wp does not exist")
            return (None, config, None)
          case None =>
            // No worker available — agent backend can't be used
            return (None, config, None)
        }
    }

    System.err.println("[orchestrator] Agent backend: claude-agent-sdk (default)")
    (Some(new ClaudeAgentBackend(wm, repoRoot)), config, newWm)
  }

  private def writeFinalReport(
    collector: EvidenceCollectorImpl,
    runId: String,
    finalRun: TaskRun,
    conn: Connection,
  ): Unit = {
    implicit val c: Connection = conn
    try {
      val reportJson = Json.obj(
        "runId" -> Json.fromString(runId),
        "status" -> Json.fromString(finalRun.status.toString),
        "finalVerdict" -> finalRun.finalVerdict.map(v => Json.fromString(v.toString)).getOrElse(Json.Null),
        "finalSummary" -> finalRun.finalSummary.map(Json.fromString).getOrElse(Json.Null),
        "createdAt" -> Json.fromString(finalRun.createdAt.toString),
        "startedAt" -> finalRun.startedAt.map(t => Json.fromString(t.toString)).getOrElse(Json.Null),
        "endedAt" -> finalRun.endedAt.map(t => Json.fromString(t.toString)).getOrElse(Json.Null),
        "attemptCount" -> Json.fromInt(finalRun.attemptCount),
        "maxAttempts" -> Json.fromInt(finalRun.maxAttempts),
      ).noSpaces

      val record = collector.writeFinalReportArtifact(runId, reportJson)
      ArtifactRecordRepo.insert(record)
    } catch {
      case e: Exception =>
        System.err.println(s"Warning: Failed to write final report artifact: ${e.getMessage}")
    }
  }
}
