package lastmile.orchestrator

import munit.FunSuite
import java.nio.file.Files

import io.circe.parser._

class LockManagerSuite extends FunSuite with TestFixtures {

  test("acquires lock when none exists") {
    withTempDir { repoRoot =>
      val worktreePath = repoRoot.resolve(".lastmile").resolve("worktrees").resolve("run-001")
      Files.createDirectories(worktreePath)

      val lockPath = LockManager.acquire(repoRoot, "run-001", worktreePath)

      assert(Files.exists(lockPath), "Lock file should exist after acquisition")

      LockManager.release(repoRoot)
    }
  }

  test("fails when live lock exists") {
    withTempDir { repoRoot =>
      val worktreePath = repoRoot.resolve(".lastmile").resolve("worktrees").resolve("run-001")
      Files.createDirectories(worktreePath)

      // Acquire first lock (with current PID — which is alive)
      LockManager.acquire(repoRoot, "run-001", worktreePath)

      // Try to acquire second lock — should fail because current PID is alive
      val ex = intercept[IllegalStateException] {
        LockManager.acquire(repoRoot, "run-002", worktreePath)
      }
      assert(ex.getMessage.contains("Concurrent run conflict"),
        s"Expected concurrent run conflict, got: ${ex.getMessage}")

      LockManager.release(repoRoot)
    }
  }

  test("removes stale lock and reacquires") {
    withTempDir { repoRoot =>
      val worktreePath = repoRoot.resolve(".lastmile").resolve("worktrees").resolve("run-001")
      Files.createDirectories(worktreePath)

      // Write a lock file with a dead PID (PID 999999999 should not be alive)
      val lockPath = LockManager.lockPath(repoRoot)
      val staleLockJson = s"""{"runId":"old-run","pid":999999999,"startedAt":"2025-01-01T00:00:00Z","worktreePath":"${worktreePath.toString}"}"""
      Files.write(lockPath, staleLockJson.getBytes("UTF-8"))

      assert(Files.exists(lockPath), "Stale lock should exist before reacquisition")

      // Should succeed — stale lock is cleaned up
      val newLockPath = LockManager.acquire(repoRoot, "run-002", worktreePath)
      assert(Files.exists(newLockPath), "New lock should exist after stale lock removal")

      // Verify the new lock has the correct runId
      val payload = LockManager.readLock(newLockPath)
      assert(payload.isDefined)
      assertEquals(payload.get.runId, "run-002")

      LockManager.release(repoRoot)
    }
  }

  test("writes valid JSON payload") {
    withTempDir { repoRoot =>
      val worktreePath = repoRoot.resolve(".lastmile").resolve("worktrees").resolve("run-001")
      Files.createDirectories(worktreePath)

      val lockPath = LockManager.acquire(repoRoot, "run-001", worktreePath)

      // Read raw JSON and verify it parses
      val content = new String(Files.readAllBytes(lockPath), "UTF-8")
      val parsed = parse(content)
      assert(parsed.isRight, s"Lock file should contain valid JSON, got error: ${parsed.left.getOrElse("")}")

      val json = parsed.toOption.get
      val cursor = json.hcursor
      assertEquals(cursor.get[String]("runId").toOption, Some("run-001"))
      assert(cursor.get[Long]("pid").toOption.isDefined, "Should have a PID")
      assert(cursor.get[String]("startedAt").toOption.isDefined, "Should have startedAt")
      assertEquals(cursor.get[String]("worktreePath").toOption, Some(worktreePath.toString))

      LockManager.release(repoRoot)
    }
  }

  test("releases lock correctly") {
    withTempDir { repoRoot =>
      val worktreePath = repoRoot.resolve(".lastmile").resolve("worktrees").resolve("run-001")
      Files.createDirectories(worktreePath)

      val lockPath = LockManager.acquire(repoRoot, "run-001", worktreePath)
      assert(Files.exists(lockPath), "Lock should exist after acquire")

      LockManager.release(repoRoot)
      assert(!Files.exists(lockPath), "Lock should not exist after release")
    }
  }
}
