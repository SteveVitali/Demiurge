package lastmile.cli.Commands

import java.sql.Connection
import java.time.Instant

import lastmile.cli._
import lastmile.cli.CommandParsers._
import lastmile.model._
import lastmile.persistence._

// Phase 7: `lastmile resume` command — Spec §14.1
object ResumeCommand {

  // Resumable statuses: non-terminal, non-Created
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

        // Mark run as resuming by transitioning back to its status
        TaskRunRepo.setStartedAt(cmd.runId, Instant.now())

        if (!global.quiet) {
          System.out.println(OutputFormatter.formatSuccess(
            s"Resuming run ${cmd.runId} from status ${run.status}", global.format))
        }

        // Actual resume orchestration would be invoked here.
        // For the CLI layer, we validate and signal readiness.
        ExitCodes.Success
    }
  }
}
