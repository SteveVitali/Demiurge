package demiurge.cli

import java.nio.file.{Path, Paths}

// Phase 7: CLI command parsing per canonical spec §14.1
// Lightweight hand-rolled arg parser — no external library dependency.

object CommandParsers {

  // --- Global options ---
  case class GlobalOpts(
    repo: Path          = Paths.get(".").toAbsolutePath.normalize(),
    format: OutputFormat = OutputFormat.Human,
    verbose: Boolean     = false,
    quiet: Boolean       = false,
    config: Option[Path] = None,
  )

  sealed trait OutputFormat
  object OutputFormat {
    case object Human extends OutputFormat
    case object Json  extends OutputFormat
    def parse(s: String): Either[String, OutputFormat] = s.toLowerCase match {
      case "human" => Right(Human)
      case "json"  => Right(Json)
      case other   => Left(s"Unknown format: $other (expected: human, json)")
    }
  }

  // --- Parsed command ADT ---
  sealed trait ParsedCommand
  case class RunCmd(
    task: String,
    maxAttempts: Option[Int]       = None,
    runTimeout: Option[Long]       = None,
    attemptTimeout: Option[Long]   = None,
    verifierTimeout: Option[Long]  = None,
    repairTimeout: Option[Long]    = None,
    inferenceTimeout: Option[Long] = None,
    maxPatchLines: Option[Int]     = None,
    maxArtifactDisk: Option[Long]  = None,
    maxRepairTokens: Option[Long]  = None,
    maxExploratorySteps: Option[Int] = None,
    changedFiles: Option[List[String]] = None,
    gitRef: Option[String]         = None,
    mode: Option[String]           = None,
    runId: Option[String]          = None,
    replayInference: Boolean       = false,
    headless: Boolean              = true,
  ) extends ParsedCommand

  case class PlanCmd(
    task: String,
    changedFiles: Option[List[String]] = None,
    gitRef: Option[String]             = None,
  ) extends ParsedCommand

  case class ResumeCmd(runId: String) extends ParsedCommand

  case class StatusCmd(runId: Option[String] = None) extends ParsedCommand

  case class InspectRunCmd(
    runId: String,
    attempt: Option[Int]   = None,
    showVerdicts: Boolean  = false,
    showArtifacts: Boolean = false,
  ) extends ParsedCommand

  case class OpenArtifactCmd(
    runId: String,
    artifactId: Option[String] = None,
    artifactType: Option[String] = None,
    attempt: Option[Int]       = None,
    printPath: Boolean         = false,
  ) extends ParsedCommand

  case class ExplainFailureCmd(
    runId: String,
    attempt: Option[Int] = None,
  ) extends ParsedCommand

  case class CancelCmd(runId: Option[String] = None) extends ParsedCommand

  case class CleanCmd(
    runId: Option[String]  = None,
    all: Boolean           = false,
    maxAge: Option[String] = None,
    includeArtifacts: Boolean = false,
    includeDb: Boolean     = false,
    dryRun: Boolean        = false,
  ) extends ParsedCommand

  case class DoctorCmd() extends ParsedCommand

  case class InitManifestCmd(
    output: String  = "demiurge.yaml",
    force: Boolean  = false,
  ) extends ParsedCommand

  case object HelpCmd extends ParsedCommand

  case class ParseResult(global: GlobalOpts, command: ParsedCommand)

  // --- Parsing ---

  def parse(args: Array[String]): Either[String, ParseResult] = {
    val argList = args.toList
    parseGlobalAndCommand(argList, GlobalOpts())
  }

  private def parseGlobalAndCommand(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    args match {
      case Nil => Left("No command specified. Available commands: run, plan, resume, status, inspect-run, open-artifact, explain-failure, cancel, clean, doctor, init-manifest")
      case "--repo" :: value :: rest => parseGlobalAndCommand(rest, global.copy(repo = Paths.get(value).toAbsolutePath.normalize()))
      case "--format" :: value :: rest =>
        OutputFormat.parse(value) match {
          case Right(f) => parseGlobalAndCommand(rest, global.copy(format = f))
          case Left(e)  => Left(e)
        }
      case "--verbose" :: rest => parseGlobalAndCommand(rest, global.copy(verbose = true))
      case "--quiet" :: rest   => parseGlobalAndCommand(rest, global.copy(quiet = true))
      case "--config" :: value :: rest => parseGlobalAndCommand(rest, global.copy(config = Some(Paths.get(value))))
      case "--help" :: _ | "-h" :: _ => Right(ParseResult(global, HelpCmd))
      case cmd :: rest => parseCommand(cmd, rest, global)
    }
  }

  private def parseCommand(cmd: String, args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    cmd match {
      case "run"              => parseRunCmd(args, global)
      case "plan"             => parsePlanCmd(args, global)
      case "resume"           => parseResumeCmd(args, global)
      case "status"           => parseStatusCmd(args, global)
      case "inspect-run"      => parseInspectRunCmd(args, global)
      case "open-artifact"    => parseOpenArtifactCmd(args, global)
      case "explain-failure"  => parseExplainFailureCmd(args, global)
      case "cancel"           => parseCancelCmd(args, global)
      case "clean"            => parseCleanCmd(args, global)
      case "doctor"           => Right(ParseResult(global, DoctorCmd()))
      case "init-manifest"    => parseInitManifestCmd(args, global)
      case other              => Left(s"Unknown command: $other")
    }
  }

  private def parseRunCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var task: Option[String] = None
    var cmd = RunCmd(task = "")
    var remaining = args

    while (remaining.nonEmpty) {
      remaining match {
        case "--task" :: v :: rest          => task = Some(v); remaining = rest
        case "--max-attempts" :: v :: rest  => parseInt(v, "--max-attempts").map(n => cmd = cmd.copy(maxAttempts = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--run-timeout" :: v :: rest   => parseDuration(v, "--run-timeout").map(n => cmd = cmd.copy(runTimeout = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--attempt-timeout" :: v :: rest => parseDuration(v, "--attempt-timeout").map(n => cmd = cmd.copy(attemptTimeout = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--verifier-timeout" :: v :: rest => parseDuration(v, "--verifier-timeout").map(n => cmd = cmd.copy(verifierTimeout = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--repair-timeout" :: v :: rest => parseDuration(v, "--repair-timeout").map(n => cmd = cmd.copy(repairTimeout = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--inference-timeout" :: v :: rest => parseDuration(v, "--inference-timeout").map(n => cmd = cmd.copy(inferenceTimeout = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--max-patch-lines" :: v :: rest => parseInt(v, "--max-patch-lines").map(n => cmd = cmd.copy(maxPatchLines = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--max-artifact-disk" :: v :: rest => parseSize(v, "--max-artifact-disk").map(n => cmd = cmd.copy(maxArtifactDisk = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--max-repair-tokens" :: v :: rest => parseLong(v, "--max-repair-tokens").map(n => cmd = cmd.copy(maxRepairTokens = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--max-exploratory-steps" :: v :: rest => parseInt(v, "--max-exploratory-steps").map(n => cmd = cmd.copy(maxExploratorySteps = Some(n))) match { case Left(e) => return Left(e); case _ => }; remaining = rest
        case "--changed-files" :: v :: rest => cmd = cmd.copy(changedFiles = Some(v.split(",").toList)); remaining = rest
        case "--git-ref" :: v :: rest      => cmd = cmd.copy(gitRef = Some(v)); remaining = rest
        case "--mode" :: v :: rest         => cmd = cmd.copy(mode = Some(v)); remaining = rest
        case "--run-id" :: v :: rest       => cmd = cmd.copy(runId = Some(v)); remaining = rest
        case "--replay-inference" :: rest  => cmd = cmd.copy(replayInference = true); remaining = rest
        case "--headless" :: rest          => cmd = cmd.copy(headless = true); remaining = rest
        case "--no-headless" :: rest       => cmd = cmd.copy(headless = false); remaining = rest
        case unknown :: _                  => return Left(s"Unknown flag for run: $unknown")
      }
    }

    task match {
      case Some(t) => Right(ParseResult(global, cmd.copy(task = t)))
      case None    => Left("run requires --task")
    }
  }

  private def parsePlanCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var task: Option[String] = None
    var changedFiles: Option[List[String]] = None
    var gitRef: Option[String] = None
    var remaining = args

    while (remaining.nonEmpty) {
      remaining match {
        case "--task" :: v :: rest          => task = Some(v); remaining = rest
        case "--changed-files" :: v :: rest => changedFiles = Some(v.split(",").toList); remaining = rest
        case "--git-ref" :: v :: rest      => gitRef = Some(v); remaining = rest
        case unknown :: _                  => return Left(s"Unknown flag for plan: $unknown")
      }
    }

    task match {
      case Some(t) => Right(ParseResult(global, PlanCmd(t, changedFiles, gitRef)))
      case None    => Left("plan requires --task")
    }
  }

  private def parseResumeCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var runId: Option[String] = None
    var remaining = args
    while (remaining.nonEmpty) {
      remaining match {
        case "--run-id" :: v :: rest => runId = Some(v); remaining = rest
        case unknown :: _           => return Left(s"Unknown flag for resume: $unknown")
      }
    }
    runId match {
      case Some(id) => Right(ParseResult(global, ResumeCmd(id)))
      case None     => Left("resume requires --run-id")
    }
  }

  private def parseStatusCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var runId: Option[String] = None
    var remaining = args
    while (remaining.nonEmpty) {
      remaining match {
        case "--run-id" :: v :: rest => runId = Some(v); remaining = rest
        case unknown :: _           => return Left(s"Unknown flag for status: $unknown")
      }
    }
    Right(ParseResult(global, StatusCmd(runId)))
  }

  private def parseInspectRunCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var runId: Option[String] = None
    var attempt: Option[Int] = None
    var showVerdicts = false
    var showArtifacts = false
    var remaining = args
    while (remaining.nonEmpty) {
      remaining match {
        case "--run-id" :: v :: rest     => runId = Some(v); remaining = rest
        case "--attempt" :: v :: rest    => parseInt(v, "--attempt") match { case Right(n) => attempt = Some(n); case Left(e) => return Left(e) }; remaining = rest
        case "--show-verdicts" :: rest   => showVerdicts = true; remaining = rest
        case "--show-artifacts" :: rest  => showArtifacts = true; remaining = rest
        case unknown :: _               => return Left(s"Unknown flag for inspect-run: $unknown")
      }
    }
    runId match {
      case Some(id) => Right(ParseResult(global, InspectRunCmd(id, attempt, showVerdicts, showArtifacts)))
      case None     => Left("inspect-run requires --run-id")
    }
  }

  private def parseOpenArtifactCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var runId: Option[String] = None
    var artifactId: Option[String] = None
    var artifactType: Option[String] = None
    var attempt: Option[Int] = None
    var printPath = false
    var remaining = args
    while (remaining.nonEmpty) {
      remaining match {
        case "--run-id" :: v :: rest      => runId = Some(v); remaining = rest
        case "--artifact-id" :: v :: rest => artifactId = Some(v); remaining = rest
        case "--type" :: v :: rest        => artifactType = Some(v); remaining = rest
        case "--attempt" :: v :: rest     => parseInt(v, "--attempt") match { case Right(n) => attempt = Some(n); case Left(e) => return Left(e) }; remaining = rest
        case "--print-path" :: rest       => printPath = true; remaining = rest
        case unknown :: _                 => return Left(s"Unknown flag for open-artifact: $unknown")
      }
    }
    runId match {
      case Some(id) => Right(ParseResult(global, OpenArtifactCmd(id, artifactId, artifactType, attempt, printPath)))
      case None     => Left("open-artifact requires --run-id")
    }
  }

  private def parseExplainFailureCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var runId: Option[String] = None
    var attempt: Option[Int] = None
    var remaining = args
    while (remaining.nonEmpty) {
      remaining match {
        case "--run-id" :: v :: rest  => runId = Some(v); remaining = rest
        case "--attempt" :: v :: rest => parseInt(v, "--attempt") match { case Right(n) => attempt = Some(n); case Left(e) => return Left(e) }; remaining = rest
        case unknown :: _            => return Left(s"Unknown flag for explain-failure: $unknown")
      }
    }
    runId match {
      case Some(id) => Right(ParseResult(global, ExplainFailureCmd(id, attempt)))
      case None     => Left("explain-failure requires --run-id")
    }
  }

  private def parseCancelCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var runId: Option[String] = None
    var remaining = args
    while (remaining.nonEmpty) {
      remaining match {
        case "--run-id" :: v :: rest => runId = Some(v); remaining = rest
        case unknown :: _           => return Left(s"Unknown flag for cancel: $unknown")
      }
    }
    Right(ParseResult(global, CancelCmd(runId)))
  }

  private def parseCleanCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var cmd = CleanCmd()
    var remaining = args
    while (remaining.nonEmpty) {
      remaining match {
        case "--run-id" :: v :: rest       => cmd = cmd.copy(runId = Some(v)); remaining = rest
        case "--all" :: rest               => cmd = cmd.copy(all = true); remaining = rest
        case "--max-age" :: v :: rest      => cmd = cmd.copy(maxAge = Some(v)); remaining = rest
        case "--include-artifacts" :: rest => cmd = cmd.copy(includeArtifacts = true); remaining = rest
        case "--include-db" :: rest        => cmd = cmd.copy(includeDb = true); remaining = rest
        case "--dry-run" :: rest           => cmd = cmd.copy(dryRun = true); remaining = rest
        case unknown :: _                  => return Left(s"Unknown flag for clean: $unknown")
      }
    }
    if (!cmd.all && cmd.runId.isEmpty && cmd.maxAge.isEmpty) {
      return Left("clean requires --run-id, --all, or --max-age")
    }
    Right(ParseResult(global, cmd))
  }

  private def parseInitManifestCmd(args: List[String], global: GlobalOpts): Either[String, ParseResult] = {
    var cmd = InitManifestCmd()
    var remaining = args
    while (remaining.nonEmpty) {
      remaining match {
        case "--output" :: v :: rest => cmd = cmd.copy(output = v); remaining = rest
        case "--force" :: rest       => cmd = cmd.copy(force = true); remaining = rest
        case unknown :: _            => return Left(s"Unknown flag for init-manifest: $unknown")
      }
    }
    Right(ParseResult(global, cmd))
  }

  // --- Utility parsers ---

  def parseInt(s: String, flag: String): Either[String, Int] =
    try Right(s.toInt) catch { case _: NumberFormatException => Left(s"Invalid integer for $flag: $s") }

  def parseLong(s: String, flag: String): Either[String, Long] =
    try Right(s.toLong) catch { case _: NumberFormatException => Left(s"Invalid number for $flag: $s") }

  /** Parse duration string: plain seconds or suffixed (s, m, h). Returns milliseconds. */
  def parseDuration(s: String, flag: String): Either[String, Long] = {
    val trimmed = s.trim.toLowerCase
    try {
      if (trimmed.endsWith("ms")) Right(trimmed.dropRight(2).toLong)
      else if (trimmed.endsWith("s")) Right(trimmed.dropRight(1).toLong * 1000L)
      else if (trimmed.endsWith("m")) Right(trimmed.dropRight(1).toLong * 60000L)
      else if (trimmed.endsWith("h")) Right(trimmed.dropRight(1).toLong * 3600000L)
      else Right(trimmed.toLong * 1000L) // plain number = seconds
    } catch {
      case _: NumberFormatException => Left(s"Invalid duration for $flag: $s (expected: number with optional s/m/h suffix)")
    }
  }

  /** Parse size string: plain bytes or suffixed (KB, MB, GB). Returns bytes. */
  def parseSize(s: String, flag: String): Either[String, Long] = {
    val trimmed = s.trim.toUpperCase
    try {
      if (trimmed.endsWith("GB")) Right(trimmed.dropRight(2).trim.toLong * 1073741824L)
      else if (trimmed.endsWith("MB")) Right(trimmed.dropRight(2).trim.toLong * 1048576L)
      else if (trimmed.endsWith("KB")) Right(trimmed.dropRight(2).trim.toLong * 1024L)
      else Right(trimmed.toLong)
    } catch {
      case _: NumberFormatException => Left(s"Invalid size for $flag: $s (expected: number with optional KB/MB/GB suffix)")
    }
  }
}
