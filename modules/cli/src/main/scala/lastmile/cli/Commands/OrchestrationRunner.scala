package lastmile.cli.Commands

import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.Instant

import io.circe.Json
import io.circe.syntax._

import lastmile.cli.CommandParsers._
import lastmile.model._
import lastmile.persistence._
import lastmile.orchestrator._
import lastmile.api.{LocalApiServer, EventStream}
import lastmile.inspector.RepoInspectorImpl
import lastmile.planner.EnvironmentPlannerImpl
import lastmile.runtime.RuntimeSupervisorImpl
import lastmile.artifact.{ArtifactSinkImpl, EvidenceCollectorImpl}

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
  ): TaskRun = {
    implicit val c: Connection = conn
    val runId = taskRun.runId
    val artifactRoot = taskRun.artifactRootPath
    Files.createDirectories(artifactRoot)

    // Start local API server (best-effort, non-fatal)
    val dbPath = global.repo.resolve(".lastmile").resolve("lastmile.db")
    try {
      LocalApiServer.start(
        port = 19440,
        dbPath = dbPath,
        artifactRootResolver = rid => Some(global.repo.resolve(".lastmile").resolve("artifacts").resolve(rid)),
      )
    } catch { case _: Exception => }

    // Wire SSE event streaming
    RunTransitionManager.setEventListener(event => EventStream.publish(event))

    val compiler = RunCommand.buildCompiler(worktreePath)
    val repairBackend = RunCommand.buildRepairBackend()
    val (browserExecutor, workerManager) = RunCommand.buildBrowserExecutor(worktreePath, artifactRoot, runId)
    val artifactSink = new ArtifactSinkImpl(artifactRoot)
    val evidenceCollector = new EvidenceCollectorImpl(artifactSink)

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
      )

      writeFinalReport(evidenceCollector, runId, finalRun, conn)
      finalRun
    } finally {
      workerManager.foreach(w => try { w.shutdown() } catch { case _: Exception => })
      RunTransitionManager.clearEventListener()
      EventStream.markRunEnded(runId)
      LocalApiServer.stop()
    }
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
