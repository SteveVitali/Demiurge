package demiurge.cli.Commands

import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.Instant
import java.util.UUID

import io.circe.Json
import io.circe.syntax._

import demiurge.cli._
import demiurge.cli.CommandParsers._
import demiurge.model._
import demiurge.persistence._
import demiurge.orchestrator._
import demiurge.api.LocalApiServer
import demiurge.compiler.RequirementCompilerImpl
import demiurge.requirements.{RequirementsParser, RequirementsFile}
import demiurge.selectors.{SelectorsParser, SelectorsFile}
import demiurge.license.CredentialStore
import demiurge.repair.RepairBackend
import demiurge.verification.{VerificationEngine, BrowserFlowVerifier, BrowserVerifierResult, VerifierOutcome}
import demiurge.worker.{WorkerProcessManager, WorkerMessages}

// Phase 10: `demiurge run` command — fully wired to real implementations
// Includes: browser worker spawning, artifact production, repair backend
object RunCommand {

  def execute(cmd: RunCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    // Check for concurrent active run
    val activeRun = TaskRunRepo.getActiveRunByRepoPath(global.repo.toString)
    if (activeRun.isDefined) {
      val msg = s"Concurrent run conflict: run ${activeRun.get.runId} is already active for this repo"
      System.err.println(OutputFormatter.formatError(msg, global.format))
      return ExitCodes.ConcurrentRunConflict
    }

    val runId = cmd.runId.getOrElse(UUID.randomUUID().toString)
    val runMode = cmd.mode.flatMap(m => RunMode.values.find(_.toString.equalsIgnoreCase(m))).getOrElse(RunMode.Full)
    val budget = if (runMode == RunMode.Build) BuildBudgetDefaults.defaults else ExecutionBudgetDefaults.defaults

    val artifactRoot = global.repo.resolve(".demiurge").resolve("artifacts").resolve(runId)
    Files.createDirectories(artifactRoot)

    // Create isolated git worktree (Spec §4.2, Design §12.3)
    // Agent mode is default when ANTHROPIC_API_KEY is set (unless explicitly disabled)
    // BYOK: env var takes priority, then ~/.demiurge/config.json
    val agentDisabled = Option(System.getenv("DEMIURGE_AGENT_BACKEND")).exists(v => v.equalsIgnoreCase("none") || v.equalsIgnoreCase("disabled"))
    val agentMode = !agentDisabled && CredentialStore.resolveApiKey("ANTHROPIC_API_KEY", "anthropic").isDefined
    val worktreePath = try {
      WorktreeManager.create(global.repo, runId, cmd.gitRef.orElse(Some("HEAD")), agentMode = agentMode)
    } catch {
      case e: Exception =>
        System.err.println(OutputFormatter.formatError(s"Failed to create worktree: ${e.getMessage}", global.format))
        return ExitCodes.Errored
    }

    // Acquire run lock (Spec §4.3)
    val lockFilePath = try {
      LockManager.acquire(global.repo, runId, worktreePath)
    } catch {
      case e: IllegalStateException =>
        System.err.println(OutputFormatter.formatError(e.getMessage, global.format))
        WorktreeManager.remove(global.repo, runId)
        return ExitCodes.ConcurrentRunConflict
      case e: Exception =>
        System.err.println(OutputFormatter.formatError(s"Failed to acquire lock: ${e.getMessage}", global.format))
        WorktreeManager.remove(global.repo, runId)
        return ExitCodes.Errored
    }

    val run = TaskRun(
      runId = runId,
      repoPath = global.repo,
      worktreePath = worktreePath,
      gitRef = cmd.gitRef,
      taskText = cmd.task,
      changedFiles = cmd.changedFiles,
      status = RunStatus.Created,
      runMode = runMode,
      createdAt = Instant.now(),
      startedAt = None,
      endedAt = None,
      maxAttempts = cmd.maxAttempts.getOrElse(budget.maxAttempts),
      attemptCount = 0,
      envBootAttempts = 0,
      currentAttemptId = None,
      finalVerdict = None,
      finalSummary = None,
      policySnapshotId = s"policy-$runId",
      lockFilePath = lockFilePath,
      artifactRootPath = artifactRoot,
    )

    TaskRunRepo.insert(run)

    if (!global.quiet) {
      System.out.println(OutputFormatter.formatRun(run, global.format))
    }

    // Auto-trigger smart init when no demiurge.yaml exists and ANTHROPIC_API_KEY is available
    val manifestPath = worktreePath.resolve("demiurge.yaml")
    if (!Files.exists(manifestPath) && agentMode) {
      if (!global.quiet) {
        System.err.println("[init] No demiurge.yaml found — running smart init automatically...")
      }
      val initResult = AgentInitExecutor.execute(
        repoRoot = worktreePath,
        outputPath = manifestPath,
        force = false,
        quiet = global.quiet,
      )
      if (initResult.success) {
        if (!global.quiet) {
          System.err.println(s"[init] ${initResult.summary}")
        }
        // Post-process: replace worktree paths with repo root paths in the generated YAML.
        // The agent explores the worktree, so CWDs will reference it. We need repo root paths
        // so that remapPlanCwds() can correctly remap them to each run's worktree.
        val worktreeStr = worktreePath.toAbsolutePath.normalize().toString
        val repoStr = global.repo.toAbsolutePath.normalize().toString
        val fixPaths = (yaml: String) => yaml.replace(worktreeStr, repoStr)

        // Rewrite the files in the worktree with corrected paths
        try {
          initResult.demiurgeYaml.foreach { yaml =>
            Files.writeString(manifestPath, fixPaths(yaml))
          }
          val worktreeReqs = worktreePath.resolve("requirements.yaml")
          initResult.requirementsYaml.foreach { yaml =>
            Files.writeString(worktreeReqs, fixPaths(yaml))
          }
        } catch { case _: Exception => }

        // Also copy the corrected files back to the original repo for future runs
        try {
          val repoManifest = global.repo.resolve("demiurge.yaml")
          if (!Files.exists(repoManifest)) {
            initResult.demiurgeYaml.foreach(yaml => Files.writeString(repoManifest, fixPaths(yaml)))
          }
          val repoReqs = global.repo.resolve("requirements.yaml")
          if (!Files.exists(repoReqs)) {
            initResult.requirementsYaml.foreach(yaml => Files.writeString(repoReqs, fixPaths(yaml)))
          }
        } catch { case _: Exception => /* best-effort copy to repo root */ }
      } else {
        if (!global.quiet) {
          System.err.println(s"[init] Smart init failed: ${initResult.summary}")
          System.err.println("[init] Continuing with inspection-based planning...")
        }
      }
    }

    // Start local API server (Spec §14.4)
    val dbPath = global.repo.resolve(".demiurge").resolve("demiurge.db")
    try {
      demiurge.api.Routes.setRunStarter((task: String, apiConn: Connection) => {
        try {
          val apiRunId = UUID.randomUUID().toString
          val apiRun = TaskRun(
            runId = apiRunId, repoPath = global.repo, worktreePath = global.repo,
            gitRef = None, taskText = task, changedFiles = None,
            status = RunStatus.Created, runMode = RunMode.Full,
            createdAt = Instant.now(), startedAt = None, endedAt = None,
            maxAttempts = 5, attemptCount = 0, envBootAttempts = 0,
            currentAttemptId = None, finalVerdict = None, finalSummary = None,
            policySnapshotId = s"policy-$apiRunId",
            lockFilePath = global.repo.resolve(".demiurge").resolve("run.lock"),
            artifactRootPath = global.repo.resolve(".demiurge").resolve("artifacts").resolve(apiRunId),
          )
          TaskRunRepo.insert(apiRun)(apiConn)
          Some(apiRunId)
        } catch { case _: Exception => None }
      })

      LocalApiServer.start(
        port = 19440,
        dbPath = dbPath,
        artifactRootResolver = rid => Some(global.repo.resolve(".demiurge").resolve("artifacts").resolve(rid)),
      )
      if (!global.quiet) {
        System.out.println("Local API server started on http://127.0.0.1:19440")
      }
    } catch {
      case e: Exception =>
        if (!global.quiet) {
          System.err.println(s"Warning: Could not start local API server: ${e.getMessage}")
        }
    }

    // Delegate to shared orchestration runner (manages SSE, worker, artifacts, cleanup)
    val finalRun = try {
      OrchestrationRunner.run(run, global, worktreePath, conn)
    } catch {
      case e: Exception =>
        System.err.println(OutputFormatter.formatError(s"Orchestration failed: ${e.getMessage}", global.format))
        try { TaskRunRepo.updateStatus(runId, RunStatus.Exhausted, endedAt = Some(Instant.now())) } catch { case _: Exception => }
        return ExitCodes.Errored
    } finally {
      LockManager.release(global.repo)
      demiurge.api.Routes.clearRunStarter()
    }

    // Phase E: Post-run git operations (--branch / --open-pr)
    if (finalRun.status == RunStatus.Succeeded && (cmd.branch.isDefined || cmd.openPr)) {
      val gitMessage = GitIntegration.handlePostRun(
        repoPath = global.repo,
        worktreePath = worktreePath,
        taskText = cmd.task,
        branch = cmd.branch,
        openPr = cmd.openPr,
      )
      gitMessage.foreach(msg => if (!global.quiet) System.out.println(msg))
    }

    if (!global.quiet) {
      System.out.println(OutputFormatter.formatRun(finalRun, global.format))
    }

    ExitCodes.fromRunStatus(finalRun.status)
  }

  /** Build a RequirementCompiler from worktree YAML files, falling back to empty if not present. */
  private[cli] def buildCompiler(worktreePath: Path): RequirementCompilerImpl = {
    val reqsFile = worktreePath.resolve("requirements.yaml")
    val selsFile = worktreePath.resolve("selectors.yaml")

    val reqs = if (Files.exists(reqsFile)) {
      RequirementsParser.parse(reqsFile).getOrElse(RequirementsFile(Nil))
    } else RequirementsFile(Nil)

    val sels = if (Files.exists(selsFile)) {
      SelectorsParser.parse(selsFile).getOrElse(SelectorsFile(Nil))
    } else SelectorsFile(Nil)

    new RequirementCompilerImpl(reqs, sels)
  }

  /** Build repair backend if ANTHROPIC_API_KEY is set (env var or BYOK config). */
  private[cli] def buildRepairBackend(): Option[RepairBackend] = {
    val apiKey = CredentialStore.resolveApiKey("ANTHROPIC_API_KEY", "anthropic").orNull
    if (apiKey != null && apiKey.nonEmpty) {
      try {
        Some(new demiurge.repair.claude.ClaudeRepairBackend())
      } catch {
        case _: Exception => None
      }
    } else None
  }

  /**
   * Build browser executor if a worker entry point exists.
   * Returns (executor option, worker manager option for shutdown).
   * Worker is spawned lazily — only initialized when browser verification is actually needed.
   */
  private[cli] def buildBrowserExecutor(
    worktreePath: Path,
    artifactRoot: Path,
    runId: String,
  ): (Option[VerificationEngine.BrowserVerifierExecutor], Option[WorkerProcessManager]) = {
    // Look for worker entry point in standard locations
    val workerLocations = List(
      worktreePath.resolve("node_modules/.bin/demiurge-worker"),
      worktreePath.resolve("worker/src/index.ts"),
      worktreePath.resolve("worker/dist/index.js"),
    )
    val workerScript = workerLocations.find(Files.exists(_))

    workerScript match {
      case Some(script) =>
        val wpm = new WorkerProcessManager(script)
        val executor = new WorkerBrowserExecutor(wpm, artifactRoot.toString, worktreePath.toString, runId)
        (Some(executor), Some(wpm))
      case None =>
        // No worker script found — browser verifiers will error gracefully
        (None, None)
    }
  }

}

/**
 * BrowserVerifierExecutor that wraps WorkerProcessManager.
 * Spawns and initializes the worker on first use (lazy).
 * Maps BrowserFlowVerifier fields to WorkerMessages params and back.
 */
class WorkerBrowserExecutor(
  wpm: WorkerProcessManager,
  artifactRoot: String,
  worktreePath: String,
  runId: String,
) extends VerificationEngine.BrowserVerifierExecutor {

  @volatile private var initialized = false

  private def ensureInitialized(): Unit = {
    if (!initialized) {
      wpm.spawn()
      wpm.initialize(artifactRoot, worktreePath, runId) match {
        case Right(_) => initialized = true
        case Left(err) => throw new RuntimeException(s"Worker init failed: $err")
      }
    }
  }

  override def execute(bv: BrowserFlowVerifier): BrowserVerifierResult = {
    try {
      ensureInitialized()

      // Map BrowserAction/Assertion/ArtifactCapture to Json
      val actionJsons = bv.actions.map(actionToJson)
      val assertionJsons = bv.assertions.map(assertionToJson)
      val artifactPlanJsons = bv.artifactPlan.map(captureToJson)

      val params = WorkerMessages.executeBrowserFlowParams(
        taskId = bv.id,
        entryUrl = bv.entryUrl,
        actions = actionJsons,
        assertions = assertionJsons,
        artifactPlan = artifactPlanJsons,
        storageStatePath = bv.storageStatePath,
        timeoutMs = Some(bv.timeout.toMillis.toInt),
      )

      wpm.executeBrowserFlow(params, bv.timeout.toMillis) match {
        case Right(result) =>
          val outcome = result.status match {
            case "passed" => VerifierOutcome.Passed
            case "failed" => VerifierOutcome.Failed(result.errorMessage.getOrElse("Browser flow failed"))
            case "error"  => VerifierOutcome.Error(result.errorMessage.getOrElse("Browser flow error"))
            case _        => VerifierOutcome.Error(s"Unknown status: ${result.status}")
          }

          val observations = result.observations.map { obs =>
            Observation(
              observationType = obs.observationType,
              message = obs.message,
              selector = obs.selector,
              expected = obs.expected,
              actual = obs.actual,
              timestamp = try { Instant.parse(obs.timestamp) } catch { case _: Exception => Instant.now() },
            )
          }

          val artifactRefs = result.artifacts.map(_.relativePath)

          BrowserVerifierResult(outcome, observations, artifactRefs)

        case Left(err) =>
          BrowserVerifierResult(VerifierOutcome.Error(s"Worker error: $err"), Nil, Nil)
      }
    } catch {
      case e: Exception =>
        BrowserVerifierResult(VerifierOutcome.Error(s"Worker exception: ${e.getMessage}"), Nil, Nil)
    }
  }

  private def actionToJson(a: BrowserAction): Json = Json.obj(
    "actionType" -> Json.fromString(a.actionType),
    "selector" -> a.selector.map(s => Json.fromString(s.value)).getOrElse(Json.Null),
    "value" -> a.value.map(Json.fromString).getOrElse(Json.Null),
    "url" -> a.url.map(Json.fromString).getOrElse(Json.Null),
    "description" -> Json.fromString(a.description),
  )

  private def assertionToJson(a: Assertion): Json = Json.obj(
    "assertionType" -> Json.fromString(a.assertionType),
    "selector" -> a.selector.map(s => Json.fromString(s.value)).getOrElse(Json.Null),
    "expected" -> a.expected.map(Json.fromString).getOrElse(Json.Null),
    "description" -> Json.fromString(a.description),
  )

  private def captureToJson(c: ArtifactCapture): Json = Json.obj(
    "artifactType" -> Json.fromString(c.artifactType.toString),
    "trigger" -> Json.fromString(c.trigger),
    "label" -> c.label.map(Json.fromString).getOrElse(Json.Null),
  )
}
