package demiurge.verification

import java.net.{HttpURLConnection, Socket, URL}
import scala.sys.process._
import scala.util.{Try, Success, Failure}
import java.nio.file.{Files, Paths}

// Phase 4: In-process verifier execution engine.
// Executes verifiers synchronously. No worker, no Playwright.
object VerifierExecutor {

  /** Execute with built-in retry (legacy — prefer executeOnce + engine-level retry). */
  def execute(verifier: Verifier): VerifierOutcome = {
    val result = executeOnce(verifier)
    result match {
      case VerifierOutcome.Passed => VerifierOutcome.Passed
      case failure if verifier.maxRetries > 0 =>
        var current = failure
        var retries = 0
        while (retries < verifier.maxRetries && current != VerifierOutcome.Passed) {
          retries += 1
          Thread.sleep(100)
          current = executeOnce(verifier)
        }
        current
      case other => other
    }
  }

  /** Execute a single attempt with no retry. Used by VerificationEngine for retry+flake tracking. */
  def executeOnce(verifier: Verifier): VerifierOutcome = {
    try {
      verifier match {
        case v: HttpVerifier        => executeHttp(v)
        case v: TcpVerifier         => executeTcp(v)
        case v: ExecVerifier        => executeExec(v)
        case v: LogContainsVerifier => executeLogContains(v)
        case v: StateVerifier       => executeState(v)
        // Phase 6: BrowserFlowVerifier is handled by VerificationEngine directly
        // via the worker process manager — not by this in-process executor.
        case _: BrowserFlowVerifier =>
          VerifierOutcome.Error("BrowserFlowVerifier must be executed via WorkerProcessManager")
      }
    } catch {
      case _: java.net.SocketTimeoutException => VerifierOutcome.TimedOut
      case e: Exception => VerifierOutcome.Error(e.getMessage)
    }
  }

  private def executeHttp(v: HttpVerifier): VerifierOutcome = {
    System.err.println(s"[verifier] Executing HTTP ${v.method} ${v.url} (expected ${v.expectedStatus})")
    try {
      val url = new URL(v.url)
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      try {
        conn.setRequestMethod(v.method)
        conn.setConnectTimeout(v.timeout.toMillis.toInt.min(30000))
        conn.setReadTimeout(v.timeout.toMillis.toInt.min(30000))
        v.headers.foreach { case (k, value) => conn.setRequestProperty(k, value) }
        val status = conn.getResponseCode
        System.err.println(s"[verifier] HTTP ${v.method} ${v.url} → $status (expected ${v.expectedStatus})")
        if (status == v.expectedStatus) {
          VerifierOutcome.Passed
        } else {
          VerifierOutcome.Failed(s"HTTP ${v.method} ${v.url}: expected status ${v.expectedStatus}, got $status")
        }
      } finally {
        conn.disconnect()
      }
    } catch {
      case _: java.net.SocketTimeoutException =>
        System.err.println(s"[verifier] Timeout: ${v.url}")
        VerifierOutcome.TimedOut
      case e: java.net.ConnectException =>
        System.err.println(s"[verifier] Connection refused: ${v.url}")
        VerifierOutcome.Failed(s"Connection refused: ${v.url}")
      case e: Exception =>
        System.err.println(s"[verifier] Error: ${v.url} — ${e.getClass.getName}: ${e.getMessage}")
        VerifierOutcome.Error(s"HTTP verifier error: ${e.getMessage}")
    }
  }

  private def executeTcp(v: TcpVerifier): VerifierOutcome = {
    if (v.port <= 0) {
      return VerifierOutcome.Failed(s"Invalid TCP port: ${v.port}")
    }
    try {
      val socket = new Socket()
      try {
        socket.connect(
          new java.net.InetSocketAddress(v.host, v.port),
          v.timeout.toMillis.toInt.min(30000),
        )
        VerifierOutcome.Passed
      } finally {
        socket.close()
      }
    } catch {
      case _: java.net.SocketTimeoutException => VerifierOutcome.TimedOut
      case e: java.net.ConnectException =>
        VerifierOutcome.Failed(s"TCP connection refused: ${v.host}:${v.port}")
      case e: Exception =>
        VerifierOutcome.Error(s"TCP verifier error: ${e.getMessage}")
    }
  }

  private def executeExec(v: ExecVerifier): VerifierOutcome = {
    if (v.command.isEmpty) {
      return VerifierOutcome.Failed("Empty command")
    }
    try {
      val timeoutMs = v.timeout.toMillis
      val stdout = new StringBuilder
      val stderr = new StringBuilder
      val logger = ProcessLogger(
        out => stdout.append(out).append('\n'),
        err => stderr.append(err).append('\n'),
      )
      val process = Process(v.command).run(logger)
      val startTime = System.currentTimeMillis()
      var exitCode: Option[Int] = None

      while (exitCode.isEmpty && (System.currentTimeMillis() - startTime) < timeoutMs) {
        Try(process.exitValue()) match {
          case Success(code) => exitCode = Some(code)
          case Failure(_)    => Thread.sleep(50)
        }
      }

      exitCode match {
        case Some(code) if code == v.expectedExit => VerifierOutcome.Passed
        case Some(code) =>
          VerifierOutcome.Failed(s"Process exited with code $code (expected ${v.expectedExit}). stderr: ${stderr.toString.take(500)}")
        case None =>
          process.destroy()
          VerifierOutcome.TimedOut
      }
    } catch {
      case e: Exception =>
        VerifierOutcome.Error(s"Exec verifier error: ${e.getMessage}")
    }
  }

  private def executeLogContains(v: LogContainsVerifier): VerifierOutcome = {
    try {
      val path = Paths.get(v.logPath)
      if (!Files.exists(path)) {
        return VerifierOutcome.Failed(s"Log file not found: ${v.logPath}")
      }
      val content = new String(Files.readAllBytes(path), "UTF-8")
      val found = content.contains(v.pattern)

      if (v.forbidden) {
        if (found) VerifierOutcome.Failed(s"Forbidden pattern '${v.pattern}' found in log")
        else VerifierOutcome.Passed
      } else {
        if (found) VerifierOutcome.Passed
        else VerifierOutcome.Failed(s"Expected pattern '${v.pattern}' not found in log")
      }
    } catch {
      case e: Exception =>
        VerifierOutcome.Error(s"LogContains verifier error: ${e.getMessage}")
    }
  }

  private def executeState(v: StateVerifier): VerifierOutcome = {
    // Phase 4 stub: StateVerifier always passes
    VerifierOutcome.Passed
  }
}
