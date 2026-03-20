package demiurge.persistence

import java.nio.file.Paths
import java.sql.Connection
import java.time.Instant
import io.circe.syntax._
import io.circe.parser._
import demiurge.model._
import demiurge.model.JsonCodecs._

object TaskRunRepo {

  def insert(run: TaskRun)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO task_runs (
        |  run_id, repo_path, worktree_path, git_ref, task_text, changed_files_json,
        |  status, run_mode, created_at, started_at, ended_at,
        |  max_attempts, attempt_count, env_boot_attempts, current_attempt_id,
        |  final_verdict, final_summary, policy_snapshot_id, lock_file_path, artifact_root_path
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.stripMargin)
    try {
      ps.setString(1, run.runId)
      ps.setString(2, run.repoPath.toString)
      ps.setString(3, run.worktreePath.toString)
      ps.setString(4, run.gitRef.orNull)
      ps.setString(5, run.taskText)
      ps.setString(6, run.changedFiles.map(_.asJson.noSpaces).orNull)
      ps.setString(7, run.status.toString)
      ps.setString(8, run.runMode.toString)
      ps.setString(9, run.createdAt.toString)
      ps.setString(10, run.startedAt.map(_.toString).orNull)
      ps.setString(11, run.endedAt.map(_.toString).orNull)
      ps.setInt(12, run.maxAttempts)
      ps.setInt(13, run.attemptCount)
      ps.setInt(14, run.envBootAttempts)
      ps.setString(15, run.currentAttemptId.orNull)
      ps.setString(16, run.finalVerdict.map(_.toString).orNull)
      ps.setString(17, run.finalSummary.orNull)
      ps.setString(18, run.policySnapshotId)
      ps.setString(19, run.lockFilePath.toString)
      ps.setString(20, run.artifactRootPath.toString)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def getById(runId: String)(implicit conn: Connection): Option[TaskRun] = {
    val ps = conn.prepareStatement("SELECT * FROM task_runs WHERE run_id = ?")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(rowToTaskRun(rs)) else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def updateStatus(runId: String, status: RunStatus, endedAt: Option[Instant] = None)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("UPDATE task_runs SET status = ?, ended_at = ? WHERE run_id = ?")
    try {
      ps.setString(1, status.toString)
      ps.setString(2, endedAt.map(_.toString).orNull)
      ps.setString(3, runId)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def listByRepoPath(repoPath: String)(implicit conn: Connection): List[TaskRun] = {
    val ps = conn.prepareStatement("SELECT * FROM task_runs WHERE repo_path = ?")
    try {
      ps.setString(1, repoPath)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[TaskRun]()
        while (rs.next()) {
          buf += rowToTaskRun(rs)
        }
        buf.toList
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  // Phase 2: Update full TaskRun record
  def update(run: TaskRun)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """UPDATE task_runs SET
        |  repo_path = ?, worktree_path = ?, git_ref = ?, task_text = ?, changed_files_json = ?,
        |  status = ?, run_mode = ?, created_at = ?, started_at = ?, ended_at = ?,
        |  max_attempts = ?, attempt_count = ?, env_boot_attempts = ?, current_attempt_id = ?,
        |  final_verdict = ?, final_summary = ?, policy_snapshot_id = ?, lock_file_path = ?, artifact_root_path = ?
        |WHERE run_id = ?
      """.stripMargin)
    try {
      ps.setString(1, run.repoPath.toString)
      ps.setString(2, run.worktreePath.toString)
      ps.setString(3, run.gitRef.orNull)
      ps.setString(4, run.taskText)
      ps.setString(5, run.changedFiles.map(_.asJson.noSpaces).orNull)
      ps.setString(6, run.status.toString)
      ps.setString(7, run.runMode.toString)
      ps.setString(8, run.createdAt.toString)
      ps.setString(9, run.startedAt.map(_.toString).orNull)
      ps.setString(10, run.endedAt.map(_.toString).orNull)
      ps.setInt(11, run.maxAttempts)
      ps.setInt(12, run.attemptCount)
      ps.setInt(13, run.envBootAttempts)
      ps.setString(14, run.currentAttemptId.orNull)
      ps.setString(15, run.finalVerdict.map(_.toString).orNull)
      ps.setString(16, run.finalSummary.orNull)
      ps.setString(17, run.policySnapshotId)
      ps.setString(18, run.lockFilePath.toString)
      ps.setString(19, run.artifactRootPath.toString)
      ps.setString(20, run.runId)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  // Phase 2: Set current attempt ID on a run
  def setCurrentAttempt(runId: String, attemptId: Option[String])(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("UPDATE task_runs SET current_attempt_id = ? WHERE run_id = ?")
    try {
      ps.setString(1, attemptId.orNull)
      ps.setString(2, runId)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  // Phase 2: Increment attempt count
  def incrementAttemptCount(runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("UPDATE task_runs SET attempt_count = attempt_count + 1 WHERE run_id = ?")
    try {
      ps.setString(1, runId)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  // Phase 2: Find active (non-terminal) runs for a repo path
  def getActiveRunByRepoPath(repoPath: String)(implicit conn: Connection): Option[TaskRun] = {
    val terminalStatuses = List("Succeeded", "Exhausted", "Cancelled", "Interrupted")
    val placeholders = terminalStatuses.map(_ => "?").mkString(", ")
    val ps = conn.prepareStatement(
      s"SELECT * FROM task_runs WHERE repo_path = ? AND status NOT IN ($placeholders) LIMIT 1"
    )
    try {
      ps.setString(1, repoPath)
      terminalStatuses.zipWithIndex.foreach { case (s, i) => ps.setString(i + 2, s) }
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(rowToTaskRun(rs)) else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  // Phase 2: Set startedAt timestamp
  def setStartedAt(runId: String, startedAt: Instant)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("UPDATE task_runs SET started_at = ? WHERE run_id = ?")
    try {
      ps.setString(1, startedAt.toString)
      ps.setString(2, runId)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  // Phase 4: Set final verdict
  def setFinalVerdict(runId: String, verdict: VerdictStatus)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("UPDATE task_runs SET final_verdict = ? WHERE run_id = ?")
    try {
      ps.setString(1, verdict.toString)
      ps.setString(2, runId)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  // Phase 2: Set final summary
  def setFinalSummary(runId: String, summary: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("UPDATE task_runs SET final_summary = ? WHERE run_id = ?")
    try {
      ps.setString(1, summary)
      ps.setString(2, runId)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  // Phase 2: Update worktree path on a run
  def updateWorktreePath(runId: String, worktreePath: java.nio.file.Path)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("UPDATE task_runs SET worktree_path = ? WHERE run_id = ?")
    try {
      ps.setString(1, worktreePath.toString)
      ps.setString(2, runId)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  // Desktop Phase 1: Paginated list with optional status filter and sorting
  def listPaginated(
    offset: Int = 0,
    limit: Int = 20,
    statusFilter: Option[RunStatus] = None,
    sort: String = "created_at",
    order: String = "desc",
  )(implicit conn: Connection): (List[TaskRun], Int) = {
    val allowedSorts = Set("created_at", "status", "task_text", "run_mode")
    val safeSort = if (allowedSorts.contains(sort)) sort else "created_at"
    val safeOrder = if (order.equalsIgnoreCase("asc")) "ASC" else "DESC"

    val whereClause = statusFilter.map(_ => "WHERE status = ?").getOrElse("")

    // Count query
    val countPs = conn.prepareStatement(s"SELECT COUNT(*) FROM task_runs $whereClause")
    try {
      statusFilter.foreach(s => countPs.setString(1, s.toString))
      val countRs = countPs.executeQuery()
      val total = if (countRs.next()) countRs.getInt(1) else 0
      countRs.close()

      // Data query
      val dataPs = conn.prepareStatement(
        s"SELECT * FROM task_runs $whereClause ORDER BY $safeSort $safeOrder LIMIT ? OFFSET ?"
      )
      try {
        var paramIdx = 1
        statusFilter.foreach { s =>
          dataPs.setString(paramIdx, s.toString)
          paramIdx += 1
        }
        dataPs.setInt(paramIdx, limit)
        dataPs.setInt(paramIdx + 1, offset)
        val rs = dataPs.executeQuery()
        try {
          val buf = scala.collection.mutable.ListBuffer[TaskRun]()
          while (rs.next()) { buf += rowToTaskRun(rs) }
          (buf.toList, total)
        } finally { rs.close() }
      } finally { dataPs.close() }
    } finally { countPs.close() }
  }

  // Desktop Phase 1: Find any active (non-terminal) run across all repos
  def getActiveRun()(implicit conn: Connection): Option[TaskRun] = {
    val terminalStatuses = List("Succeeded", "Exhausted", "Cancelled", "Interrupted", "EnvironmentFailed")
    val placeholders = terminalStatuses.map(_ => "?").mkString(", ")
    val ps = conn.prepareStatement(
      s"SELECT * FROM task_runs WHERE status NOT IN ($placeholders) ORDER BY created_at DESC LIMIT 1"
    )
    try {
      terminalStatuses.zipWithIndex.foreach { case (s, i) => ps.setString(i + 1, s) }
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(rowToTaskRun(rs)) else None
      } finally { rs.close() }
    } finally { ps.close() }
  }

  // Phase 7: List recent runs ordered by creation time descending
  def listRecent(limit: Int = 20)(implicit conn: Connection): List[TaskRun] = {
    val ps = conn.prepareStatement("SELECT * FROM task_runs ORDER BY created_at DESC LIMIT ?")
    try {
      ps.setInt(1, limit)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[TaskRun]()
        while (rs.next()) { buf += rowToTaskRun(rs) }
        buf.toList
      } finally { rs.close() }
    } finally { ps.close() }
  }

  // Phase 7: List all runs (for clean --all)
  def listAll()(implicit conn: Connection): List[TaskRun] = {
    val ps = conn.prepareStatement("SELECT * FROM task_runs ORDER BY created_at DESC")
    try {
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[TaskRun]()
        while (rs.next()) { buf += rowToTaskRun(rs) }
        buf.toList
      } finally { rs.close() }
    } finally { ps.close() }
  }

  // Phase 7: Delete a run record by ID
  def deleteById(runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("DELETE FROM task_runs WHERE run_id = ?")
    try {
      ps.setString(1, runId)
      ps.executeUpdate()
    } finally { ps.close() }
  }

  private def rowToTaskRun(rs: java.sql.ResultSet): TaskRun = {
    val changedFilesJson = Option(rs.getString("changed_files_json"))
    val changedFiles = changedFilesJson.flatMap { json =>
      decode[List[String]](json).toOption
    }
    val finalVerdictStr = Option(rs.getString("final_verdict"))
    val finalVerdict = finalVerdictStr.flatMap(s => VerdictStatus.values.find(_.toString == s))

    TaskRun(
      runId = rs.getString("run_id"),
      repoPath = Paths.get(rs.getString("repo_path")),
      worktreePath = Paths.get(rs.getString("worktree_path")),
      gitRef = Option(rs.getString("git_ref")),
      taskText = rs.getString("task_text"),
      changedFiles = changedFiles,
      status = {
        val s = rs.getString("status")
        RunStatus.values.find(_.toString == s)
          .getOrElse(throw new IllegalStateException(s"Unknown RunStatus in DB: $s"))
      },
      runMode = {
        val s = rs.getString("run_mode")
        RunMode.values.find(_.toString == s)
          .getOrElse(throw new IllegalStateException(s"Unknown RunMode in DB: $s"))
      },
      createdAt = Instant.parse(rs.getString("created_at")),
      startedAt = Option(rs.getString("started_at")).map(Instant.parse),
      endedAt = Option(rs.getString("ended_at")).map(Instant.parse),
      maxAttempts = rs.getInt("max_attempts"),
      attemptCount = rs.getInt("attempt_count"),
      envBootAttempts = rs.getInt("env_boot_attempts"),
      currentAttemptId = Option(rs.getString("current_attempt_id")),
      finalVerdict = finalVerdict,
      finalSummary = Option(rs.getString("final_summary")),
      policySnapshotId = rs.getString("policy_snapshot_id"),
      lockFilePath = Paths.get(rs.getString("lock_file_path")),
      artifactRootPath = Paths.get(rs.getString("artifact_root_path")),
    )
  }
}
