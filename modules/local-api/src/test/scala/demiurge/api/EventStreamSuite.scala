package demiurge.api

import munit.FunSuite
import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import io.circe.Json
import demiurge.model._

// Phase 7: SSE event stream tests — Spec §14.5
class EventStreamSuite extends FunSuite {

  override def afterEach(context: AfterEach): Unit = {
    EventStream.cleanup("test-run-1")
    EventStream.cleanup("test-run-2")
  }

  private def makeEvent(runId: String, message: String): SystemEvent = SystemEvent(
    eventId = java.util.UUID.randomUUID().toString,
    runId = runId,
    attemptNumber = Some(1),
    eventType = "test",
    component = "test",
    severity = "info",
    timestamp = Instant.now(),
    correlationFields = Map.empty,
    payload = Map.empty,
    humanMessage = message,
  )

  test("event stream emits live events") {
    val latch = new CountDownLatch(1)
    val received = new AtomicReference[String]("")

    EventStream.subscribe("test-run-1", (data: String) => {
      received.set(data)
      latch.countDown()
    })

    val event = makeEvent("test-run-1", "Hello live")
    EventStream.publish(event)

    assert(latch.await(2, TimeUnit.SECONDS), "Listener should have received event")
    assert(received.get().contains("Hello live"))
    assert(received.get().startsWith("data: "))
  }

  test("event stream does not replay pre-connect history") {
    // Publish event BEFORE subscribing
    val event1 = makeEvent("test-run-2", "Before subscribe")
    EventStream.publish(event1)

    val latch = new CountDownLatch(1)
    val received = new AtomicReference[String]("")

    // Subscribe AFTER event was published
    EventStream.subscribe("test-run-2", (data: String) => {
      received.set(data)
      latch.countDown()
    })

    // Should NOT have received the pre-connect event
    assert(!latch.await(500, TimeUnit.MILLISECONDS), "Should not receive pre-connect events")
    assertEquals(received.get(), "")

    // Now publish a new event — should be received
    val event2 = makeEvent("test-run-2", "After subscribe")
    EventStream.publish(event2)

    assert(latch.await(2, TimeUnit.SECONDS), "Should receive post-connect event")
    assert(received.get().contains("After subscribe"))
  }

  test("stream closes when run ends") {
    EventStream.markRunEnded("test-run-1")
    assert(EventStream.isRunEnded("test-run-1"))

    // After cleanup, should no longer be marked as ended
    EventStream.cleanup("test-run-1")
    assert(!EventStream.isRunEnded("test-run-1"))
  }

  test("unsubscribe removes listener") {
    val received = new AtomicReference[String]("")
    val listener: EventStream.EventListener = (data: String) => received.set(data)

    EventStream.subscribe("test-run-1", listener)
    EventStream.unsubscribe("test-run-1", listener)

    EventStream.publish(makeEvent("test-run-1", "should not receive"))
    Thread.sleep(200)
    assertEquals(received.get(), "")
  }

  test("multiple listeners receive same event") {
    val latch = new CountDownLatch(2)
    val received1 = new AtomicReference[String]("")
    val received2 = new AtomicReference[String]("")

    EventStream.subscribe("test-run-1", (data: String) => { received1.set(data); latch.countDown() })
    EventStream.subscribe("test-run-1", (data: String) => { received2.set(data); latch.countDown() })

    EventStream.publish(makeEvent("test-run-1", "broadcast"))

    assert(latch.await(2, TimeUnit.SECONDS))
    assert(received1.get().contains("broadcast"))
    assert(received2.get().contains("broadcast"))
  }
}
