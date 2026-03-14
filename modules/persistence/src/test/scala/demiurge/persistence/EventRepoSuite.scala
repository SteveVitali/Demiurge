package demiurge.persistence

import munit.FunSuite
import java.nio.file.{Files, Paths}
import java.time.Instant
import io.circe.Json
import demiurge.model._

class EventRepoSuite extends FunSuite {

  private def withDb(testFn: java.sql.Connection => Unit): Unit = {
    val tmp = Files.createTempFile("demiurge-test-", ".db")
    Files.delete(tmp)
    val conn = Database.open(tmp)
    try {
      Migrator.migrate(conn)
      testFn(conn)
    } finally {
      conn.close()
      Files.deleteIfExists(tmp)
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-wal"))
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-shm"))
    }
  }

  private def makeEvent(id: String, runId: String, eventType: String, ts: String): SystemEvent =
    SystemEvent(
      eventId = id,
      runId = runId,
      attemptNumber = Some(1),
      eventType = eventType,
      component = "orchestrator",
      severity = "info",
      timestamp = Instant.parse(ts),
      correlationFields = Map("verifierId" -> "v-001"),
      payload = Map("key" -> Json.fromString("value")),
      humanMessage = s"Event $id",
    )

  test("EventRepo insert and query by runId") {
    withDb { implicit conn =>
      EventRepo.insert(makeEvent("evt-1", "run-001", "StateTransition", "2025-01-01T00:00:01Z"))
      EventRepo.insert(makeEvent("evt-2", "run-001", "VerifierStarted", "2025-01-01T00:00:02Z"))
      EventRepo.insert(makeEvent("evt-3", "run-001", "StateTransition", "2025-01-01T00:00:03Z"))

      val events = EventRepo.listByRunId("run-001")
      assertEquals(events.size, 3)
      // Verify order by timestamp ASC
      assertEquals(events.head.eventId, "evt-1")
      assertEquals(events.last.eventId, "evt-3")
      // Verify payload round-trip
      assertEquals(events.head.payload("key"), Json.fromString("value"))
      assertEquals(events.head.correlationFields("verifierId"), "v-001")
    }
  }

  test("EventRepo query by eventType") {
    withDb { implicit conn =>
      EventRepo.insert(makeEvent("evt-1", "run-001", "StateTransition", "2025-01-01T00:00:01Z"))
      EventRepo.insert(makeEvent("evt-2", "run-001", "VerifierStarted", "2025-01-01T00:00:02Z"))
      EventRepo.insert(makeEvent("evt-3", "run-001", "StateTransition", "2025-01-01T00:00:03Z"))

      val stateEvents = EventRepo.listByType("run-001", "StateTransition")
      assertEquals(stateEvents.size, 2)
      assert(stateEvents.forall(_.eventType == "StateTransition"))

      val verifierEvents = EventRepo.listByType("run-001", "VerifierStarted")
      assertEquals(verifierEvents.size, 1)
      assertEquals(verifierEvents.head.eventId, "evt-2")
    }
  }
}
