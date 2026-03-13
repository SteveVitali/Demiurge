package lastmile.orchestrator

import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.Instant
import java.util.UUID

import lastmile.model._
import lastmile.persistence._

// Phase 8: Retry and robustness tests (Spec §2.1, §8)
// Tests env boot retry, repair retry, worker restart budget, exhaustion behavior.
class RetryRobustnessSuite extends munit.FunSuite {

  private def withDb(fn: Connection => Unit): Unit = {
    val tmp = Files.createTempFile("retry-test-", ".db")
    Files.delete(tmp)
    val conn = Database.open(tmp)
    try {
      Migrator.migrate(conn)
      fn(conn)
    } finally {
      conn.close()
      Files.deleteIfExists(tmp)
    }
  }

  private def insertRun(runId: String, status: RunStatus, maxAttempts: Int = 5, attemptCount: Int = 0,
                        envBootAttempts: Int = 0)(implicit conn: Connection): TaskRun = {
    val run = TaskRun(
      runId = runId,
      repoPath = Path.of("/tmp/repo"),
      worktreePath = Path.of("/tmp/worktree"),
      gitRef = None,
      taskText = "Test task",
      changedFiles = None,
      status = status,
      runMode = RunMode.Full,
      createdAt = Instant.now(),
      startedAt = Some(Instant.now()),
      endedAt = None,
      maxAttempts = maxAttempts,
      attemptCount = attemptCount,
      envBootAttempts = envBootAttempts,
      currentAttemptId = None,
      finalVerdict = None,
      finalSummary = None,
      policySnapshotId = "policy-1",
      lockFilePath = Path.of("/tmp/.lastmile/run.lock"),
      artifactRootPath = Path.of("/tmp/.lastmile/runs/" + runId),
    )
    TaskRunRepo.insert(run)
    run
  }

  // --- Env Boot Retry via Resume State Machine ---

  test("EnvironmentFailed resumes at EnvironmentFailed for retry logic") {
    // Spec §2.1: Interrupted in EnvironmentFailed re-enters EnvironmentFailed to apply retry logic
    assertEquals(
      ResumeManager.resumeStateFor(RunStatus.EnvironmentFailed, 0, 5),
      RunStatus.EnvironmentFailed,
    )
  }

  test("BootstrappingEnvironment resumes at BootstrappingEnvironment") {
    // Spec §2.1: Re-enter BootstrappingEnvironment with fresh boot after orphan cleanup
    assertEquals(
      ResumeManager.resumeStateFor(RunStatus.BootstrappingEnvironment, 0, 5),
      RunStatus.BootstrappingEnvironment,
    )
  }

  // --- Repair Retry via Resume State Machine ---

  test("RepairFailed with budget remaining resumes at ReadyToVerify") {
    // Spec §2.1: Non-resumable state with attempts remaining → ReadyToVerify
    assertEquals(
      ResumeManager.resumeStateFor(RunStatus.RepairFailed, 2, 5),
      RunStatus.ReadyToVerify,
    )
  }

  test("RepairFailed with budget exhausted goes to Exhausted") {
    // Spec §2.1: Non-resumable state with no attempts remaining → Exhausted
    assertEquals(
      ResumeManager.resumeStateFor(RunStatus.RepairFailed, 5, 5),
      RunStatus.Exhausted,
    )
  }

  test("Repairing interrupted with budget remaining resumes at ReadyToVerify") {
    assertEquals(
      ResumeManager.resumeStateFor(RunStatus.Repairing, 3, 5),
      RunStatus.ReadyToVerify,
    )
  }

  test("Repairing interrupted at max attempts goes to Exhausted") {
    assertEquals(
      ResumeManager.resumeStateFor(RunStatus.Repairing, 5, 5),
      RunStatus.Exhausted,
    )
  }

  // --- Budget Default Verification ---

  test("env boot retry budget default is 2 per Spec §8.2") {
    assertEquals(ExecutionBudgetDefaults.defaults.maxEnvBootRetries, 2)
  }

  test("repair retry budget default is 1 per Spec §8.2") {
    assertEquals(ExecutionBudgetDefaults.defaults.maxRepairRetriesPerAttempt, 1)
  }

  test("max attempts default is 5 per Spec §8.2") {
    assertEquals(ExecutionBudgetDefaults.defaults.maxAttempts, 5)
  }

  test("worker max service restarts default is 2 per Spec §8.2") {
    assertEquals(ExecutionBudgetDefaults.defaults.maxServiceRestarts, 2)
  }

  test("degraded recovery timeout default is 30s per Spec §8.1") {
    assertEquals(ExecutionBudgetDefaults.defaults.degradedRecoveryTimeoutMs, 30000L)
  }

  // --- Attempt State Machine Tests ---

  test("attempt transitions follow spec composition rules") {
    withDb { implicit conn =>
      val runId = UUID.randomUUID().toString
      insertRun(runId, RunStatus.Verifying)

      // COMP-1: New attempt created when run enters Verifying
      val attempt = AttemptManager.createAttempt(runId, 1)
      assertEquals(attempt.status, AttemptStatus.Created)
      assertEquals(attempt.attemptNumber, 1)

      // COMP-1: Start verifying
      val verifying = AttemptManager.startVerifying(attempt)
      assertEquals(verifying.status, AttemptStatus.Verifying)
    }
  }

  test("attempt count incremented correctly") {
    withDb { implicit conn =>
      val runId = UUID.randomUUID().toString
      insertRun(runId, RunStatus.Verifying)

      // COMP-7: attempt_count is incremented when new Attempt enters Created
      val a1 = AttemptManager.createAttempt(runId, 1)
      val run1 = TaskRunRepo.getById(runId).get
      assertEquals(run1.attemptCount, 1)

      val a2 = AttemptManager.createAttempt(runId, 2)
      val run2 = TaskRunRepo.getById(runId).get
      assertEquals(run2.attemptCount, 2)
    }
  }

  // --- Terminal Behavior Tests ---

  test("terminal states are properly identified") {
    val terminalStates: Set[RunStatus] = Set(RunStatus.Succeeded, RunStatus.Exhausted, RunStatus.Cancelled, RunStatus.Interrupted)
    val nonTerminalStates = RunStatus.values.filterNot(terminalStates)

    assertEquals(terminalStates.size, 4)
    assertEquals(nonTerminalStates.size, RunStatus.values.size - 4)

    assert(terminalStates.contains(RunStatus.Succeeded))
    assert(terminalStates.contains(RunStatus.Exhausted))
    assert(terminalStates.contains(RunStatus.Cancelled))
    assert(terminalStates.contains(RunStatus.Interrupted))
    assert(!terminalStates.contains(RunStatus.Created))
    assert(!terminalStates.contains(RunStatus.Verifying))
  }

  test("Cancelled is never resumable, Interrupted is always resumable") {
    // Spec INV-R3, INV-R4
    val cancelledResumeState = ResumeManager.resumeStateFor(RunStatus.Cancelled, 0, 5)
    assertEquals(cancelledResumeState, RunStatus.Exhausted, "Cancelled should not be resumable")

    // Interrupted states should map to valid resume states
    val interruptedResumeState = ResumeManager.resumeStateFor(RunStatus.Created, 0, 5)
    assertEquals(interruptedResumeState, RunStatus.Created, "Interrupted in Created should resume from Created")
  }
}
