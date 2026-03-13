package lastmile.orchestrator

import java.sql.Connection
import java.time.Instant
import java.util.UUID

import lastmile.model._
import lastmile.persistence._

// Phase 4: Manages attempt lifecycle within a run.
// Creates attempts, updates verdict summaries, and finalizes attempt status.
object AttemptManager {

  def createAttempt(runId: String, attemptNumber: Int)(implicit conn: Connection): Attempt = {
    val now = Instant.now()
    val attemptId = UUID.randomUUID().toString
    val attempt = Attempt(
      attemptId = attemptId,
      runId = runId,
      attemptNumber = attemptNumber,
      status = AttemptStatus.Created,
      startedAt = now,
      endedAt = None,
      repairBackend = None,
      patchRecordId = None,
      failurePacketId = None,
      rerunPlanId = None,
      repairRetriesUsed = 0,
      verdictSummary = None,
    )

    TransactionManager.atomic(conn) { txn =>
      AttemptRepo.insert(attempt)(txn)
      TaskRunRepo.setCurrentAttempt(runId, Some(attemptId))(txn)
      TaskRunRepo.incrementAttemptCount(runId)(txn)
    }

    attempt
  }

  def startVerifying(attempt: Attempt)(implicit conn: Connection): Attempt = {
    val updated = attempt.copy(status = AttemptStatus.Verifying)
    AttemptRepo.updateStatus(attempt.attemptId, AttemptStatus.Verifying)
    updated
  }

  def completeAttempt(
    attempt: Attempt,
    verdicts: List[RequirementVerdict],
    overallVerdict: VerdictStatus,
  )(implicit conn: Connection): Attempt = {
    val now = Instant.now()
    val passCount = verdicts.count(_.status == VerdictStatus.Pass)
    val failCount = verdicts.count(_.status == VerdictStatus.Fail)
    val inconclusiveCount = verdicts.count(_.status == VerdictStatus.Inconclusive)
    val blockedCount = verdicts.count(_.status == VerdictStatus.Blocked)
    val timeoutCount = verdicts.count(_.status == VerdictStatus.Timeout)
    val flakeCount = verdicts.count(_.status == VerdictStatus.Flake)
    val totalRequired = verdicts.size

    val summary = AttemptVerdictSummary(
      totalRequired = totalRequired,
      passCount = passCount,
      failCount = failCount,
      inconclusiveCount = inconclusiveCount,
      blockedCount = blockedCount,
      timeoutCount = timeoutCount,
      flakeCount = flakeCount,
    )

    val finalStatus = if (overallVerdict == VerdictStatus.Pass) {
      AttemptStatus.VerificationPassed
    } else {
      AttemptStatus.VerificationFailed
    }

    val updated = attempt.copy(
      status = finalStatus,
      endedAt = Some(now),
      verdictSummary = Some(summary),
    )

    TransactionManager.atomic(conn) { txn =>
      VerdictRepo.insertAll(verdicts)(txn)
      AttemptRepo.updateStatus(attempt.attemptId, finalStatus, endedAt = Some(now))(txn)
      AttemptRepo.updateVerdictSummary(attempt.attemptId, summary)(txn)
    }

    updated
  }
}
