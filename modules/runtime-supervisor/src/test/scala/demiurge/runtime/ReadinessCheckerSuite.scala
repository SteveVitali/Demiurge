package demiurge.runtime

import munit.FunSuite
import java.net.ServerSocket
import demiurge.model.ReadinessProbe

class ReadinessCheckerSuite extends FunSuite {

  test("HTTP readiness succeeds against a tiny local test server") {
    // Start a minimal HTTP server
    val server = com.sun.net.httpserver.HttpServer.create(
      new java.net.InetSocketAddress(0), 0)
    val port = server.getAddress.getPort
    server.createContext("/health", exchange => {
      val response = """{"status":"ok"}"""
      exchange.sendResponseHeaders(200, response.length)
      val os = exchange.getResponseBody
      os.write(response.getBytes)
      os.close()
    })
    server.start()

    try {
      val result = ReadinessChecker.checkHttp(s"http://localhost:$port/health", 5000)
      assertEquals(result, ReadinessChecker.ProbeSuccess)
    } finally {
      server.stop(0)
    }
  }

  test("HTTP readiness fails for non-2xx response") {
    val server = com.sun.net.httpserver.HttpServer.create(
      new java.net.InetSocketAddress(0), 0)
    val port = server.getAddress.getPort
    server.createContext("/health", exchange => {
      exchange.sendResponseHeaders(500, 0)
      exchange.getResponseBody.close()
    })
    server.start()

    try {
      val result = ReadinessChecker.checkHttp(s"http://localhost:$port/health", 5000)
      assert(result.isInstanceOf[ReadinessChecker.ProbeFailure])
    } finally {
      server.stop(0)
    }
  }

  test("TCP readiness succeeds against an open socket") {
    val serverSocket = new ServerSocket(0)
    val port = serverSocket.getLocalPort

    try {
      val result = ReadinessChecker.checkTcp(s"localhost:$port", 5000)
      assertEquals(result, ReadinessChecker.ProbeSuccess)
    } finally {
      serverSocket.close()
    }
  }

  test("TCP readiness fails for closed port") {
    // Use a port that's very likely not in use
    val result = ReadinessChecker.checkTcp("localhost:19999", 1000)
    assert(result.isInstanceOf[ReadinessChecker.ProbeFailure])
  }

  test("log_contains readiness succeeds on matching log output") {
    val logLines = List("Starting server...", "Server listening on port 3000", "Ready")
    val result = ReadinessChecker.checkLogContains("listening on port", logLines)
    assertEquals(result, ReadinessChecker.ProbeSuccess)
  }

  test("log_contains readiness fails when no match") {
    val logLines = List("Starting server...", "Initializing...")
    val result = ReadinessChecker.checkLogContains("listening on port", logLines)
    assert(result.isInstanceOf[ReadinessChecker.ProbeFailure])
  }

  test("exec readiness succeeds for exit code 0") {
    val result = ReadinessChecker.checkExec("true", 5000)
    assertEquals(result, ReadinessChecker.ProbeSuccess)
  }

  test("exec readiness fails for non-zero exit") {
    val result = ReadinessChecker.checkExec("false", 5000)
    assert(result.isInstanceOf[ReadinessChecker.ProbeFailure])
  }

  test("readiness times out correctly") {
    val probe = ReadinessProbe(
      probeType = "tcp",
      target = "localhost:19999",
      intervalMs = 100,
      timeoutMs = 500,
      maxFailures = 100,
      initialDelayMs = 0,
    )
    val start = System.currentTimeMillis()
    val result = ReadinessChecker.waitUntilReady(probe)
    val elapsed = System.currentTimeMillis() - start

    assert(!result, "Should not be ready")
    assert(elapsed >= 400 && elapsed < 3000,
      s"Should timeout in ~500ms, took ${elapsed}ms")
  }

  test("waitUntilReady succeeds for available TCP port") {
    val serverSocket = new ServerSocket(0)
    val port = serverSocket.getLocalPort

    try {
      val probe = ReadinessProbe(
        probeType = "tcp",
        target = s"localhost:$port",
        intervalMs = 100,
        timeoutMs = 5000,
        maxFailures = 10,
        initialDelayMs = 0,
      )
      val result = ReadinessChecker.waitUntilReady(probe)
      assert(result, "Should be ready")
    } finally {
      serverSocket.close()
    }
  }
}
