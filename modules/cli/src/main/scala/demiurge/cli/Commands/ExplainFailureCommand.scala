package demiurge.cli.Commands

import java.sql.Connection

import demiurge.cli._
import demiurge.cli.CommandParsers._
import demiurge.model._
import demiurge.persistence._

// Phase 7: `demiurge explain-failure` command — Spec §14.1
object ExplainFailureCommand {

  def execute(cmd: ExplainFailureCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    TaskRunRepo.getById(cmd.runId) match {
      case None =>
        System.err.println(OutputFormatter.formatError(s"Run not found: ${cmd.runId}", global.format))
        ExitCodes.NotFound

      case Some(run) =>
        val verdicts = cmd.attempt match {
          case Some(n) => VerdictRepo.listByRunAndAttempt(cmd.runId, n)
          case None    => VerdictRepo.listByRunId(cmd.runId)
        }

        System.out.println(OutputFormatter.formatFailureExplanation(verdicts, global.format))
        ExitCodes.Success
    }
  }
}
