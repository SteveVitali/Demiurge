package demiurge.api

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import io.circe._
import io.circe.parser.{decode => jsonDecode}
import io.circe.syntax._

// Desktop Phase 3 — §7.2: WebSocket server on :19441 using com.sun.net.httpserver
// thin WebSocket implementation (RFC 6455) without external library dependency.
// Handles: subscribe_logs, unsubscribe_logs, subscribe_agent, unsubscribe_agent, ping/pong.
// Broadcasts: log_backfill, log_line, agent_message, heartbeat.
object WebSocketServer {

  import java.io.{InputStream, OutputStream}
  import java.net.ServerSocket
  import java.security.MessageDigest
  import java.util.Base64

  case class WsClient(
    id: String,
    output: OutputStream,
    @volatile var open: Boolean = true,
  )

  private val clients = new ConcurrentHashMap[String, WsClient]()
  @volatile private var serverSocket: ServerSocket = _
  @volatile private var running = false
  @volatile private var acceptThread: Thread = _
  @volatile private var heartbeatThread: Thread = _

  def start(port: Int = 19441): Unit = {
    if (running) return
    running = true

    // Wire broadcast functions into LogStreamManager
    LogStreamManager.setSendToClientFunction((clientId, msg) => sendToClient(clientId, msg))

    serverSocket = new ServerSocket()
    serverSocket.setReuseAddress(true)
    serverSocket.bind(new InetSocketAddress("127.0.0.1", port))

    acceptThread = new Thread(() => acceptLoop(), "ws-accept")
    acceptThread.setDaemon(true)
    acceptThread.start()

    heartbeatThread = new Thread(() => heartbeatLoop(), "ws-heartbeat")
    heartbeatThread.setDaemon(true)
    heartbeatThread.start()

    System.err.println(s"[ws-server] WebSocket server started on :$port")
  }

  def stop(): Unit = {
    running = false
    try { if (serverSocket != null) serverSocket.close() } catch { case _: Exception => }
    val iter = clients.values().iterator()
    while (iter.hasNext) {
      val c = iter.next()
      c.open = false
      try { c.output.close() } catch { case _: Exception => }
    }
    clients.clear()
    LogStreamManager.reset()
    System.err.println("[ws-server] WebSocket server stopped")
  }

  def isRunning: Boolean = running

  private def acceptLoop(): Unit = {
    while (running) {
      try {
        val socket = serverSocket.accept()
        val t = new Thread(() => handleConnection(socket), s"ws-client-${UUID.randomUUID().toString.take(8)}")
        t.setDaemon(true)
        t.start()
      } catch {
        case _: Exception if !running => // shutdown
        case e: Exception =>
          System.err.println(s"[ws-server] Accept error: ${e.getMessage}")
      }
    }
  }

  private def handleConnection(socket: java.net.Socket): Unit = {
    val clientId = UUID.randomUUID().toString
    try {
      val in = socket.getInputStream
      val out = socket.getOutputStream

      // Read HTTP upgrade request
      val request = readHttpRequest(in)
      val wsKey = extractHeader(request, "Sec-WebSocket-Key")

      if (wsKey.isEmpty) {
        socket.close()
        return
      }

      // Send upgrade response
      val acceptKey = computeAcceptKey(wsKey)
      val response = s"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $acceptKey\r\n\r\n"
      out.write(response.getBytes("UTF-8"))
      out.flush()

      val client = WsClient(clientId, out)
      clients.put(clientId, client)

      // Read frames
      while (client.open && running) {
        try {
          val frame = readFrame(in)
          frame match {
            case Some(TextFrame(text)) => handleMessage(clientId, text)
            case Some(CloseFrame) =>
              client.open = false
              sendCloseFrame(out)
            case Some(PingFrame(data)) => sendPongFrame(out, data)
            case None => client.open = false
            case _ => // ignore
          }
        } catch {
          case _: Exception => client.open = false
        }
      }
    } catch {
      case _: Exception => // connection closed
    } finally {
      clients.remove(clientId)
      LogStreamManager.removeClient(clientId)
      try { socket.close() } catch { case _: Exception => }
    }
  }

  private def handleMessage(clientId: String, text: String): Unit = {
    jsonDecode[Json](text) match {
      case Left(_) => // ignore malformed
      case Right(json) =>
        val msgType = json.hcursor.downField("type").as[String].getOrElse("")
        msgType match {
          case "subscribe_logs" =>
            val runId = json.hcursor.downField("runId").as[String].getOrElse("")
            val serviceId = json.hcursor.downField("serviceId").as[String].getOrElse("")
            val lines = json.hcursor.downField("lines").as[Int].getOrElse(500)
            if (serviceId.nonEmpty) {
              LogStreamManager.subscribeToLogs(clientId, serviceId, lines)
            }

          case "unsubscribe_logs" =>
            val serviceId = json.hcursor.downField("serviceId").as[String].getOrElse("")
            if (serviceId.nonEmpty) {
              LogStreamManager.unsubscribeFromLogs(clientId, serviceId)
            }

          case "subscribe_agent" =>
            val runId = json.hcursor.downField("runId").as[String].getOrElse("")
            if (runId.nonEmpty) {
              LogStreamManager.subscribeToAgent(clientId, runId)
            }

          case "unsubscribe_agent" =>
            LogStreamManager.unsubscribeFromAgent(clientId)

          case "ping" =>
            val pong = Json.obj(
              "type" -> "pong".asJson,
              "timestamp" -> java.time.Instant.now().toString.asJson,
            ).noSpaces
            sendToClient(clientId, pong)

          case _ => // unknown message type — ignore
        }
    }
  }

  def sendToClient(clientId: String, message: String): Unit = {
    Option(clients.get(clientId)).foreach { client =>
      if (client.open) {
        try {
          val bytes = message.getBytes("UTF-8")
          writeFrame(client.output, bytes)
        } catch {
          case _: Exception =>
            client.open = false
            clients.remove(clientId)
            LogStreamManager.removeClient(clientId)
        }
      }
    }
  }

  def broadcastToAll(message: String): Unit = {
    val iter = clients.entrySet().iterator()
    while (iter.hasNext) {
      val entry = iter.next()
      sendToClient(entry.getKey, message)
    }
  }

  private def heartbeatLoop(): Unit = {
    while (running) {
      try {
        Thread.sleep(15000) // 15s heartbeat per §7.2
        if (running) {
          val msg = Json.obj(
            "type" -> "heartbeat".asJson,
            "timestamp" -> java.time.Instant.now().toString.asJson,
          ).noSpaces
          broadcastToAll(msg)
        }
      } catch {
        case _: InterruptedException => // shutdown
        case _: Exception => // ignore
      }
    }
  }

  // --- WebSocket frame encoding/decoding (RFC 6455) ---

  sealed trait WsFrame
  case class TextFrame(text: String) extends WsFrame
  case object CloseFrame extends WsFrame
  case class PingFrame(data: Array[Byte]) extends WsFrame
  case object PongFrame extends WsFrame

  private val MAX_FRAME_PAYLOAD = 16 * 1024 * 1024 // 16 MB

  private def readFrame(in: InputStream): Option[WsFrame] = {
    val b0 = in.read()
    if (b0 == -1) return None
    val b1 = in.read()
    if (b1 == -1) return None

    val opcode = b0 & 0x0F
    val masked = (b1 & 0x80) != 0
    var payloadLen = (b1 & 0x7F).toLong

    if (payloadLen == 126) {
      val h = in.read()
      val l = in.read()
      if (h == -1 || l == -1) return None
      payloadLen = ((h & 0xFF) << 8) | (l & 0xFF)
    } else if (payloadLen == 127) {
      var len = 0L
      for (_ <- 0 until 8) {
        val b = in.read()
        if (b == -1) return None
        len = (len << 8) | (b & 0xFF)
      }
      payloadLen = len
    }

    if (payloadLen > MAX_FRAME_PAYLOAD) return None

    val maskKey = if (masked) {
      val mk = new Array[Byte](4)
      val read = in.readNBytes(mk, 0, 4)
      if (read < 4) return None
      mk
    } else new Array[Byte](0)

    val payload = new Array[Byte](payloadLen.toInt)
    var totalRead = 0
    while (totalRead < payloadLen) {
      val n = in.read(payload, totalRead, payloadLen.toInt - totalRead)
      if (n == -1) return None
      totalRead += n
    }

    if (masked) {
      for (i <- payload.indices) {
        payload(i) = (payload(i) ^ maskKey(i % 4)).toByte
      }
    }

    opcode match {
      case 0x1 => Some(TextFrame(new String(payload, "UTF-8")))
      case 0x8 => Some(CloseFrame)
      case 0x9 => Some(PingFrame(payload))
      case 0xA => Some(PongFrame)
      case _   => None
    }
  }

  private def writeFrame(out: OutputStream, payload: Array[Byte], opcode: Int = 0x1): Unit = {
    out.synchronized {
      out.write(0x80 | opcode) // FIN + opcode
      val len = payload.length
      if (len < 126) {
        out.write(len)
      } else if (len < 65536) {
        out.write(126)
        out.write((len >> 8) & 0xFF)
        out.write(len & 0xFF)
      } else {
        out.write(127)
        for (i <- 7 to 0 by -1) {
          out.write((len >> (8 * i)) & 0xFF)
        }
      }
      out.write(payload)
      out.flush()
    }
  }

  private def sendCloseFrame(out: OutputStream): Unit = {
    try { writeFrame(out, Array.empty, 0x8) } catch { case _: Exception => }
  }

  private def sendPongFrame(out: OutputStream, data: Array[Byte]): Unit = {
    try { writeFrame(out, data, 0xA) } catch { case _: Exception => }
  }

  // --- HTTP handshake helpers ---

  private val MAX_HTTP_HEADER_SIZE = 8192

  private def readHttpRequest(in: InputStream): String = {
    val sb = new StringBuilder
    var prev = 0
    var curr = 0
    var crlfCount = 0
    while (crlfCount < 2) {
      curr = in.read()
      if (curr == -1) return sb.toString()
      sb.append(curr.toChar)
      if (sb.length > MAX_HTTP_HEADER_SIZE) return sb.toString()
      if (prev == '\r' && curr == '\n') crlfCount += 1
      else if (curr != '\r') crlfCount = 0
      prev = curr
    }
    sb.toString()
  }

  private def extractHeader(request: String, name: String): String = {
    val lower = name.toLowerCase
    request.split("\r\n").find(_.toLowerCase.startsWith(lower + ":")).map(_.substring(name.length + 1).trim).getOrElse("")
  }

  private def computeAcceptKey(wsKey: String): String = {
    val concat = wsKey + "258EAFA5-E914-47DA-95CA-5AB4FE80FD75"
    val sha1 = MessageDigest.getInstance("SHA-1").digest(concat.getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(sha1)
  }
}
