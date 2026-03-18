package demiurge.orchestrator

import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant

import io.circe._
import io.circe.syntax._
import io.circe.parser._

// Spec §4.3: Run lock at <repo_root>/.demiurge/run.lock
// Ensures single concurrent run per repository.
object LockManager {

  case class LockPayload(
    runId:        String,
    pid:          Long,
    startedAt:    Instant,
    worktreePath: String,
  )

  private implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  private implicit val instantDecoder: Decoder[Instant] = Decoder.decodeString.emap { s =>
    try Right(Instant.parse(s)) catch { case e: Exception => Left(s"Invalid instant: $s") }
  }

  private implicit val lockPayloadEncoder: Encoder[LockPayload] = Encoder.forProduct4(
    "runId", "pid", "startedAt", "worktreePath"
  )(lp => (lp.runId, lp.pid, lp.startedAt, lp.worktreePath))

  private implicit val lockPayloadDecoder: Decoder[LockPayload] = Decoder.forProduct4(
    "runId", "pid", "startedAt", "worktreePath"
  )(LockPayload.apply)

  /** Resolve the lock file path for a given repo root. */
  def lockPath(repoRoot: Path): Path =
    repoRoot.resolve(".demiurge").resolve("run.lock")

  /**
   * Acquire the run lock. Spec §4.3:
   * - Atomic create
   * - JSON contents with runId, pid, startedAt, worktreePath
   * - If lock exists and owning PID alive → fail with concurrent run conflict
   * - If lock exists and owning PID dead → delete stale lock and acquire
   */
  def acquire(repoRoot: Path, runId: String, worktreePath: Path): Path = {
    val lock = lockPath(repoRoot)
    Files.createDirectories(lock.getParent)

    if (Files.exists(lock)) {
      val existing = readLock(lock)
      existing match {
        case Some(payload) if isProcessAlive(payload.pid) =>
          throw new IllegalStateException(
            s"Concurrent run conflict: lock held by runId=${payload.runId}, pid=${payload.pid}"
          )
        case _ =>
          // Stale lock — owning PID dead or unreadable. Remove and reacquire.
          Files.deleteIfExists(lock)
      }
    }

    val payload = LockPayload(
      runId = runId,
      pid = ProcessHandle.current().pid(),
      startedAt = Instant.now(),
      worktreePath = worktreePath.toString,
    )

    // Atomic create — CREATE_NEW ensures fail if file concurrently created
    Files.write(
      lock,
      payload.asJson.spaces2.getBytes("UTF-8"),
      StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
    )

    lock
  }

  /** Release the run lock. Spec §4.3: release on normal termination. */
  def release(repoRoot: Path): Unit = {
    val lock = lockPath(repoRoot)
    Files.deleteIfExists(lock)
  }

  /** Read and parse the lock file, returning None if unreadable or missing. */
  def readLock(lockFile: Path): Option[LockPayload] = {
    if (!Files.exists(lockFile)) return None
    try {
      val content = new String(Files.readAllBytes(lockFile), "UTF-8")
      decode[LockPayload](content).toOption
    } catch {
      case _: Exception => None
    }
  }

  /** Check if a process with the given PID is still alive. */
  private[orchestrator] def isProcessAlive(pid: Long): Boolean = {
    try {
      val handle = ProcessHandle.of(pid)
      handle.isPresent && handle.get().isAlive
    } catch {
      case _: Exception => false
    }
  }
}
