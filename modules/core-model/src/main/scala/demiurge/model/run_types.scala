package demiurge.model

import java.nio.file.Path
import java.time.Instant

// Spec §3.2: TaskRun
case class TaskRun(
  runId:              String,
  repoPath:           Path,
  worktreePath:       Path,
  gitRef:             Option[String],
  taskText:           String,
  changedFiles:       Option[List[String]],
  status:             RunStatus,
  runMode:            RunMode,
  createdAt:          Instant,
  startedAt:          Option[Instant],
  endedAt:            Option[Instant],
  maxAttempts:        Int,
  attemptCount:       Int,
  envBootAttempts:    Int,
  currentAttemptId:   Option[String],
  finalVerdict:       Option[VerdictStatus],
  finalSummary:       Option[String],
  policySnapshotId:   String,
  lockFilePath:       Path,
  artifactRootPath:   Path,
)

// Spec §3.2: Attempt
case class Attempt(
  attemptId:          String,
  runId:              String,
  attemptNumber:      Int,
  status:             AttemptStatus,
  startedAt:          Instant,
  endedAt:            Option[Instant],
  repairBackend:      Option[String],
  patchRecordId:      Option[String],
  failurePacketId:    Option[String],
  rerunPlanId:        Option[String],
  repairRetriesUsed:  Int,
  verdictSummary:     Option[AttemptVerdictSummary],
)

// Spec §3.2: AttemptVerdictSummary
case class AttemptVerdictSummary(
  totalRequired:      Int,
  passCount:          Int,
  failCount:          Int,
  inconclusiveCount:  Int,
  blockedCount:       Int,
  timeoutCount:       Int,
  flakeCount:         Int,
)
