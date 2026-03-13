package lastmile.cli.Commands

import java.sql.Connection
import java.time.Instant

import lastmile.cli._
import lastmile.cli.CommandParsers._
import lastmile.model._
import lastmile.persistence._

// Phase 7: `lastmile cancel` command — Spec §14.1
object CancelCommand {

  private val terminalStatuses: Set[RunStatus] = Set(
    RunStatus.Succeeded, RunStatus.Exhausted, RunStatus.Cancelled, RunStatus.Interrupted
  )

  def execute(cmd: CancelCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    val runOpt = cmd.runId match {
      case Some(id) => TaskRunRepo.getById(id)
      case None     => TaskRunRepo.getActiveRunByRepoPath(global.repo.toString)
    }

    runOpt match {
      case None =>
        System.err.println(OutputFormatter.formatError("No active run found to cancel", global.format))
        ExitCodes.NotFound

      case Some(run) =>
        if (terminalStatuses.contains(run.status)) {
          System.err.println(OutputFormatter.formatError(
            s"Run ${run.runId} is already in terminal state: ${run.status}", global.format))
          return ExitCodes.CommandFailure
        }

        TaskRunRepo.updateStatus(run.runId, RunStatus.Cancelled, Some(Instant.now()))
        System.out.println(OutputFormatter.formatSuccess(
          s"Run ${run.runId} cancelled", global.format))
        ExitCodes.Success
    }
  }
}
