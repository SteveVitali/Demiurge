package lastmile.cli.Commands

import java.sql.Connection

import lastmile.cli._
import lastmile.cli.CommandParsers._
import lastmile.model._
import lastmile.persistence._

// Phase 7: `lastmile inspect-run` command — Spec §14.1
object InspectRunCommand {

  def execute(cmd: InspectRunCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    TaskRunRepo.getById(cmd.runId) match {
      case None =>
        System.err.println(OutputFormatter.formatError(s"Run not found: ${cmd.runId}", global.format))
        ExitCodes.NotFound

      case Some(run) =>
        System.out.println(OutputFormatter.formatRun(run, global.format))

        val attempts = cmd.attempt match {
          case Some(n) => AttemptRepo.getByRunAndNumber(cmd.runId, n).toList
          case None    => AttemptRepo.listByRunId(cmd.runId)
        }
        System.out.println(OutputFormatter.formatAttemptList(attempts, global.format))

        if (cmd.showVerdicts) {
          val verdicts = cmd.attempt match {
            case Some(n) => VerdictRepo.listByRunAndAttempt(cmd.runId, n)
            case None    => VerdictRepo.listByRunId(cmd.runId)
          }
          System.out.println(OutputFormatter.formatVerdictList(verdicts, global.format))
        }

        if (cmd.showArtifacts) {
          val artifacts = cmd.attempt match {
            case Some(n) => ArtifactRecordRepo.listByRunAndAttempt(cmd.runId, n)
            case None    => ArtifactRecordRepo.listByRunId(cmd.runId)
          }
          System.out.println(OutputFormatter.formatArtifactList(artifacts, global.format))
        }

        ExitCodes.Success
    }
  }
}
