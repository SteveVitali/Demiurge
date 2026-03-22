package demiurge.api

import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import scala.collection.mutable
import io.circe._
import io.circe.syntax._

// Desktop Phase 3 — §7.2: Per-service log ring buffers + WebSocket broadcast.
// Thread-safe: multiple service processes write, multiple WS clients read.
object LogStreamManager {

  private val DEFAULT_BUFFER_SIZE = 10000

  // Per-service ring buffer of log lines
  private class RingBuffer(maxSize: Int) {
    private val buffer = new mutable.ArrayDeque[String](maxSize)
    private var _totalCount: Long = 0L

    def append(line: String): Unit = synchronized {
      if (buffer.size >= maxSize) {
        buffer.removeHead()
      }
      buffer.addOne(line)
      _totalCount += 1
    }

    def getLines(count: Int): List[String] = synchronized {
      val n = math.min(count, buffer.size)
      buffer.takeRight(n).toList
    }

    def totalCount: Long = synchronized { _totalCount }

    def clear(): Unit = synchronized {
      buffer.clear()
      _totalCount = 0
    }
  }

  // Ring buffers keyed by serviceId
  private val buffers = new ConcurrentHashMap[String, RingBuffer]()

  // Log subscriptions: clientId → Set[serviceId]
  private val logSubscriptions = new ConcurrentHashMap[String, java.util.Set[String]]()

  // Agent subscriptions: clientId → runId
  private val agentSubscriptions = new ConcurrentHashMap[String, String]()

  // Set by WebSocketServer to enable broadcasting to specific clients
  @volatile private var sendToClientFn: Option[(String, String) => Unit] = None

  def setSendToClientFunction(fn: (String, String) => Unit): Unit = {
    sendToClientFn = Some(fn)
  }

  // --- Log line management ---

  def appendLine(serviceId: String, line: String): Unit = {
    val buf = buffers.computeIfAbsent(serviceId, _ => new RingBuffer(DEFAULT_BUFFER_SIZE))
    buf.append(line)

    // Broadcast to subscribed clients
    val msg = Json.obj(
      "type"      -> "log_line".asJson,
      "serviceId" -> serviceId.asJson,
      "line"      -> line.asJson,
      "timestamp" -> Instant.now().toString.asJson,
    ).noSpaces

    broadcastToLogSubscribers(serviceId, msg)
  }

  def getBackfill(serviceId: String, lines: Int): List[String] = {
    Option(buffers.get(serviceId)).map(_.getLines(lines)).getOrElse(Nil)
  }

  def clearBuffer(serviceId: String): Unit = {
    Option(buffers.get(serviceId)).foreach(_.clear())
  }

  // --- Subscription management ---

  def subscribeToLogs(clientId: String, serviceId: String, backfillLines: Int): Unit = {
    val subs = logSubscriptions.computeIfAbsent(clientId,
      _ => java.util.Collections.newSetFromMap(new ConcurrentHashMap[String, java.lang.Boolean]()))
    subs.add(serviceId)

    // Send backfill
    val lines = getBackfill(serviceId, backfillLines)
    val backfillMsg = Json.obj(
      "type"      -> "log_backfill".asJson,
      "serviceId" -> serviceId.asJson,
      "lines"     -> lines.asJson,
    ).noSpaces

    sendToClientFn.foreach(fn => fn(clientId, backfillMsg))
  }

  def unsubscribeFromLogs(clientId: String, serviceId: String): Unit = {
    Option(logSubscriptions.get(clientId)).foreach(_.remove(serviceId))
  }

  def subscribeToAgent(clientId: String, runId: String): Unit = {
    agentSubscriptions.put(clientId, runId)
  }

  def unsubscribeFromAgent(clientId: String): Unit = {
    agentSubscriptions.remove(clientId)
  }

  def removeClient(clientId: String): Unit = {
    logSubscriptions.remove(clientId)
    agentSubscriptions.remove(clientId)
  }

  // --- Agent message broadcasting ---

  def broadcastAgentMessage(messageType: String, data: Json): Unit = {
    val msg = Json.obj(
      "type"        -> "agent_message".asJson,
      "messageType" -> messageType.asJson,
      "data"        -> data,
      "timestamp"   -> Instant.now().toString.asJson,
    ).noSpaces

    // Send to all clients with agent subscriptions
    val iter = agentSubscriptions.entrySet().iterator()
    while (iter.hasNext) {
      val entry = iter.next()
      sendToClientFn.foreach(fn => fn(entry.getKey, msg))
    }
  }

  // --- Internal broadcast helpers ---

  private def broadcastToLogSubscribers(serviceId: String, msg: String): Unit = {
    val iter = logSubscriptions.entrySet().iterator()
    while (iter.hasNext) {
      val entry = iter.next()
      if (entry.getValue.contains(serviceId)) {
        sendToClientFn.foreach(fn => fn(entry.getKey, msg))
      }
    }
  }

  // --- Cleanup ---

  def reset(): Unit = {
    buffers.clear()
    logSubscriptions.clear()
    agentSubscriptions.clear()
  }
}
