package demiurge.cli.Commands

import java.nio.file.{Files, Paths}
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch

import io.circe.parser.{decode => jsonDecode}
import io.circe.Json

import demiurge.api.{LocalApiServer, Routes}
import demiurge.cli.ExitCodes
import demiurge.cli.CommandParsers.{GlobalOpts, ServeCmd}
import demiurge.model.{RunMode, RunStatus, TaskRun}
import demiurge.persistence.{Database, TaskRunRepo}
import demiurge.orchestrator.{WorktreeManager, LockManager}
import demiurge.license.CredentialStore

// Desktop Phase 5 — Appendix C: Backend `serve` command.
// Persistent server mode for the desktop app sidecar.
// Starts LocalApiServer + WebSocketServer, accepts POST /runs to start orchestration,
// blocks until SIGTERM, then graceful shutdown.
object ServeCommand {

  def execute(cmd: ServeCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    // serve daemon uses ~/.demiurge/ as default data dir (not CWD, which may be
    // Bazel runfiles or another non-writable location when launched as a sidecar)
    val dataDir = cmd.dbPath
      .map(p => Paths.get(p).getParent)
      .getOrElse(Paths.get(System.getProperty("user.home")).resolve(".demiurge"))
    Files.createDirectories(dataDir)

    val dbPath = cmd.dbPath
      .map(Paths.get(_))
      .getOrElse(dataDir.resolve("demiurge.db"))

    val artifactRoot = dataDir.resolve("artifacts")
    Files.createDirectories(artifactRoot)

    // Register run-starter callback so POST /runs can start orchestration on a new thread.
    // Only one run at a time — return 409 if a run is already in progress.
    // The body parameter is the full JSON string from the HTTP request, allowing
    // the UI to pass repoPath, mode, maxAttempts, runTimeoutMs, agentBackend, etc.
    Routes.setRunStarter((body: String, apiConn: Connection) => {
      val activeRun = TaskRunRepo.getActiveRun()(apiConn)
      if (activeRun.isDefined) {
        None
      } else {
        try {
          val json = jsonDecode[Json](body).getOrElse(Json.obj())
          val cursor = json.hcursor

          val task = cursor.get[String]("task").getOrElse("")
          val repoPath = cursor.get[String]("repoPath")
            .toOption.map(Paths.get(_)).getOrElse(global.repo)
          val runMode = cursor.get[String]("mode")
            .toOption.flatMap(m => RunMode.values.find(_.toString.equalsIgnoreCase(m)))
            .getOrElse(RunMode.Full)
          val maxAttempts = cursor.get[Int]("maxAttempts").toOption.getOrElse(
            if (runMode == RunMode.Build) 8 else 5
          )
          val createWorktree = cursor.get[Boolean]("createWorktree").toOption.getOrElse(true)

          val runId = UUID.randomUUID().toString
          val runArtifactRoot = artifactRoot.resolve(runId)
          Files.createDirectories(runArtifactRoot)

          // Create isolated git worktree (matching RunCommand behavior)
          val agentDisabled = Option(System.getenv("DEMIURGE_AGENT_BACKEND"))
            .exists(v => v.equalsIgnoreCase("none") || v.equalsIgnoreCase("disabled"))
          val agentMode = !agentDisabled && CredentialStore.resolveApiKey("ANTHROPIC_API_KEY", "anthropic").isDefined

          val worktreePath = if (createWorktree) {
            try {
              val wt = WorktreeManager.create(repoPath, runId, gitRef = Some("HEAD"), agentMode = agentMode)
              System.err.println(s"[serve] Created worktree at $wt")
              wt
            } catch {
              case e: Exception =>
                System.err.println(s"[serve] Worktree creation failed: ${e.getMessage}, using repo directly")
                repoPath
            }
          } else repoPath

          // Acquire run lock
          val lockFilePath = try {
            LockManager.acquire(repoPath, runId, worktreePath)
          } catch {
            case _: Exception =>
              repoPath.resolve(".demiurge").resolve("run.lock")
          }

          // Auto-trigger smart init when no demiurge.yaml exists and agent mode is available
          val manifestPath = worktreePath.resolve("demiurge.yaml")
          if (!Files.exists(manifestPath) && agentMode) {
            System.err.println("[serve] No demiurge.yaml found — running smart init automatically...")
            try {
              val initResult = AgentInitExecutor.execute(
                repoRoot = worktreePath,
                outputPath = manifestPath,
                force = false,
                quiet = false,
              )
              if (initResult.success) {
                System.err.println(s"[serve] Smart init: ${initResult.summary}")
                // Post-process: replace worktree paths with repo root paths
                val worktreeStr = worktreePath.toAbsolutePath.normalize().toString
                val repoStr = repoPath.toAbsolutePath.normalize().toString
                val fixPaths = (yaml: String) => yaml.replace(worktreeStr, repoStr)
                try {
                  initResult.demiurgeYaml.foreach(yaml => Files.writeString(manifestPath, fixPaths(yaml)))
                  val worktreeReqs = worktreePath.resolve("requirements.yaml")
                  initResult.requirementsYaml.foreach(yaml => Files.writeString(worktreeReqs, fixPaths(yaml)))
                } catch { case _: Exception => }
                // Copy to original repo for future runs
                try {
                  val repoManifest = repoPath.resolve("demiurge.yaml")
                  if (!Files.exists(repoManifest))
                    initResult.demiurgeYaml.foreach(yaml => Files.writeString(repoManifest, fixPaths(yaml)))
                  val repoReqs = repoPath.resolve("requirements.yaml")
                  if (!Files.exists(repoReqs))
                    initResult.requirementsYaml.foreach(yaml => Files.writeString(repoReqs, fixPaths(yaml)))
                } catch { case _: Exception => }
              } else {
                System.err.println(s"[serve] Smart init failed: ${initResult.summary}")
                System.err.println("[serve] Continuing with inspection-based planning...")
              }
            } catch {
              case e: Exception =>
                System.err.println(s"[serve] Smart init error: ${e.getMessage}")
            }
          }

          val run = TaskRun(
            runId = runId,
            repoPath = repoPath,
            worktreePath = worktreePath,
            gitRef = None,
            taskText = task,
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
            policySnapshotId = s"policy-$runId",
            lockFilePath = lockFilePath,
            artifactRootPath = runArtifactRoot,
          )
          TaskRunRepo.insert(run)(apiConn)

          // Spawn orchestration on a background thread so the HTTP response returns immediately
          val orchestrationGlobal = global.copy(repo = repoPath)
          val orchestrationThread = new Thread(() => {
            val threadConn = Database.open(dbPath)
            try {
              System.err.println(s"[serve] Starting orchestration for run $runId (mode=$runMode)")
              OrchestrationRunner.run(run, orchestrationGlobal, worktreePath, threadConn)
              System.err.println(s"[serve] Orchestration completed for run $runId")
            } catch {
              case e: Exception =>
                System.err.println(s"[serve] Orchestration failed for run $runId: ${e.getMessage}")
                e.printStackTrace(System.err)
                try {
                  TaskRunRepo.updateStatus(runId, RunStatus.Exhausted, endedAt = Some(Instant.now()))(threadConn)
                } catch { case _: Exception => }
            } finally {
              // Clean up worktree on completion
              if (createWorktree && worktreePath != repoPath) {
                try { WorktreeManager.remove(repoPath, runId) } catch { case _: Exception => }
              }
              try { LockManager.release(repoPath) } catch { case _: Exception => }
              try { threadConn.close() } catch { case _: Exception => }
            }
          }, s"orchestration-$runId")
          orchestrationThread.setDaemon(true)
          orchestrationThread.start()

          Some(runId)
        } catch {
          case e: Exception =>
            System.err.println(s"Failed to create run: ${e.getMessage}")
            e.printStackTrace(System.err)
            None
        }
      }
    })

    // Start HTTP API server + WebSocket server
    try {
      LocalApiServer.start(
        port = cmd.port,
        wsPort = cmd.wsPort,
        dbPath = dbPath,
        artifactRootResolver = _ => Some(artifactRoot),
      )
    } catch {
      case e: Exception =>
        System.err.println(s"Failed to start server: ${e.getMessage}")
        return ExitCodes.Errored
    }

    System.out.println(s"Demiurge server listening on :${cmd.port} (HTTP) and :${cmd.wsPort} (WS)")
    System.out.println(s"Database: $dbPath")
    System.out.println("Press Ctrl+C to stop")

    // Block until SIGTERM / SIGINT
    val latch = new CountDownLatch(1)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      System.err.println("Shutting down Demiurge server...")
      LocalApiServer.stop()
      latch.countDown()
    }))

    try {
      latch.await()
    } catch {
      case _: InterruptedException => // shutdown
    }

    ExitCodes.Success
  }
}
