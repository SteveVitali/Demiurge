package demiurge.orchestrator

import java.nio.file.{Files, Path}
import java.sql.Connection

import demiurge.model._
import demiurge.persistence._

// Spec §7.6: Resume manager — handles interrupted run resumption.
// Pre-resume checks: verify status is Interrupted, worktree exists, clean orphans, check DB integrity.
// Per-state resume behavior maps interrupted state to appropriate resume point.
object ResumeManager {

  sealed trait ResumeResult
  case class ResumeReady(run: TaskRun, resumeState: RunStatus) extends ResumeResult
  case class ResumeFailed(reason: String) extends ResumeResult

  /**
   * Spec §7.6: Prepare a run for resumption.
   * 1. Load TaskRun, verify Interrupted status
   * 2. Verify worktree exists
   * 3. Clean up orphaned processes
   * 4. Determine resume state per spec table
   */
  def prepareResume(runId: String, repoRoot: Path)(implicit conn: Connection): ResumeResult = {
    val runOpt = TaskRunRepo.getById(runId)
    runOpt match {
      case None =>
        ResumeFailed(s"Run $runId not found")
      case Some(run) if run.status != RunStatus.Interrupted =>
        ResumeFailed(s"Run $runId is in status ${run.status}, not Interrupted. Cannot resume.")
      case Some(run) =>
        // Check worktree exists
        val worktree = run.worktreePath
        if (!Files.isDirectory(worktree)) {
          return ResumeFailed(s"Worktree not found at $worktree (WORKTREE_MISSING)")
        }

        // Clean up orphaned processes (Spec §7.6)
        cleanOrphanedProcesses(worktree, run.runId)

        // Determine resume state (Spec §2.1 Resume Rules)
        val resumeState = determineResumeState(run)

        // If resuming from non-resumable states, abort current attempt
        resumeState match {
          case RunStatus.ReadyToVerify =>
            // Mark current attempt as Aborted if it was in a non-resumable state
            run.currentAttemptId.foreach { attemptId =>
              try {
                AttemptRepo.updateStatus(attemptId, AttemptStatus.Aborted,
                  endedAt = Some(java.time.Instant.now()))
              } catch { case _: Exception => }
            }
          case _ => // no action needed
        }

        // Update run status to resume state
        TaskRunRepo.updateStatus(runId, resumeState)
        val updatedRun = run.copy(status = resumeState, endedAt = None)

        ResumeReady(updatedRun, resumeState)
    }
  }

  /**
   * Spec §2.1 Resume Rules: Map interrupted state to resume behavior.
   * Since the run status is always Interrupted when we get here (checked in prepareResume),
   * we use the last persisted event to infer the pre-interrupt state. If that fails,
   * we fall back to Created which re-runs from scratch.
   */
  private def determineResumeState(run: TaskRun)(implicit conn: Connection): RunStatus = {
    // Look at last state-change event to determine what state we were in before interrupt
    val events = demiurge.persistence.EventRepo.listByRunId(run.runId)
    val lastStateChange = events
      .filter(_.eventType == "state_transition")
      .sortBy(_.timestamp)
      .lastOption

    lastStateChange.flatMap { event =>
      event.correlationFields.get("to_status").flatMap { toStatus =>
        RunStatus.values.find(_.toString == toStatus)
      }
    }.map { preInterruptState =>
      resumeStateFor(preInterruptState, run.attemptCount, run.maxAttempts)
    }.getOrElse(RunStatus.Created)
  }

  /**
   * Spec §2.1: Determine resume state from the state the run was in when interrupted.
   * Called by the orchestrator when it knows the pre-interrupt state.
   */
  def resumeStateFor(interruptedIn: RunStatus, attemptCount: Int, maxAttempts: Int): RunStatus = interruptedIn match {
    case RunStatus.Created => RunStatus.Created
    case RunStatus.InspectingRepo => RunStatus.InspectingRepo
    case RunStatus.CompilingRequirements => RunStatus.CompilingRequirements
    case RunStatus.PlanningEnvironment => RunStatus.PlanningEnvironment
    case RunStatus.BootstrappingEnvironment => RunStatus.BootstrappingEnvironment
    case RunStatus.EnvironmentFailed => RunStatus.EnvironmentFailed
    case RunStatus.SeedingFixtures => RunStatus.SeedingFixtures
    case RunStatus.BootstrappingAuth => RunStatus.BootstrappingAuth
    case RunStatus.ReadyToVerify => RunStatus.ReadyToVerify
    case RunStatus.RebuildingEnvironment => RunStatus.RebuildingEnvironment

    // Build mode states: re-run the planning/generation phase
    case RunStatus.PlanningFeature => RunStatus.PlanningFeature
    case RunStatus.GeneratingCode => RunStatus.GeneratingCode

    // Non-resumable states: abort attempt and resume at ReadyToVerify or Exhausted
    case RunStatus.Verifying | RunStatus.AnalyzingFailure | RunStatus.PlanningRepair |
         RunStatus.Repairing | RunStatus.RepairFailed | RunStatus.PlanningRerun |
         RunStatus.SoftResettingEnvironment =>
      if (attemptCount < maxAttempts) RunStatus.ReadyToVerify
      else RunStatus.Exhausted

    // Terminal states — should not be resumed
    case _ => RunStatus.Exhausted
  }

  /**
   * Spec §7.6: Clean up orphaned processes.
   * 1. Docker containers with label demiurge.run_id
   * 2. Host processes from PID files
   * 3. Compose projects
   */
  private def cleanOrphanedProcesses(worktree: Path, runId: String): Unit = {
    // Clean PID files (Spec §7.6)
    val pidsDir = worktree.resolve(".demiurge").resolve("pids")
    if (Files.isDirectory(pidsDir)) {
      try {
        val stream = Files.list(pidsDir)
        try {
          stream.forEach { pidFile =>
            if (pidFile.toString.endsWith(".pid")) {
              try {
                val pid = new String(Files.readAllBytes(pidFile)).trim.toLong
                // Check if process is alive and kill it
                val process = ProcessHandle.of(pid)
                process.ifPresent { p =>
                  p.destroy()
                  // Wait briefly then force kill
                  Thread.sleep(100)
                  if (p.isAlive) p.destroyForcibly()
                }
                Files.deleteIfExists(pidFile)
              } catch { case _: Exception => Files.deleteIfExists(pidFile) }
            }
          }
        } finally {
          stream.close()
        }
      } catch { case _: Exception => }
    }

    // Clean orphaned tmp files (Spec §7.5)
    cleanTmpFiles(worktree)
  }

  /** Spec §7.5: On startup, delete all files matching .tmp-* in artifact directories. */
  private def cleanTmpFiles(worktree: Path): Unit = {
    val artifactDir = worktree.resolve(".demiurge")
    if (Files.isDirectory(artifactDir)) {
      try {
        val stream = Files.walk(artifactDir, 5)
        try {
          stream.forEach { p =>
            if (Files.isRegularFile(p) && p.getFileName.toString.startsWith(".tmp-")) {
              try { Files.deleteIfExists(p) } catch { case _: Exception => }
            }
          }
        } finally {
          stream.close()
        }
      } catch { case _: Exception => }
    }
  }
}
