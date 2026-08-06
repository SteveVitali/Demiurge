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

          val runId = UUID.randomUUID().toString
          val runArtifactRoot = artifactRoot.resolve(runId)
          Files.createDirectories(runArtifactRoot)

          val run = TaskRun(
            runId = runId,
            repoPath = repoPath,
            worktreePath = repoPath,
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
            lockFilePath = repoPath.resolve(".demiurge").resolve("run.lock"),
            artifactRootPath = runArtifactRoot,
          )
          TaskRunRepo.insert(run)(apiConn)

          // Spawn orchestration on a background thread so the HTTP response returns immediately
          val orchestrationGlobal = global.copy(repo = repoPath)
          val orchestrationThread = new Thread(() => {
            val threadConn = Database.open(dbPath)
            try {
              System.err.println(s"[serve] Starting orchestration for run $runId (mode=$runMode)")
              OrchestrationRunner.run(run, orchestrationGlobal, repoPath, threadConn)
              System.err.println(s"[serve] Orchestration completed for run $runId")
            } catch {
              case e: Exception =>
                System.err.println(s"[serve] Orchestration failed for run $runId: ${e.getMessage}")
                try {
                  TaskRunRepo.updateStatus(runId, RunStatus.Exhausted, endedAt = Some(Instant.now()))(threadConn)
                } catch { case _: Exception => }
            } finally {
              try { threadConn.close() } catch { case _: Exception => }
            }
          }, s"orchestration-$runId")
          orchestrationThread.setDaemon(true)
          orchestrationThread.start()

          Some(runId)
        } catch {
          case e: Exception =>
            System.err.println(s"Failed to create run: ${e.getMessage}")
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
        artifactRootResolver = rid => Some(artifactRoot.resolve(rid)),
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
