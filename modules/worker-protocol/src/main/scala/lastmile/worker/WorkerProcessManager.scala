package lastmile.worker

import java.nio.file.Path
import io.circe.Json

// Spec §10.1: WorkerProcessManager — spawns the TypeScript worker process,
// manages lifecycle, enforces restart budget, detects crashes.
// Worker spawns on first browser need or at auth bootstrap.
// Worker persists across attempts. Restart budget enforced.
class WorkerProcessManager(
  workerScript: Path,
  maxRestarts:  Int = 3,
  command:      Option[List[String]] = None, // Override spawn command for testing
) {
  @volatile private var process: Process = _
  @volatile private var client: WorkerClient = _
  @volatile private var initialized = false
  @volatile private var restartCount = 0
  @volatile private var alive = false

  // Spec §10.1: Spawn worker process
  // Budget check is handled by restartIfNeeded; spawn() only guards against double-spawn.
  def spawn(): Unit = {
    if (alive && !hasCrashed) return

    val cmd = command.getOrElse(List("node", workerScript.toAbsolutePath.toString))
    val pb = new ProcessBuilder(cmd: _*)
    pb.redirectErrorStream(false)
    process = pb.start()
    client = new WorkerClient(
      process.getOutputStream,
      process.getInputStream,
      process.getErrorStream,
    )
    client.start()
    alive = true
  }

  // Spec §10.2: Initialize the worker with artifact root, worktree, and run id
  def initialize(artifactRoot: String, worktreePath: String, runId: String, timeoutMs: Long = 30000): Either[String, WorkerMessages.InitializeResult] = {
    if (!alive || hasCrashed) {
      spawn()
    }

    val params = WorkerMessages.initializeParams(artifactRoot, worktreePath, runId)
    client.sendRequest("initialize", params, timeoutMs).flatMap { json =>
      WorkerMessages.parseInitializeResult(json)
    } match {
      case Right(result) =>
        initialized = true
        Right(result)
      case Left(err) =>
        Left(s"Worker initialization failed: $err")
    }
  }

  // Spec §10.2: Send ping to check worker health
  def ping(timeoutMs: Long = 5000): Boolean = {
    if (!alive || hasCrashed) return false
    client.sendRequest("ping", Json.obj(), timeoutMs) match {
      case Right(_) => true
      case Left(_) => false
    }
  }

  // Spec §10.3: Execute browser flow
  def executeBrowserFlow(params: Json, timeoutMs: Long = 60000): Either[String, WorkerMessages.BrowserFlowResult] = {
    ensureAlive()
    client.sendRequest("executeBrowserFlow", params, timeoutMs).flatMap(
      WorkerMessages.parseBrowserFlowResult)
  }

  // Spec §10.4: Execute auth bootstrap
  def executeAuthBootstrap(params: Json, timeoutMs: Long = 60000): Either[String, WorkerMessages.AuthBootstrapResult] = {
    ensureAlive()
    client.sendRequest("executeAuthBootstrap", params, timeoutMs).flatMap(
      WorkerMessages.parseAuthBootstrapResult)
  }

  // Spec §10.5: Execute API request
  def executeApiRequest(params: Json, timeoutMs: Long = 30000): Either[String, WorkerMessages.ApiRequestResult] = {
    ensureAlive()
    client.sendRequest("executeApiRequest", params, timeoutMs).flatMap(
      WorkerMessages.parseApiRequestResult)
  }

  // Spec §10.6: Capture page snapshot
  def capturePageSnapshot(params: Json, timeoutMs: Long = 30000): Either[String, WorkerMessages.PageSnapshotResult] = {
    ensureAlive()
    client.sendRequest("capturePageSnapshot", params, timeoutMs).flatMap(
      WorkerMessages.parsePageSnapshotResult)
  }

  // Spec §10.2: Cancel active task
  def cancel(timeoutMs: Long = 5000): Either[String, Json] = {
    if (!alive) return Left("Worker not alive")
    client.sendRequest("cancel", Json.obj(), timeoutMs)
  }

  // Spec §10.2: Shut down worker gracefully
  def shutdown(timeoutMs: Long = 10000): Unit = {
    if (alive && !hasCrashed) {
      try {
        client.sendRequest("shutdown", Json.obj(), timeoutMs)
      } catch {
        case _: Exception => // Ignore errors during shutdown
      }
    }
    cleanup()
  }

  // Spec §10.1: Detect worker crash and attempt restart
  def hasCrashed: Boolean = {
    if (client != null && client.hasCrashed) return true
    if (process != null) {
      try {
        process.exitValue()
        return true // Process has exited
      } catch {
        case _: IllegalThreadStateException => // Still running
      }
    }
    false
  }

  // Spec §10.1: Restart crashed worker within budget
  def restartIfNeeded(artifactRoot: String, worktreePath: String, runId: String): Either[String, WorkerMessages.InitializeResult] = {
    if (!hasCrashed && initialized) {
      return Right(WorkerMessages.InitializeResult("cached", Map.empty))
    }

    restartCount += 1
    if (restartCount > maxRestarts) {
      return Left(s"Worker restart budget exhausted ($restartCount/$maxRestarts)")
    }

    cleanup()
    alive = false
    initialized = false
    spawn()
    initialize(artifactRoot, worktreePath, runId)
  }

  def isAlive: Boolean = alive && !hasCrashed
  def isInitialized: Boolean = initialized
  def getRestartCount: Int = restartCount

  def getStderrLog: String = {
    if (client != null) client.getStderrLog else ""
  }

  def setNotificationHandler(handler: JsonRpcNotification => Unit): Unit = {
    if (client != null) client.setNotificationHandler(handler)
  }

  private def ensureAlive(): Unit = {
    if (!alive || hasCrashed) {
      throw new RuntimeException("Worker process is not alive")
    }
    if (!initialized) {
      throw new RuntimeException("Worker not initialized")
    }
  }

  private def cleanup(): Unit = {
    if (client != null) {
      try { client.close() } catch { case _: Exception => }
    }
    if (process != null) {
      try { process.destroyForcibly() } catch { case _: Exception => }
    }
    alive = false
    initialized = false
  }
}
