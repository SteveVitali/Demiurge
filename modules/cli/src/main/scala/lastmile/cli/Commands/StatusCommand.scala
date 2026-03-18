package lastmile.cli.Commands

import java.sql.Connection

import lastmile.cli._
import lastmile.cli.CommandParsers._
import lastmile.model._
import lastmile.persistence._

// Phase 7: `lastmile status` command — Spec §14.1
object StatusCommand {

  def execute(cmd: StatusCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    cmd.runId match {
      case Some(id) =>
        TaskRunRepo.getById(id) match {
          case None =>
            System.err.println(OutputFormatter.formatError(s"Run not found: $id", global.format))
            ExitCodes.NotFound
          case Some(run) =>
            System.out.println(OutputFormatter.formatRun(run, global.format))
            ExitCodes.Success
        }
      case None =>
        // Show recent runs
        val runs = TaskRunRepo.listRecent(20)
        System.out.println(OutputFormatter.formatRunList(runs, global.format))
        ExitCodes.Success
    }
  }
}
