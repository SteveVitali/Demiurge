package lastmile.orchestrator

import munit.FunSuite
import java.nio.file.Files

class WorktreeManagerSuite extends FunSuite with TestFixtures {

  test("creates worktree under .lastmile/worktrees/<runId>") {
    withTempGitRepo { repoRoot =>
      val runId = "wt-test-001"
      val wtPath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))

      try {
        val expectedBase = repoRoot.resolve(".lastmile").resolve("worktrees")
        assert(wtPath.startsWith(expectedBase),
          s"Worktree path $wtPath should be under $expectedBase")
        assert(wtPath.getFileName.toString == runId,
          s"Worktree directory should be named $runId")
      } finally {
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("created worktree exists on disk") {
    withTempGitRepo { repoRoot =>
      val runId = "wt-test-002"
      val wtPath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))

      try {
        assert(Files.exists(wtPath), s"Worktree path $wtPath should exist on disk")
        assert(Files.isDirectory(wtPath), s"Worktree path $wtPath should be a directory")
        // Should contain the README.md from the commit
        assert(Files.exists(wtPath.resolve("README.md")),
          "Worktree should contain files from the commit")
      } finally {
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("worktree points to detached HEAD") {
    withTempGitRepo { repoRoot =>
      val runId = "wt-test-003"
      val wtPath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))

      try {
        assert(WorktreeManager.isDetachedHead(wtPath),
          "Worktree should have a detached HEAD")
      } finally {
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("cleanup helper removes test worktree") {
    withTempGitRepo { repoRoot =>
      val runId = "wt-test-004"
      val wtPath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))

      assert(Files.exists(wtPath), "Worktree should exist before cleanup")

      WorktreeManager.remove(repoRoot, runId)

      assert(!Files.exists(wtPath), "Worktree should not exist after cleanup")
    }
  }
}
