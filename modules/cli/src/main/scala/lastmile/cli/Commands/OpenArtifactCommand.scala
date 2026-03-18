package lastmile.cli.Commands

import java.sql.Connection

import lastmile.cli._
import lastmile.cli.CommandParsers._
import lastmile.model._
import lastmile.persistence._

// Phase 7: `lastmile open-artifact` command — Spec §14.1
object OpenArtifactCommand {

  def execute(cmd: OpenArtifactCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    TaskRunRepo.getById(cmd.runId) match {
      case None =>
        System.err.println(OutputFormatter.formatError(s"Run not found: ${cmd.runId}", global.format))
        return ExitCodes.NotFound
      case Some(run) =>
        val artifacts = cmd.artifactId match {
          case Some(id) => ArtifactRecordRepo.getById(id).toList
          case None =>
            val byType = cmd.artifactType.flatMap(t => ArtifactType.values.find(_.toString.equalsIgnoreCase(t)))
            (byType, cmd.attempt) match {
              case (Some(t), Some(n)) => ArtifactRecordRepo.listByRunAndType(cmd.runId, t).filter(_.attemptNumber.contains(n))
              case (Some(t), None)    => ArtifactRecordRepo.listByRunAndType(cmd.runId, t)
              case (None, Some(n))    => ArtifactRecordRepo.listByRunAndAttempt(cmd.runId, n)
              case (None, None)       => ArtifactRecordRepo.listByRunId(cmd.runId)
            }
        }

        if (artifacts.isEmpty) {
          System.err.println(OutputFormatter.formatError("No matching artifacts found", global.format))
          return ExitCodes.NotFound
        }

        if (cmd.printPath) {
          artifacts.foreach { a =>
            val fullPath = run.artifactRootPath.resolve(a.relativePath)
            global.format match {
              case OutputFormat.Json =>
                System.out.println(io.circe.Json.obj(
                  "artifactId" -> io.circe.Json.fromString(a.artifactId),
                  "path" -> io.circe.Json.fromString(fullPath.toString),
                ).noSpaces)
              case OutputFormat.Human =>
                System.out.println(fullPath.toString)
            }
          }
        } else {
          System.out.println(OutputFormatter.formatArtifactList(artifacts, global.format))
        }

        ExitCodes.Success
    }
  }
}
