package lastmile.cli.Commands

import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.Instant

import lastmile.cli._
import lastmile.cli.CommandParsers._
import lastmile.model._
import lastmile.persistence._

// Phase 7: `lastmile clean` command — Spec §14.1
object CleanCommand {

  private val terminalStatuses: Set[RunStatus] = Set(
    RunStatus.Succeeded, RunStatus.Exhausted, RunStatus.Cancelled, RunStatus.Interrupted
  )

  def execute(cmd: CleanCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    val allRuns = if (cmd.all) {
      TaskRunRepo.listAll()
    } else if (cmd.runId.isDefined) {
      TaskRunRepo.getById(cmd.runId.get).toList
    } else if (cmd.maxAge.isDefined) {
      val ageMs = parseAge(cmd.maxAge.get)
      if (ageMs <= 0) {
        System.err.println(OutputFormatter.formatError(s"Invalid max-age: ${cmd.maxAge.get}", global.format))
        return ExitCodes.InputError
      }
      val cutoff = Instant.now().minusMillis(ageMs)
      TaskRunRepo.listAll().filter(r => r.createdAt.isBefore(cutoff))
    } else {
      System.err.println(OutputFormatter.formatError("clean requires --run-id, --all, or --max-age", global.format))
      return ExitCodes.InputError
    }

    // Never clean active runs
    val cleanable = allRuns.filter(r => terminalStatuses.contains(r.status))
    val skipped = allRuns.filterNot(r => terminalStatuses.contains(r.status))

    val targets = scala.collection.mutable.ListBuffer[String]()

    cleanable.foreach { run =>
      targets += s"run: ${run.runId} (${run.status}, created: ${run.createdAt})"

      if (!cmd.dryRun) {
        if (cmd.includeArtifacts) {
          deleteDirectoryRecursive(run.artifactRootPath)
        }
        if (cmd.includeDb) {
          // Delete all FK-dependent child tables before the parent task_runs row
          VerdictRepo.deleteByRunId(run.runId)
          EventRepo.deleteByRunId(run.runId)
          ArtifactRecordRepo.deleteByRunId(run.runId)
          FailurePacketRepo.deleteByRunId(run.runId)
          PatchRepo.deleteByRunId(run.runId)
          RequirementGraphRepo.deleteByRunId(run.runId)
          RuntimePlanRepo.deleteByRunId(run.runId)
          RuntimeSnapshotRepo.deleteByRunId(run.runId)
          RepoInspectionReportRepo.deleteByRunId(run.runId)
          deleteByRunIdRaw("policy_snapshots", run.runId)
          deleteByRunIdRaw("usage_records", run.runId)
          deleteByRunIdRaw("inference_cache", run.runId)
          deleteByRunIdRaw("rerun_plans", run.runId)
          AttemptRepo.deleteByRunId(run.runId)
          TaskRunRepo.deleteById(run.runId)
        }
      }
    }

    if (skipped.nonEmpty && !global.quiet) {
      skipped.foreach { run =>
        System.err.println(s"Skipped active run: ${run.runId} (${run.status})")
      }
    }

    System.out.println(OutputFormatter.formatCleanTargets(targets.toList, cmd.dryRun, global.format))
    ExitCodes.Success
  }

  private def parseAge(s: String): Long = {
    val trimmed = s.trim.toLowerCase
    try {
      if (trimmed.endsWith("d")) trimmed.dropRight(1).toLong * 86400000L
      else if (trimmed.endsWith("h")) trimmed.dropRight(1).toLong * 3600000L
      else if (trimmed.endsWith("m")) trimmed.dropRight(1).toLong * 60000L
      else trimmed.toLong * 86400000L // default: days
    } catch {
      case _: NumberFormatException => -1L
    }
  }

  private def deleteByRunIdRaw(tableName: String, runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(s"DELETE FROM $tableName WHERE run_id = ?")
    try { ps.setString(1, runId); ps.executeUpdate() }
    finally { ps.close() }
  }

  private def deleteDirectoryRecursive(path: Path): Unit = {
    if (Files.exists(path)) {
      if (Files.isDirectory(path)) {
        val stream = Files.list(path)
        try {
          stream.forEach(child => deleteDirectoryRecursive(child))
        } finally {
          stream.close()
        }
      }
      Files.deleteIfExists(path)
    }
  }
}
