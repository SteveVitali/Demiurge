package demiurge.cli

import io.circe._
import io.circe.syntax._
import demiurge.model._
import demiurge.model.JsonCodecs._
import demiurge.cli.CommandParsers.OutputFormat

// Phase 7: Output formatting for human and JSON modes per canonical spec §14.2
object OutputFormatter {

  def formatRun(run: TaskRun, format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      run.asJson.noSpaces
    case OutputFormat.Human =>
      val lines = List(
        s"Run: ${run.runId}",
        s"Status: ${run.status}",
        s"Task: ${run.taskText}",
        s"Mode: ${run.runMode}",
        s"Repo: ${run.repoPath}",
        s"Created: ${run.createdAt}",
      ) ++ run.startedAt.map(t => s"Started: $t").toList ++
        run.endedAt.map(t => s"Ended: $t").toList ++
        run.finalVerdict.map(v => s"Final Verdict: $v").toList ++
        run.finalSummary.map(s => s"Summary: $s").toList ++
        List(s"Attempts: ${run.attemptCount}/${run.maxAttempts}")
      lines.mkString("\n")
  }

  def formatRunList(runs: List[TaskRun], format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      Json.arr(runs.map(_.asJson): _*).noSpaces
    case OutputFormat.Human =>
      if (runs.isEmpty) "No runs found."
      else runs.map(r => s"  ${r.runId}  ${r.status}  ${r.taskText.take(60)}  ${r.createdAt}").mkString("Recent runs:\n", "\n", "")
  }

  def formatAttemptList(attempts: List[Attempt], format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      Json.arr(attempts.map(_.asJson): _*).noSpaces
    case OutputFormat.Human =>
      if (attempts.isEmpty) "No attempts found."
      else attempts.map { a =>
        val vs = a.verdictSummary.map(s => s" (${s.passCount}/${s.totalRequired} pass)").getOrElse("")
        s"  Attempt ${a.attemptNumber}: ${a.status}$vs"
      }.mkString("Attempts:\n", "\n", "")
  }

  def formatVerdictList(verdicts: List[RequirementVerdict], format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      Json.arr(verdicts.map(_.asJson): _*).noSpaces
    case OutputFormat.Human =>
      if (verdicts.isEmpty) "No verdicts found."
      else verdicts.map { v =>
        val fc = v.failureClass.map(c => s" [$c]").getOrElse("")
        s"  ${v.requirementId}: ${v.status}${fc} (${v.executionDurationMs}ms)"
      }.mkString("Verdicts:\n", "\n", "")
  }

  def formatArtifactList(artifacts: List[ArtifactRecord], format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      Json.arr(artifacts.map(_.asJson): _*).noSpaces
    case OutputFormat.Human =>
      if (artifacts.isEmpty) "No artifacts found."
      else artifacts.map { a =>
        val attempt = a.attemptNumber.map(n => s" attempt=$n").getOrElse("")
        s"  ${a.artifactId}  ${a.artifactType}$attempt  ${a.sizeBytes} bytes  ${a.relativePath}"
      }.mkString("Artifacts:\n", "\n", "")
  }

  def formatEvent(event: SystemEvent, format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      event.asJson.noSpaces
    case OutputFormat.Human =>
      s"[${event.timestamp}] [${event.severity}] ${event.humanMessage}"
  }

  def formatEventList(events: List[SystemEvent], format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      events.map(_.asJson.noSpaces).mkString("\n") // NDJSON
    case OutputFormat.Human =>
      if (events.isEmpty) "No events found."
      else events.map(e => s"[${e.timestamp}] [${e.severity}] ${e.humanMessage}").mkString("\n")
  }

  def formatSuccess(message: String, format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      Json.obj("ok" -> Json.True, "message" -> Json.fromString(message)).noSpaces
    case OutputFormat.Human =>
      message
  }

  def formatError(message: String, format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      Json.obj("ok" -> Json.False, "error" -> Json.fromString(message)).noSpaces
    case OutputFormat.Human =>
      s"Error: $message"
  }

  def formatDoctorCheck(name: String, status: String, detail: String, format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      Json.obj("check" -> Json.fromString(name), "status" -> Json.fromString(status), "detail" -> Json.fromString(detail)).noSpaces
    case OutputFormat.Human =>
      val icon = status match {
        case "pass" => "[PASS]"
        case "fail" => "[FAIL]"
        case "warn" => "[WARN]"
        case _      => "[????]"
      }
      s"$icon $name: $detail"
  }

  def formatDoctorResults(checks: List[(String, String, String)], format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      Json.arr(checks.map { case (name, status, detail) =>
        Json.obj("check" -> Json.fromString(name), "status" -> Json.fromString(status), "detail" -> Json.fromString(detail))
      }: _*).noSpaces
    case OutputFormat.Human =>
      checks.map { case (name, status, detail) =>
        formatDoctorCheck(name, status, detail, OutputFormat.Human)
      }.mkString("\n")
  }

  def formatCleanTargets(targets: List[String], dryRun: Boolean, format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      Json.obj("dryRun" -> Json.fromBoolean(dryRun), "targets" -> Json.arr(targets.map(Json.fromString): _*)).noSpaces
    case OutputFormat.Human =>
      val prefix = if (dryRun) "Would clean:" else "Cleaned:"
      if (targets.isEmpty) "Nothing to clean."
      else targets.mkString(s"$prefix\n  ", "\n  ", "")
  }

  def formatFailureExplanation(verdicts: List[RequirementVerdict], format: OutputFormat): String = format match {
    case OutputFormat.Json =>
      val failures = verdicts.filter(_.status != VerdictStatus.Pass)
      Json.obj(
        "failureCount" -> Json.fromInt(failures.length),
        "failures" -> Json.arr(failures.map { v =>
          Json.obj(
            "requirementId" -> Json.fromString(v.requirementId),
            "status" -> Json.fromString(v.status.toString),
            "failureClass" -> v.failureClass.map(c => Json.fromString(c.toString)).getOrElse(Json.Null),
            "failureMessage" -> v.failureMessage.map(Json.fromString).getOrElse(Json.Null),
            "observations" -> Json.arr(v.observations.map(_.asJson): _*),
          )
        }: _*),
      ).noSpaces
    case OutputFormat.Human =>
      val failures = verdicts.filter(_.status != VerdictStatus.Pass)
      if (failures.isEmpty) "No failures found."
      else {
        failures.map { v =>
          val fc = v.failureClass.map(c => s"\n  Failure Class: $c").getOrElse("")
          val fm = v.failureMessage.map(m => s"\n  Message: $m").getOrElse("")
          val obs = if (v.observations.isEmpty) "" else v.observations.map(o => s"    - [${o.observationType}] ${o.message}").mkString("\n  Observations:\n", "\n", "")
          s"Requirement: ${v.requirementId} — ${v.status}$fc$fm$obs"
        }.mkString("Failure Explanation:\n", "\n\n", "")
      }
  }
}
