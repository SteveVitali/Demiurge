package demiurge.orchestrator

import java.nio.file.{Files, Path}
import scala.sys.process._

// Spec §4.2: Isolated git worktree per run
// Path: <repo_root>/.demiurge/worktrees/<runId>/
// Detached HEAD at resolved commit SHA
object WorktreeManager {

  /** Base directory for all worktrees under a repo root. */
  def worktreeBase(repoRoot: Path): Path =
    repoRoot.resolve(".demiurge").resolve("worktrees")

  /** Compute the worktree path for a given run. */
  def worktreePath(repoRoot: Path, runId: String): Path =
    worktreeBase(repoRoot).resolve(runId)

  /**
   * Resolve the repo root from any path inside a git repository.
   * Runs `git rev-parse --show-toplevel`.
   */
  def resolveRepoRoot(anyPathInRepo: Path): Path = {
    val result = Process(Seq("git", "rev-parse", "--show-toplevel"), anyPathInRepo.toFile).!!.trim
    Path.of(result)
  }

  /**
   * Resolve the current HEAD commit SHA for the repo.
   * If gitRef is provided, resolve that ref; otherwise resolve HEAD.
   */
  def resolveCommitSha(repoRoot: Path, gitRef: Option[String]): String = {
    val ref = gitRef.getOrElse("HEAD")
    Process(Seq("git", "rev-parse", ref), repoRoot.toFile).!!.trim
  }

  /**
   * Create an isolated git worktree for a run. Spec §4.2:
   * - Create .demiurge/worktrees/ if missing
   * - Run `git worktree add --detach <worktree_path> <resolved_commit_sha>`
   * - Return the worktree path
   */
  def create(repoRoot: Path, runId: String, gitRef: Option[String]): Path = {
    val wtPath = worktreePath(repoRoot, runId)
    Files.createDirectories(wtPath.getParent)

    val commitSha = resolveCommitSha(repoRoot, gitRef)

    val cmd = Seq("git", "worktree", "add", "--detach", wtPath.toAbsolutePath.toString, commitSha)
    val exitCode = Process(cmd, repoRoot.toFile).!
    if (exitCode != 0) {
      throw new RuntimeException(s"git worktree add failed with exit code $exitCode for runId=$runId")
    }

    wtPath
  }

  /**
   * Check if a worktree path has a detached HEAD.
   * Returns true if HEAD is detached in the worktree.
   */
  def isDetachedHead(wtPath: Path): Boolean = {
    try {
      val result = Process(Seq("git", "symbolic-ref", "HEAD"), wtPath.toFile).!
      // symbolic-ref returns non-zero if HEAD is detached
      result != 0
    } catch {
      case _: Exception => true
    }
  }

  /**
   * Cleanup helper: remove a worktree. Spec §4.2:
   * - Preserves worktree on normal exit
   * - This helper is for tests and explicit cleanup
   */
  def remove(repoRoot: Path, runId: String): Unit = {
    val wtPath = worktreePath(repoRoot, runId)
    if (Files.exists(wtPath)) {
      Process(Seq("git", "worktree", "remove", "--force", wtPath.toAbsolutePath.toString), repoRoot.toFile).!
    }
    // Also prune to clean up stale entries
    Process(Seq("git", "worktree", "prune"), repoRoot.toFile).!
  }
}
