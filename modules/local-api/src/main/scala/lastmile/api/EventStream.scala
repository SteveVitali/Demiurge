package lastmile.api

import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import lastmile.model.SystemEvent
import lastmile.model.JsonCodecs._
import io.circe.syntax._

// Phase 7: In-memory event bus for SSE streaming — Spec §14.5
// Events are published and streamed to connected clients.
// No replay of historical events before client connect.
object EventStream {

  // Listener callback type: receives SSE-formatted event string
  type EventListener = String => Unit

  // Per-run listener lists
  private val listeners = new ConcurrentHashMap[String, CopyOnWriteArrayList[EventListener]]()

  // Track whether a run has ended (for closing connections)
  private val endedRuns = ConcurrentHashMap.newKeySet[String]()

  def publish(event: SystemEvent): Unit = {
    val runListeners = listeners.get(event.runId)
    if (runListeners != null) {
      val sseData = s"data: ${event.asJson.noSpaces}\n\n"
      runListeners.forEach(listener => {
        try { listener(sseData) } catch { case _: Exception => }
      })
    }
  }

  def subscribe(runId: String, listener: EventListener): Unit = {
    listeners.computeIfAbsent(runId, _ => new CopyOnWriteArrayList[EventListener]())
    listeners.get(runId).add(listener)
  }

  def unsubscribe(runId: String, listener: EventListener): Unit = {
    val runListeners = listeners.get(runId)
    if (runListeners != null) {
      runListeners.remove(listener)
      if (runListeners.isEmpty) listeners.remove(runId)
    }
  }

  def markRunEnded(runId: String): Unit = {
    endedRuns.add(runId)
  }

  def isRunEnded(runId: String): Boolean = {
    endedRuns.contains(runId)
  }

  def cleanup(runId: String): Unit = {
    listeners.remove(runId)
    endedRuns.remove(runId)
  }
}
