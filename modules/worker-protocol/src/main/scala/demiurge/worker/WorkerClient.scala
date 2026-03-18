package demiurge.worker

import java.io._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}

import io.circe.Json

// Spec §10.1: WorkerClient — sends JSON-RPC requests over stdio, correlates responses by id.
// Strictly serial: one active request at a time (worker is serial).
// Handles notifications from worker (progress events).
class WorkerClient(
  stdin:  OutputStream,
  stdout: InputStream,
  stderr: InputStream,
) {
  private val nextId = new AtomicLong(1)
  private val responseQueue = new LinkedBlockingQueue[Either[JsonRpcNotification, JsonRpcResponse]]()
  private val stderrLines = new java.util.concurrent.CopyOnWriteArrayList[String]()
  @volatile private var readerRunning = false
  @volatile private var crashed = false
  private var notificationHandler: JsonRpcNotification => Unit = _ => ()

  private val reader = new BufferedReader(new InputStreamReader(stdout, "UTF-8"))
  private val writer = new BufferedWriter(new OutputStreamWriter(stdin, "UTF-8"))

  // Spec §10.1: Start background reader threads for stdout and stderr
  def start(): Unit = {
    readerRunning = true

    // stdout reader — parses JSON-RPC responses/notifications
    val stdoutThread = new Thread(() => {
      try {
        var line = reader.readLine()
        while (line != null && readerRunning) {
          JsonRpc.parseResponse(line) match {
            case Right(msg) =>
              msg match {
                case Left(notification) =>
                  try { notificationHandler(notification) } catch { case _: Exception => }
                  responseQueue.offer(Left(notification))
                case Right(response) =>
                  responseQueue.offer(Right(response))
              }
            case Left(_) => // Ignore unparseable lines
          }
          line = reader.readLine()
        }
      } catch {
        case _: IOException if !readerRunning => // Expected on shutdown
        case e: Exception =>
          crashed = true
      }
      readerRunning = false
    }, "worker-stdout-reader")
    stdoutThread.setDaemon(true)
    stdoutThread.start()

    // stderr reader — captures diagnostic logs
    val stderrThread = new Thread(() => {
      val errReader = new BufferedReader(new InputStreamReader(stderr, "UTF-8"))
      try {
        var line = errReader.readLine()
        while (line != null) {
          stderrLines.add(line)
          line = errReader.readLine()
        }
      } catch {
        case _: IOException => // Expected on shutdown
      }
    }, "worker-stderr-reader")
    stderrThread.setDaemon(true)
    stderrThread.start()
  }

  def setNotificationHandler(handler: JsonRpcNotification => Unit): Unit = {
    this.notificationHandler = handler
  }

  // Spec §10.1: Send a JSON-RPC request and wait for the corresponding response
  def sendRequest(method: String, params: Json = Json.obj(), timeoutMs: Long = 60000): Either[String, Json] = {
    if (crashed) return Left("Worker process has crashed")

    val id = nextId.getAndIncrement()
    val request = JsonRpcRequest(id, method, params)

    try {
      synchronized {
        writer.write(request.toJson)
        writer.newLine()
        writer.flush()
      }
    } catch {
      case e: IOException =>
        crashed = true
        return Left(s"Failed to send request: ${e.getMessage}")
    }

    // Wait for response with matching id — drain notifications
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val remaining = deadline - System.currentTimeMillis()
      if (remaining <= 0) return Left(s"Request timed out: $method (${timeoutMs}ms)")

      val msg = responseQueue.poll(remaining.min(1000), TimeUnit.MILLISECONDS)
      if (msg != null) {
        msg match {
          case Left(_) => // Notification — already handled, continue waiting
          case Right(response) =>
            if (response.id.contains(id)) {
              return response.error match {
                case Some(err) => Left(s"JSON-RPC error ${err.code}: ${err.message}")
                case None => Right(response.result.getOrElse(Json.Null))
              }
            } else {
              // Response for a different request (shouldn't happen in serial mode)
              // Put it back
              responseQueue.offer(msg)
            }
        }
      }
    }
    Left(s"Request timed out: $method (${timeoutMs}ms)")
  }

  def hasCrashed: Boolean = crashed

  def getStderrLog: String = {
    val sb = new StringBuilder()
    stderrLines.forEach(line => sb.append(line).append('\n'))
    sb.toString()
  }

  def close(): Unit = {
    readerRunning = false
    try { writer.close() } catch { case _: Exception => }
    try { reader.close() } catch { case _: Exception => }
  }
}
