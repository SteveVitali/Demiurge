package lastmile.orchestrator

import munit.FunSuite
import java.nio.file.{Files, Path}
import lastmile.model._
import lastmile.persistence._

class RunOrchestratorSuite extends FunSuite with TestFixtures {

  test("creates TaskRun and persists Created") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "orch-test-001"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val loaded = TaskRunRepo.getById(runId)
        assert(loaded.isDefined, "TaskRun should be persisted")
        assertEquals(loaded.get.status, RunStatus.Created)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("transitions through minimal path to Exhausted") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "orch-test-002"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor)

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assert(finalRun.endedAt.isDefined, "endedAt should be set for terminal state")

        // Verify persisted state matches
        val persisted = TaskRunRepo.getById(runId)
        assert(persisted.isDefined)
        assertEquals(persisted.get.status, RunStatus.Exhausted)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("persists each transition in order and inserts corresponding events") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "orch-test-003"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor)

        // Verify events were inserted for each transition
        val events = EventRepo.listByRunId(runId, limit = 100)
        val transitionEvents = events.filter(_.eventType == "state_transition")

        // Should have 7 transitions (Phase 3 path):
        // Created → InspectingRepo
        // InspectingRepo → CompilingRequirements
        // CompilingRequirements → PlanningEnvironment
        // PlanningEnvironment → BootstrappingEnvironment
        // BootstrappingEnvironment → SeedingFixtures
        // SeedingFixtures → ReadyToVerify
        // ReadyToVerify → Exhausted
        assertEquals(transitionEvents.size, 7, s"Expected 7 transition events, got ${transitionEvents.size}")

        // Verify order
        val expectedTransitions = List(
          ("Created", "InspectingRepo"),
          ("InspectingRepo", "CompilingRequirements"),
          ("CompilingRequirements", "PlanningEnvironment"),
          ("PlanningEnvironment", "BootstrappingEnvironment"),
          ("BootstrappingEnvironment", "SeedingFixtures"),
          ("SeedingFixtures", "ReadyToVerify"),
          ("ReadyToVerify", "Exhausted"),
        )

        transitionEvents.zip(expectedTransitions).foreach { case (event, (from, to)) =>
          assertEquals(event.correlationFields.get("from_status"), Some(from),
            s"Expected from_status=$from")
          assertEquals(event.correlationFields.get("to_status"), Some(to),
            s"Expected to_status=$to")
        }

        // Verify events are in chronological order
        transitionEvents.sliding(2).foreach {
          case List(e1, e2) =>
            assert(!e2.timestamp.isBefore(e1.timestamp),
              s"Events should be in chronological order: ${e1.timestamp} should be before ${e2.timestamp}")
          case _ => // single element, ok
        }
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("persists worktreePath on run") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "orch-test-004"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val loaded = TaskRunRepo.getById(runId)
        assert(loaded.isDefined)
        assertEquals(loaded.get.worktreePath, worktreePath)
        assert(Files.exists(worktreePath), "Worktree should exist on disk")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("runs through environment states to ReadyToVerify terminal placeholder") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "orch-test-006"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor)

        assertEquals(finalRun.status, RunStatus.Exhausted)
        assert(finalRun.finalSummary.exists(_.contains("Phase 3 completed")),
          s"Summary should mention Phase 3: ${finalRun.finalSummary}")

        // Verify we passed through ReadyToVerify by checking events
        val events = EventRepo.listByRunId(runId, limit = 100)
        val transitionEvents = events.filter(_.eventType == "state_transition")
        val toStatuses = transitionEvents.flatMap(_.correlationFields.get("to_status"))
        assert(toStatuses.contains("ReadyToVerify"),
          s"Should have transitioned through ReadyToVerify: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("persists repo inspection report, runtime plan, and runtime snapshot") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "orch-test-007"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor)

        // Verify inspection report was persisted
        val report = RepoInspectionReportRepo.getByRunId(runId)
        assert(report.isDefined, "Inspection report should be persisted")
        assertEquals(report.get.runId, runId)

        // Verify runtime plan was persisted
        val plan = RuntimePlanRepo.getByRunId(runId)
        assert(plan.isDefined, "Runtime plan should be persisted")
        assertEquals(plan.get.runId, runId)

        // Verify runtime snapshot was persisted
        val snapshots = RuntimeSnapshotRepo.getByRunId(runId)
        assert(snapshots.nonEmpty, "Runtime snapshot should be persisted")
        assertEquals(snapshots.head.runId, runId)
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("persists state transitions before side effects") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "orch-test-005"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        // Track side-effect execution order relative to DB state
        var sideEffectDbStates = List.empty[(String, RunStatus)]

        // Use a custom inspector that checks DB state when side effect runs
        val checkingInspector = new lastmile.inspector.RepoInspector {
          def inspect(rid: String, root: Path, changed: Option[List[String]]): RepoInspectionReport = {
            // At this point, the DB should already have InspectingRepo persisted
            val dbRun = TaskRunRepo.getById(rid)(conn)
            sideEffectDbStates = sideEffectDbStates :+ ("inspect", dbRun.get.status)
            StubRepoInspector.inspect(rid, root, changed)
          }
        }

        val checkingCompiler = new lastmile.compiler.RequirementCompiler {
          def compile(rid: String, inspection: RepoInspectionReport, taskText: String): RequirementGraph = {
            val dbRun = TaskRunRepo.getById(rid)(conn)
            sideEffectDbStates = sideEffectDbStates :+ ("compile", dbRun.get.status)
            StubRequirementCompiler.compile(rid, inspection, taskText)
          }
        }

        val checkingPlanner = new lastmile.planner.EnvironmentPlanner {
          def plan(rid: String, inspection: RepoInspectionReport, requirements: RequirementGraph): RuntimePlan = {
            val dbRun = TaskRunRepo.getById(rid)(conn)
            sideEffectDbStates = sideEffectDbStates :+ ("plan", dbRun.get.status)
            StubEnvironmentPlanner.plan(rid, inspection, requirements)
          }
        }

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        RunOrchestrator.execute(ctx, checkingInspector, checkingCompiler, checkingPlanner, StubRuntimeSupervisor)

        // Verify: each side effect saw its target state already persisted in DB
        assertEquals(sideEffectDbStates.size, 3)
        assertEquals(sideEffectDbStates(0), ("inspect", RunStatus.InspectingRepo))
        assertEquals(sideEffectDbStates(1), ("compile", RunStatus.CompilingRequirements))
        assertEquals(sideEffectDbStates(2), ("plan", RunStatus.PlanningEnvironment))
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }
}
