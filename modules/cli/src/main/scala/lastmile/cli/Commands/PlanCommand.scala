package lastmile.cli.Commands

import java.sql.Connection

import lastmile.cli._
import lastmile.cli.CommandParsers._
import lastmile.model._
import lastmile.persistence._

// Phase 7: `lastmile plan` command — Spec §14.1
// Plan-only mode: inspect repo, compile requirements, plan environment, then stop.
object PlanCommand {

  def execute(cmd: PlanCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    // In plan-only mode, we create a run in PlanOnly mode and return the plan.
    // For the CLI layer, we output the task description and repo info.
    val output = global.format match {
      case OutputFormat.Json =>
        io.circe.Json.obj(
          "command" -> io.circe.Json.fromString("plan"),
          "task" -> io.circe.Json.fromString(cmd.task),
          "repo" -> io.circe.Json.fromString(global.repo.toString),
          "changedFiles" -> cmd.changedFiles.map(fs => io.circe.Json.arr(fs.map(io.circe.Json.fromString): _*)).getOrElse(io.circe.Json.Null),
          "gitRef" -> cmd.gitRef.map(io.circe.Json.fromString).getOrElse(io.circe.Json.Null),
        ).noSpaces
      case OutputFormat.Human =>
        val lines = List(
          s"Plan for task: ${cmd.task}",
          s"Repository: ${global.repo}",
        ) ++ cmd.changedFiles.map(fs => s"Changed files: ${fs.mkString(", ")}").toList ++
          cmd.gitRef.map(r => s"Git ref: $r").toList
        lines.mkString("\n")
    }

    System.out.println(output)
    ExitCodes.Success
  }
}
