package demiurge.orchestrator

import munit.FunSuite
import java.nio.file.Files
import java.time.Instant
import demiurge.model._
import demiurge.persistence._

class SignalHandlerSuite extends FunSuite with TestFixtures {

  test("simulated interrupt marks run as Interrupted") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "sig-test-001"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        SignalHandler.register(ctx, repoRoot)

        // Simulate interrupt
        SignalHandler.simulateInterrupt()

        // Verify run is marked as Interrupted in DB
        val persisted = TaskRunRepo.getById(runId)
        assert(persisted.isDefined, "Run should still exist in DB")
        assertEquals(persisted.get.status, RunStatus.Interrupted)
        assert(persisted.get.endedAt.isDefined, "endedAt should be set after interruption")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
        SignalHandler.reset()
      }
    }
  }

  test("lock is left in recoverable stale state after interrupt") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "sig-test-002"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        SignalHandler.register(ctx, repoRoot)

        // Simulate interrupt — lock should be left in place (stale lock recovery)
        SignalHandler.simulateInterrupt()

        // Lock file should still exist (Spec §4.3: stale lock left for recovery)
        assert(Files.exists(lockPath),
          "Lock file should remain after interrupt for stale lock recovery")

        // Verify the lock can be recovered by a new run (stale lock cleanup)
        // The PID in the lock is current process (still alive), so we can't
        // test stale recovery here. But we verify the lock is parseable.
        val payload = LockManager.readLock(lockPath)
        assert(payload.isDefined, "Lock should be readable for stale recovery")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
        SignalHandler.reset()
      }
    }
  }

  test("partial run remains resumable in DB") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "sig-test-003"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        // Create a run that's partway through (CompilingRequirements)
        val run = makeRun(runId, repoRoot, worktreePath, lockPath,
          status = RunStatus.CompilingRequirements, taskText = "Signal handler test task")
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        SignalHandler.register(ctx, repoRoot)

        // Simulate interrupt
        SignalHandler.simulateInterrupt()

        // Verify the run is in Interrupted state and has essential data
        val persisted = TaskRunRepo.getById(runId)
        assert(persisted.isDefined)
        assertEquals(persisted.get.status, RunStatus.Interrupted)
        // The run retains its identifying data for potential resume
        assertEquals(persisted.get.runId, runId)
        assertEquals(persisted.get.repoPath, repoRoot)
        assertEquals(persisted.get.worktreePath, worktreePath)
        assertEquals(persisted.get.taskText, "Signal handler test task")
        assertEquals(persisted.get.gitRef, Some("HEAD"))
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
        SignalHandler.reset()
      }
    }
  }

  test("interrupt does not affect already-terminal run") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "sig-test-004"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val endTime = Instant.now()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath,
          status = RunStatus.Exhausted, endedAt = Some(endTime))
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        SignalHandler.register(ctx, repoRoot)

        // Simulate interrupt on an already-terminal run
        SignalHandler.simulateInterrupt()

        // Status should remain Exhausted (not changed to Interrupted)
        val persisted = TaskRunRepo.getById(runId)
        assert(persisted.isDefined)
        assertEquals(persisted.get.status, RunStatus.Exhausted)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
        SignalHandler.reset()
      }
    }
  }
}
