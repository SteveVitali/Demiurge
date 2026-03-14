package demiurge.orchestrator

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID
import demiurge.model._
import demiurge.persistence._

// Spec §4: Phase 2 smoke entrypoint (not a real CLI).
// Opens DB, runs migrations, acquires lock, creates worktree,
// creates TaskRun, invokes orchestrator, prints final status, releases lock.
object Main {

  def main(args: Array[String]): Unit = {
    println("Demiurge Phase 2 — Orchestrator Smoke Entrypoint")

    // Resolve repo root from current working directory
    val cwd = Paths.get(System.getProperty("user.dir"))
    val repoRoot = WorktreeManager.resolveRepoRoot(cwd)
    println(s"Repo root: $repoRoot")

    // Open DB under .demiurge/
    val demiurgeDir = repoRoot.resolve(".demiurge")
    Files.createDirectories(demiurgeDir)
    val dbPath = demiurgeDir.resolve("demiurge.db")
    println(s"DB path: $dbPath")

    val conn = Database.open(dbPath)
    var lockAcquired = false

    try {
      // Run migrations
      Migrator.migrate(conn)
      println(s"Schema version: ${Migrator.currentVersion(conn)}")

      val budget = ExecutionBudgetDefaults.defaults
      val runId = UUID.randomUUID().toString
      val taskText = if (args.nonEmpty) args.mkString(" ") else "Phase 2 smoke test task"

      // Create worktree (Spec §4.2)
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      println(s"Worktree created: $worktreePath")

      // Acquire lock (Spec §4.3)
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)
      lockAcquired = true
      println(s"Lock acquired: $lockPath")

      // Create and persist TaskRun (Spec §3.2)
      implicit val c: java.sql.Connection = conn
      val run = TaskRun(
        runId = runId,
        repoPath = repoRoot,
        worktreePath = worktreePath,
        gitRef = Some("HEAD"),
        taskText = taskText,
        changedFiles = None,
        status = RunStatus.Created,
        runMode = RunMode.Full,
        createdAt = Instant.now(),
        startedAt = None,
        endedAt = None,
        maxAttempts = budget.maxAttempts,
        attemptCount = 0,
        envBootAttempts = 0,
        currentAttemptId = None,
        finalVerdict = None,
        finalSummary = None,
        policySnapshotId = s"ps-$runId",
        lockFilePath = lockPath,
        artifactRootPath = repoRoot.resolve(".runs").resolve(runId),
      )

      TaskRunRepo.insert(run)
      println(s"TaskRun created: $runId")

      // Build run context and invoke orchestrator
      val ctx = RunContext(
        run = run,
        repoRoot = repoRoot,
        worktreePath = worktreePath,
        conn = conn,
      )

      val finalRun = RunOrchestrator.execute(
        ctx,
        StubRepoInspector,
        StubRequirementCompiler,
        StubEnvironmentPlanner,
        StubRuntimeSupervisor,
      )

      println(s"Run completed: status=${finalRun.status}, summary=${finalRun.finalSummary.getOrElse("none")}")

      // Release lock on success (Spec §4.3)
      LockManager.release(repoRoot)
      lockAcquired = false
      println("Lock released.")

      // Clean up worktree for smoke test
      WorktreeManager.remove(repoRoot, runId)
      println("Worktree cleaned up.")

      println("Phase 2 smoke entrypoint completed successfully.")
    } catch {
      case e: Exception =>
        System.err.println(s"ERROR: ${e.getMessage}")
        e.printStackTrace()
        System.exit(1)
    } finally {
      if (lockAcquired) {
        LockManager.release(repoRoot)
      }
      conn.close()
    }
  }
}
