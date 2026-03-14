package demiurge.cli

import java.nio.file.Files
import java.sql.Connection

import demiurge.cli.CommandParsers._
import demiurge.cli.Commands._
import demiurge.persistence.{Database, Migrator}

// Phase 7: CLI application entry point — Spec §14.1
// Routes parsed commands to their handlers, manages DB connection lifecycle.
object CliApp {

  def run(args: Array[String]): Int = {
    CommandParsers.parse(args) match {
      case Left(error) =>
        System.err.println(s"Error: $error")
        ExitCodes.InputError

      case Right(ParseResult(global, HelpCmd)) =>
        printHelp()
        ExitCodes.Success

      case Right(ParseResult(global, cmd)) =>
        val dbDir = global.repo.resolve(".demiurge")
        Files.createDirectories(dbDir)
        val dbPath = dbDir.resolve("demiurge.db")
        val conn = Database.open(dbPath)
        try {
          Migrator.migrate(conn)
          dispatch(cmd, global, conn)
        } catch {
          case e: Exception =>
            System.err.println(OutputFormatter.formatError(e.getMessage, global.format))
            ExitCodes.Errored
        } finally {
          conn.close()
        }
    }
  }

  private def dispatch(cmd: ParsedCommand, global: GlobalOpts, conn: Connection): Int = cmd match {
    case c: RunCmd            => RunCommand.execute(c, global, conn)
    case c: PlanCmd           => PlanCommand.execute(c, global, conn)
    case c: ResumeCmd         => ResumeCommand.execute(c, global, conn)
    case c: StatusCmd         => StatusCommand.execute(c, global, conn)
    case c: InspectRunCmd     => InspectRunCommand.execute(c, global, conn)
    case c: OpenArtifactCmd   => OpenArtifactCommand.execute(c, global, conn)
    case c: ExplainFailureCmd => ExplainFailureCommand.execute(c, global, conn)
    case c: CancelCmd         => CancelCommand.execute(c, global, conn)
    case c: CleanCmd          => CleanCommand.execute(c, global, conn)
    case DoctorCmd()          => DoctorCommand.execute(global, conn)
    case c: InitManifestCmd   => InitManifestCommand.execute(c, global, conn)
    case HelpCmd              => printHelp(); ExitCodes.Success
  }

  private def printHelp(): Unit = {
    System.out.println(
      """demiurge — Last-Mile Web Development Automation Platform
        |
        |Usage: demiurge [global-flags] <command> [command-flags]
        |
        |Global Flags:
        |  --repo <path>       Repository path (default: current directory)
        |  --format <mode>     Output format: human (default) or json
        |  --verbose           Verbose output
        |  --quiet             Suppress non-essential output
        |  --config <path>     Configuration file path
        |
        |Commands:
        |  run                 Execute a full verification run
        |  plan                Plan without executing
        |  resume              Resume an interrupted run
        |  status              Show run status
        |  inspect-run         Detailed run inspection
        |  open-artifact       Access run artifacts
        |  explain-failure     Explain verification failures
        |  cancel              Cancel an active run
        |  clean               Clean up old runs and artifacts
        |  doctor              Check system prerequisites
        |  init-manifest       Generate a demiurge.yaml manifest
        |
        |Run 'demiurge <command> --help' for command-specific help.
      """.stripMargin)
  }
}
