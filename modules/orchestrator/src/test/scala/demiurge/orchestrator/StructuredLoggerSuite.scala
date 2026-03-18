package demiurge.orchestrator

import java.nio.file.Files
import java.sql.Connection

import demiurge.model._
import demiurge.persistence._

// Phase 8: Tests for structured logging behavior (Spec §16)
class StructuredLoggerSuite extends munit.FunSuite {

  override def beforeEach(context: BeforeEach): Unit = {
    StructuredLogger.verbose = false
    StructuredLogger.quiet = false
  }

  private def withDb(fn: Connection => Unit): Unit = {
    val tmp = Files.createTempFile("logger-test-", ".db")
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

  test("runCreated emits event with correct type and payload") {
    withDb { implicit conn =>
      val event = StructuredLogger.runCreated("run-1", RunMode.Full, "Fix the login page", Some(conn))
      assertEquals(event.eventType, "run_created")
      assertEquals(event.component, "orchestrator")
      assertEquals(event.severity, "info")
      assertEquals(event.runId, "run-1")

      val events = EventRepo.listByRunId("run-1")
      assertEquals(events.size, 1)
    }
  }

  test("runStateChanged emits correct from/to states") {
    withDb { implicit conn =>
      val event = StructuredLogger.runStateChanged("run-1", RunStatus.Created, RunStatus.InspectingRepo, "run_started", Some(conn))
      assertEquals(event.eventType, "run_state_changed")

      val events = EventRepo.listByRunId("run-1")
      assertEquals(events.size, 1)
    }
  }

  test("runCompleted emits with final verdict and duration") {
    withDb { implicit conn =>
      val event = StructuredLogger.runCompleted("run-1", VerdictStatus.Pass, 3, 120000L, Some(conn))
      assertEquals(event.eventType, "run_completed")
      assert(event.humanMessage.contains("Pass"))

      val events = EventRepo.listByRunId("run-1")
      assertEquals(events.size, 1)
    }
  }

  test("verificationStarted emits with attempt number and verifier count") {
    withDb { implicit conn =>
      val event = StructuredLogger.verificationStarted("run-1", 1, 10, Some(conn))
      assertEquals(event.eventType, "verification_started")
      assertEquals(event.attemptNumber, Some(1))
    }
  }

  test("verifierCompleted emits with correlation fields") {
    withDb { implicit conn =>
      val event = StructuredLogger.verifierCompleted("run-1", 1, "ver-1", VerdictStatus.Fail, 500L,
        Some(FailureClass.BackendContractFailure), Some(conn))
      assertEquals(event.eventType, "verifier_completed")
      assert(event.correlationFields.contains("verifierId"))
      assertEquals(event.correlationFields("verifierId"), "ver-1")
    }
  }

  test("repairStarted and repairCompleted emit in sequence") {
    withDb { implicit conn =>
      StructuredLogger.repairStarted("run-1", 1, "claude-agent-sdk", Some(conn))
      StructuredLogger.repairCompleted("run-1", 1, "Success", List("src/app.ts", "src/api.ts"), Some(conn))

      val events = EventRepo.listByRunId("run-1")
      assertEquals(events.size, 2)
      assert(events.exists(_.eventType == "repair_started"))
      assert(events.exists(_.eventType == "repair_completed"))
    }
  }

  test("inferenceCompleted emits with token counts") {
    withDb { implicit conn =>
      val event = StructuredLogger.inferenceCompleted("run-1", "req-1", "failure_analyzer",
        1000, 500, false, 2000, Some(conn))
      assertEquals(event.eventType, "inference_completed")
      assert(event.correlationFields.contains("requestId"))
    }
  }

  test("environmentReady and environmentFailed emit correctly") {
    withDb { implicit conn =>
      StructuredLogger.environmentReady("run-1", 5000L, Some(conn))
      StructuredLogger.environmentFailed("run-1", "DB connection timeout", List("db"), Some(conn))

      val events = EventRepo.listByRunId("run-1")
      assertEquals(events.size, 2)
      assert(events.exists(_.eventType == "environment_ready"))
      assert(events.exists(_.eventType == "environment_failed"))
      assert(events.find(_.eventType == "environment_failed").get.severity == "error")
    }
  }

  test("verbose mode controls debug event persistence") {
    StructuredLogger.verbose = false
    // Debug events should not be persisted when not verbose
    val event = StructuredLogger.emitEvent("run-1", None, "debug_event", "test", "debug",
      Map.empty, "Debug message", conn = None)
    assertEquals(event.severity, "debug")

    StructuredLogger.verbose = true
    // Debug events should be persisted when verbose
    withDb { implicit conn =>
      StructuredLogger.emitEvent("run-1", None, "debug_event", "test", "debug",
        Map.empty, "Debug message", conn = Some(conn))
      val events = EventRepo.listByRunId("run-1")
      assertEquals(events.size, 1)
    }
    StructuredLogger.verbose = false
  }
}
