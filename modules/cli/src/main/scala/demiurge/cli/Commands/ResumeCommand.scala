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

// Phase 10: `demiurge resume` command — resumes interrupted runs via orchestrator.
// Gap 6: Now uses ResumeManager.prepareResume() and passes resumeFromStatus
// to OrchestrationRunner so the orchestrator skips completed phases.
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

        // Gap 6: Determine correct resume state.
        // ResumeManager.prepareResume() only handles Interrupted status (it does
        // orphan cleanup, worktree verification, etc.). For other resumable statuses
        // (ReadyToVerify, AnalyzingFailure, PlanningRepair), we map directly to a
        // resume state using ResumeManager.resumeStateFor().
        val now = Instant.now()
        val (resumedRun, resumeFromStatus) = if (run.status == RunStatus.Interrupted) {
          val resumeResult = ResumeManager.prepareResume(cmd.runId, global.repo)
          resumeResult match {
            case ResumeManager.ResumeReady(updatedRun, resumeState) =>
              TaskRunRepo.setStartedAt(cmd.runId, now)
              (updatedRun.copy(startedAt = Some(now)), Some(resumeState))
            case ResumeManager.ResumeFailed(_) =>
              // Fallback: reset to Created for full re-run
              TaskRunRepo.updateStatus(cmd.runId, RunStatus.Created)
              TaskRunRepo.setStartedAt(cmd.runId, now)
              (run.copy(status = RunStatus.Created, startedAt = Some(now)), None)
          }
        } else {
          // Non-Interrupted resumable status — map directly to resume point
          val resumeState = ResumeManager.resumeStateFor(
            run.status, run.attemptCount, run.maxAttempts)
          TaskRunRepo.updateStatus(cmd.runId, resumeState)
          TaskRunRepo.setStartedAt(cmd.runId, now)
          (run.copy(status = resumeState, startedAt = Some(now)), Some(resumeState))
        }

        if (!global.quiet) {
          val fromDesc = resumeFromStatus.map(s => s" from $s").getOrElse(" from scratch")
          System.out.println(OutputFormatter.formatSuccess(
            s"Resuming run ${cmd.runId}$fromDesc", global.format))
        }

        // Delegate to shared orchestration runner with resume state
        val finalRun = try {
          OrchestrationRunner.run(resumedRun, global, worktreePath, conn,
            resumeFromStatus = resumeFromStatus)
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
