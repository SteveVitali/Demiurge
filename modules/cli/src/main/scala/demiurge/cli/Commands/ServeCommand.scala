package demiurge.cli.Commands

import java.nio.file.{Files, Paths}
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch

import demiurge.api.{LocalApiServer, Routes}
import demiurge.cli.ExitCodes
import demiurge.cli.CommandParsers.{GlobalOpts, ServeCmd}
import demiurge.model.{RunMode, RunStatus, TaskRun}
import demiurge.persistence.TaskRunRepo

// Desktop Phase 5 — Appendix C: Backend `serve` command.
// Persistent server mode for the desktop app sidecar.
// Starts LocalApiServer + WebSocketServer, accepts POST /runs to start orchestration,
// blocks until SIGTERM, then graceful shutdown.
object ServeCommand {

  def execute(cmd: ServeCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    val dbPath = cmd.dbPath
      .map(Paths.get(_))
      .getOrElse(global.repo.resolve(".demiurge").resolve("demiurge.db"))

    val artifactRoot = global.repo.resolve(".demiurge").resolve("artifacts")
    Files.createDirectories(artifactRoot)

    // Register run-starter callback so POST /runs can start orchestration on a new thread.
    // Only one run at a time — return 409 if a run is already in progress.
    Routes.setRunStarter((task: String, apiConn: Connection) => {
      val activeRun = TaskRunRepo.getActiveRun()(apiConn)
      if (activeRun.isDefined) {
        // Concurrent run conflict — caller gets None which Routes converts to 409
        None
      } else {
        try {
          val runId = UUID.randomUUID().toString
          val run = TaskRun(
            runId = runId,
            repoPath = global.repo,
            worktreePath = global.repo,
            gitRef = None,
            taskText = task,
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
            policySnapshotId = s"policy-$runId",
            lockFilePath = global.repo.resolve(".demiurge").resolve("run.lock"),
            artifactRootPath = artifactRoot.resolve(runId),
          )
          TaskRunRepo.insert(run)(apiConn)
          Some(runId)
        } catch {
          case _: Exception => None
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
