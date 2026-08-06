package demiurge.cli

import java.nio.file.Files
import java.sql.Connection

import demiurge.cli.CommandParsers._
import demiurge.cli.Commands._
import demiurge.license.{LicenseManager, LicenseStatus}
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

      // Commands that don't need a DB connection
      case Right(ParseResult(global, cmd: LoginCmd)) =>
        LoginCommand.execute(cmd, global)
      case Right(ParseResult(global, LogoutCmd)) =>
        LogoutCommand.execute(global)
      case Right(ParseResult(global, cmd: ConfigCmd)) =>
        ConfigCommand.execute(cmd, global)

      case Right(ParseResult(global, cmd)) =>
        // License gate for run-lifecycle commands
        cmd match {
          case _: RunCmd | _: BuildCmd | _: ResumeCmd =>
            LicenseManager.validate() match {
              case LicenseStatus.Valid(planTier, uses, maxUses, _, _) =>
                // Spec 05 §6.1: Pre-run usage summary
                val planLabel = if (planTier.nonEmpty) planTier.capitalize else "Active"
                System.err.println(s"[demiurge] License valid ($planLabel plan). Usage: $uses/$maxUses runs this period.")
                // Spec 05 §6.3: Approaching limit warning (≥80%)
                if (maxUses > 0) {
                  val pct = (uses.toDouble / maxUses * 100).toInt
                  if (pct >= 80) {
                    System.err.println(s"[demiurge] Warning: $uses/$maxUses runs used this period ($pct%). Consider upgrading.")
                  }
                }
              case LicenseStatus.NoCredentials =>
                System.err.println("Error: Not logged in. Run `demiurge login` to authenticate.")
                return ExitCodes.InputError
              case LicenseStatus.Expired(expiry) =>
                System.err.println(s"Error: License expired on $expiry. Renew at https://demiurge.dev/billing")
                return ExitCodes.InputError
              case LicenseStatus.Suspended(_) =>
                System.err.println("Error: License suspended. Contact support or resubscribe at https://demiurge.dev/billing")
                return ExitCodes.InputError
              case LicenseStatus.OverLimit(uses, maxUses) =>
                // Spec 05 §6.2: Detailed limit reached error with upgrade prompts
                System.err.println(s"Error: Run limit reached ($uses/$maxUses runs used this period).\n")
                System.err.println("To continue:")
                System.err.println("  \u2022 Upgrade your plan: https://demiurge.dev/pricing")
                System.err.println("  \u2022 Wait for your period to reset\n")
                System.err.println("Run 'demiurge status' to check your current usage.")
                return ExitCodes.InputError
              case LicenseStatus.TooManyMachines =>
                System.err.println("Error: Machine limit reached. Deactivate a machine or upgrade your plan.")
                return ExitCodes.InputError
              case LicenseStatus.MachineNotActivated =>
                System.err.println("Error: Machine not activated. Run `demiurge login` again.")
                return ExitCodes.InputError
              case LicenseStatus.NotFound =>
                System.err.println("Error: License not found. Run `demiurge login` to authenticate.")
                return ExitCodes.InputError
              case LicenseStatus.NetworkError(msg) =>
                System.err.println(s"Error: Cannot validate license \u2014 $msg")
                return ExitCodes.Errored
            }
          case _ => // No gate needed
        }

        // serve daemon uses ~/.demiurge/ as default data dir (not CWD, which may be
        // Bazel runfiles or another non-writable location when launched as a sidecar)
        val dbDir = cmd match {
          case _: ServeCmd =>
            java.nio.file.Paths.get(System.getProperty("user.home")).resolve(".demiurge")
          case _ =>
            global.repo.resolve(".demiurge")
        }
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
    case c: BuildCmd          => BuildCommand.execute(c, global, conn)
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
    case c: ServeCmd          => ServeCommand.execute(c, global, conn)
    case HelpCmd              => printHelp(); ExitCodes.Success
    // LoginCmd, LogoutCmd, ConfigCmd are handled before dispatch() — should never reach here
    case _ => System.err.println("Error: Unrecognized command in dispatch"); ExitCodes.InputError
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
        |  login               Authenticate with Demiurge (opens browser or use --license-key)
        |  logout              Clear stored credentials
        |  config              Manage configuration (set/get/list API keys, preferences)
        |  run                 Execute a full verification run
        |  build               Build a new feature (generate + verify + repair)
        |  plan                Plan without executing
        |  resume              Resume an interrupted run
        |  status              Show run status
        |  inspect-run         Detailed run inspection
        |  open-artifact       Access run artifacts
        |  explain-failure     Explain verification failures
        |  cancel              Cancel an active run
        |  clean               Clean up old runs and artifacts
        |  doctor              Check system prerequisites
        |  init                Generate demiurge.yaml from repo analysis
        |  serve               Start persistent backend server (desktop sidecar)
        |
        |Run 'demiurge <command> --help' for command-specific help.
      """.stripMargin)
  }
}
