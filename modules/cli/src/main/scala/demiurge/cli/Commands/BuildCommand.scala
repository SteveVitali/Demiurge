package demiurge.cli.Commands

import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.Instant
import java.util.UUID

import demiurge.cli._
import demiurge.cli.CommandParsers._
import demiurge.model._
import demiurge.persistence._
import demiurge.orchestrator._

// Phase C: `demiurge build` command — autonomous feature generation + verify/repair loop.
// Syntactic sugar for `demiurge run --mode build`, with --branch/--open-pr/--yes support.
object BuildCommand {

  def execute(cmd: BuildCmd, global: GlobalOpts, conn: Connection): Int = {
    // Convert BuildCmd to RunCmd with mode=build
    val runCmd = RunCmd(
      task = cmd.task,
      maxAttempts = cmd.maxAttempts,
      runTimeout = cmd.runTimeout,
      attemptTimeout = cmd.attemptTimeout,
      maxPatchLines = cmd.maxPatchLines,
      changedFiles = cmd.changedFiles,
      gitRef = cmd.gitRef,
      mode = Some("Build"),
      runId = cmd.runId,
      replayInference = cmd.replayInference,
      headless = cmd.headless,
      branch = cmd.branch,
      openPr = cmd.openPr,
      yes = cmd.yes,
    )

    // Delegate to RunCommand
    RunCommand.execute(runCmd, global, conn)
  }
}
