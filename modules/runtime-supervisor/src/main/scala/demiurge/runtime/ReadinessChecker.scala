package demiurge.runtime

import java.net.{HttpURLConnection, InetSocketAddress, Socket, URL}
import demiurge.model.ReadinessProbe

// Spec §8: Readiness probe execution for Phase 3.
// Supports: http, tcp, exec, log_contains
object ReadinessChecker {

  sealed trait ProbeResult
  case object ProbeSuccess extends ProbeResult
  case class ProbeFailure(reason: String) extends ProbeResult
  case class ProbeTimeout(elapsedMs: Long) extends ProbeResult

  /** Execute a single probe check. */
  def checkOnce(probe: ReadinessProbe): ProbeResult = {
    probe.probeType.toLowerCase match {
      case "http" => checkHttp(probe.target, probe.timeoutMs)
      case "tcp" => checkTcp(probe.target, probe.timeoutMs)
      case "exec" => checkExec(probe.target, probe.timeoutMs)
      case "log_contains" => ProbeFailure("log_contains requires log lines — use checkLogContains")
      case other => ProbeFailure(s"Unknown probe type: $other")
    }
  }

  /** Poll until healthy or timeout. Returns true if healthy within the deadline. */
  def waitUntilReady(probe: ReadinessProbe, logLines: => List[String] = Nil): Boolean = {
    val deadline = System.currentTimeMillis() + probe.timeoutMs
    Thread.sleep(math.max(0, probe.initialDelayMs).toLong)

    var failures = 0
    while (System.currentTimeMillis() < deadline && failures < probe.maxFailures) {
      val result = probe.probeType.toLowerCase match {
        case "log_contains" => checkLogContains(probe.target, logLines)
        case _ => checkOnce(probe)
      }

      result match {
        case ProbeSuccess => return true
        case f =>
          if (failures == 0 || failures % 5 == 0) {
            System.err.println(s"[readiness] ${probe.target} attempt ${failures + 1}: $f")
          }
          failures += 1
          val sleepMs = math.min(probe.intervalMs.toLong, deadline - System.currentTimeMillis())
          if (sleepMs > 0) Thread.sleep(sleepMs)
      }
    }
    System.err.println(s"[readiness] ${probe.target} FAILED after $failures attempts (deadline exceeded: ${System.currentTimeMillis() >= deadline})")
    false
  }

  /** HTTP readiness: GET the target URL, accept 2xx as success.
   *  Tries the original target first; if it fails with ConnectException and the host
   *  is "localhost", retries with 127.0.0.1 to handle IPv6/IPv4 mismatch (e.g. Vite
   *  binds to ::1 but Java resolves localhost to 127.0.0.1 or vice versa).
   */
  def checkHttp(target: String, timeoutMs: Int): ProbeResult = {
    val result = checkHttpOnce(target, timeoutMs)
    result match {
      case ProbeFailure(reason) if reason.contains("Connection refused") && target.contains("://localhost") =>
        // Retry with explicit IPv4 loopback
        val ipv4Target = target.replace("://localhost", "://127.0.0.1")
        checkHttpOnce(ipv4Target, timeoutMs) match {
          case s: ProbeSuccess.type => s
          case _ =>
            // Also try IPv6 loopback
            val ipv6Target = target.replace("://localhost", "://[::1]")
            checkHttpOnce(ipv6Target, timeoutMs)
        }
      case other => other
    }
  }

  private def checkHttpOnce(target: String, timeoutMs: Int): ProbeResult = {
    try {
      val url = new URL(target)
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      try {
        conn.setRequestMethod("GET")
        conn.setConnectTimeout(math.min(timeoutMs, 5000))
        conn.setReadTimeout(math.min(timeoutMs, 5000))
        conn.setInstanceFollowRedirects(true)
        val code = conn.getResponseCode
        if (code >= 200 && code < 300) ProbeSuccess
        else ProbeFailure(s"HTTP $code from $target")
      } finally {
        conn.disconnect()
      }
    } catch {
      case e: Exception => ProbeFailure(s"HTTP probe failed: ${e.getMessage}")
    }
  }

  /** TCP readiness: connect to host:port. */
  def checkTcp(target: String, timeoutMs: Int): ProbeResult = {
    try {
      val parts = target.split(":")
      if (parts.length != 2) return ProbeFailure(s"Invalid TCP target: $target (expected host:port)")
      val host = parts(0)
      val port = parts(1).toInt
      val socket = new Socket()
      try {
        socket.connect(new InetSocketAddress(host, port), math.min(timeoutMs, 5000))
        ProbeSuccess
      } finally {
        socket.close()
      }
    } catch {
      case e: Exception => ProbeFailure(s"TCP probe failed: ${e.getMessage}")
    }
  }

  /** Exec readiness: run command, exit 0 = success. */
  def checkExec(command: String, timeoutMs: Int): ProbeResult = {
    try {
      val process = new ProcessBuilder("sh", "-c", command)
        .redirectErrorStream(true)
        .start()
      val completed = process.waitFor(timeoutMs.toLong, java.util.concurrent.TimeUnit.MILLISECONDS)
      if (!completed) {
        process.destroyForcibly()
        ProbeTimeout(timeoutMs.toLong)
      } else if (process.exitValue() == 0) {
        ProbeSuccess
      } else {
        ProbeFailure(s"Exec probe exited with code ${process.exitValue()}")
      }
    } catch {
      case e: Exception => ProbeFailure(s"Exec probe failed: ${e.getMessage}")
    }
  }

  /** log_contains readiness: check if any log line contains the target string. */
  def checkLogContains(target: String, logLines: List[String]): ProbeResult = {
    if (logLines.exists(_.contains(target))) ProbeSuccess
    else ProbeFailure(s"Log does not yet contain: $target")
  }
}
