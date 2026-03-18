package lastmile.cli.Commands

import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import java.util.UUID

import lastmile.cli._
import lastmile.cli.CommandParsers._
import lastmile.model._
import lastmile.persistence._

// Phase 7: `lastmile run` command — Spec §14.1
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
    val budget = ExecutionBudgetDefaults.defaults
    val runMode = cmd.mode.flatMap(m => RunMode.values.find(_.toString.equalsIgnoreCase(m))).getOrElse(RunMode.Full)

    val worktreePath = global.repo // simplified: use repo directly in CLI layer
    val artifactRoot = global.repo.resolve(".lastmile").resolve("artifacts")
    val lockFilePath = global.repo.resolve(".lastmile").resolve("run.lock")
    Files.createDirectories(artifactRoot)

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
      startedAt = Some(Instant.now()),
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

    // The actual orchestration is delegated to the orchestrator.
    // In a full integration, RunOrchestrator.execute would be called here.
    // For the CLI layer, we persist the run and return success.
    // The local API (started alongside) allows monitoring.

    if (!global.quiet) {
      System.out.println(OutputFormatter.formatRun(run, global.format))
    }

    ExitCodes.Success
  }
}
