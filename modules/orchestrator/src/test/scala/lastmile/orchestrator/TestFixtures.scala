package lastmile.orchestrator

import java.nio.file.{Files, Path}
import java.time.Instant
import lastmile.model._
import lastmile.persistence._

import scala.sys.process._

/** Shared test fixtures for orchestrator test suites. */
trait TestFixtures {

  /** Create a temporary git repo with one commit, invoke testFn, then clean up. */
  protected def withTempGitRepo(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("demiurge-test-")
    initGitRepo(tmpDir)
    try {
      testFn(tmpDir)
    } finally {
      try { Process(Seq("git", "worktree", "prune"), tmpDir.toFile).! } catch { case _: Exception => }
      deleteRecursive(tmpDir)
    }
  }

  /** Create a temporary git repo with DB, invoke testFn, then clean up both. */
  protected def withTempGitRepoAndDb(testFn: (Path, java.sql.Connection) => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("demiurge-test-")
    val dbPath = tmpDir.resolve(".lastmile").resolve("lastmile.db")
    Files.createDirectories(dbPath.getParent)
    initGitRepo(tmpDir)

    val conn = Database.open(dbPath)
    try {
      Migrator.migrate(conn)
      testFn(tmpDir, conn)
    } finally {
      conn.close()
      try { Process(Seq("git", "worktree", "prune"), tmpDir.toFile).! } catch { case _: Exception => }
      deleteRecursive(tmpDir)
    }
  }

  /** Create a temporary directory (no git), invoke testFn, then clean up. */
  protected def withTempDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("demiurge-test-")
    try {
      Files.createDirectories(tmpDir.resolve(".lastmile"))
      testFn(tmpDir)
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  /** Build a TaskRun in Created state for testing. */
  protected def makeRun(
    runId: String,
    repoRoot: Path,
    worktreePath: Path,
    lockPath: Path,
    status: RunStatus = RunStatus.Created,
    taskText: String = "Test task",
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
  ): TaskRun = TaskRun(
    runId = runId,
    repoPath = repoRoot,
    worktreePath = worktreePath,
    gitRef = Some("HEAD"),
    taskText = taskText,
    changedFiles = None,
    status = status,
    runMode = RunMode.Full,
    createdAt = Instant.now(),
    startedAt = startedAt,
    endedAt = endedAt,
    maxAttempts = 5,
    attemptCount = 0,
    envBootAttempts = 0,
    currentAttemptId = None,
    finalVerdict = None,
    finalSummary = None,
    policySnapshotId = "ps-test",
    lockFilePath = lockPath,
    artifactRootPath = repoRoot.resolve(".runs").resolve(runId),
  )

  private def initGitRepo(dir: Path): Unit = {
    Process(Seq("git", "init"), dir.toFile).!
    Process(Seq("git", "config", "user.email", "test@test.com"), dir.toFile).!
    Process(Seq("git", "config", "user.name", "Test"), dir.toFile).!
    Files.write(dir.resolve("README.md"), "# Test repo\n".getBytes)
    Process(Seq("git", "add", "."), dir.toFile).!
    Process(Seq("git", "commit", "-m", "initial commit"), dir.toFile).!
  }

  protected def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      val entries = Files.list(path)
      try {
        entries.forEach(p => deleteRecursive(p))
      } finally {
        entries.close()
      }
    }
    Files.deleteIfExists(path)
  }
}
