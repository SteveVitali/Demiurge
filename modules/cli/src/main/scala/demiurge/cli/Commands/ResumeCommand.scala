package demiurge.cli.Commands

import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.Instant

import demiurge.cli._
import demiurge.cli.CommandParsers._
import demiurge.model._
import demiurge.persistence._
import demiurge.orchestrator._
import demiurge.api.EventStream

// Phase 10: `demiurge resume` command — resumes interrupted runs via orchestrator
object ResumeCommand {

  private val resumableStatuses: Set[RunStatus] = Set(
    RunStatus.Interrupted,
    RunStatus.ReadyToVerify,
    RunStatus.AnalyzingFailure,
    RunStatus.PlanningRepair,
  )

  def execute(cmd: ResumeCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    TaskRunRepo.getById(cmd.runId) match {
      case None =>
        System.err.println(OutputFormatter.formatError(s"Run not found: ${cmd.runId}", global.format))
        ExitCodes.NotFound

      case Some(run) =>
        if (!resumableStatuses.contains(run.status)) {
          System.err.println(OutputFormatter.formatError(
            s"Run ${cmd.runId} is not resumable (status: ${run.status})", global.format))
          return ExitCodes.ResumeFailed
        }

        val worktreePath = run.worktreePath
        if (!Files.exists(worktreePath)) {
          System.err.println(OutputFormatter.formatError(
            s"Worktree not found at ${worktreePath}. Cannot resume.", global.format))
          return ExitCodes.ResumeFailed
        }

        // Reset status to Created so the orchestrator re-runs the full pipeline
        TaskRunRepo.updateStatus(cmd.runId, RunStatus.Created)
        TaskRunRepo.setStartedAt(cmd.runId, Instant.now())
        val resumedRun = run.copy(status = RunStatus.Created, startedAt = Some(Instant.now()))

        if (!global.quiet) {
          System.out.println(OutputFormatter.formatSuccess(
            s"Resuming run ${cmd.runId} from status ${run.status}", global.format))
        }

        // Delegate to shared orchestration runner
        val finalRun = try {
          OrchestrationRunner.run(resumedRun, global, worktreePath, conn)
        } catch {
          case e: Exception =>
            System.err.println(OutputFormatter.formatError(s"Resume failed: ${e.getMessage}", global.format))
            try { TaskRunRepo.updateStatus(cmd.runId, RunStatus.Exhausted, endedAt = Some(Instant.now())) } catch { case _: Exception => }
            return ExitCodes.Errored
        }

        if (!global.quiet) {
          System.out.println(OutputFormatter.formatRun(finalRun, global.format))
        }

        ExitCodes.fromRunStatus(finalRun.status)
    }
  }
}
