package demiurge.verification

import munit.FunSuite
import java.nio.file.Files
import java.time.Duration

class VerifierExecutorSuite extends FunSuite {

  test("ExecVerifier passes when exit code matches") {
    val v = ExecVerifier(
      id = "exec-1",
      requirementId = "req-1",
      command = List("true"),
      expectedExit = 0,
      timeout = Duration.ofSeconds(5),
      maxRetries = 0,
    )
    val result = VerifierExecutor.execute(v)
    assertEquals(result, VerifierOutcome.Passed)
  }

  test("ExecVerifier fails when exit code differs") {
    val v = ExecVerifier(
      id = "exec-2",
      requirementId = "req-2",
      command = List("false"),
      expectedExit = 0,
      timeout = Duration.ofSeconds(5),
      maxRetries = 0,
    )
    val result = VerifierExecutor.execute(v)
    assert(result.isInstanceOf[VerifierOutcome.Failed])
  }

  test("ExecVerifier with empty command fails") {
    val v = ExecVerifier(
      id = "exec-3",
      requirementId = "req-3",
      command = Nil,
      expectedExit = 0,
      timeout = Duration.ofSeconds(5),
      maxRetries = 0,
    )
    val result = VerifierExecutor.execute(v)
    assert(result.isInstanceOf[VerifierOutcome.Failed] || result.isInstanceOf[VerifierOutcome.Error])
  }

  test("LogContainsVerifier passes when forbidden pattern is absent") {
    val tmpFile = Files.createTempFile("log-test-", ".log")
    try {
      Files.write(tmpFile, "INFO: all good\nDEBUG: trace\n".getBytes("UTF-8"))
      val v = LogContainsVerifier(
        id = "log-1",
        requirementId = "req-1",
        logPath = tmpFile.toString,
        pattern = "ERROR",
        forbidden = true,
        timeout = Duration.ofSeconds(5),
        maxRetries = 0,
      )
      val result = VerifierExecutor.execute(v)
      assertEquals(result, VerifierOutcome.Passed)
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  test("LogContainsVerifier fails when forbidden pattern is present") {
    val tmpFile = Files.createTempFile("log-test-", ".log")
    try {
      Files.write(tmpFile, "INFO: starting\nERROR: something broke\n".getBytes("UTF-8"))
      val v = LogContainsVerifier(
        id = "log-2",
        requirementId = "req-2",
        logPath = tmpFile.toString,
        pattern = "ERROR",
        forbidden = true,
        timeout = Duration.ofSeconds(5),
        maxRetries = 0,
      )
      val result = VerifierExecutor.execute(v)
      assert(result.isInstanceOf[VerifierOutcome.Failed])
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  test("LogContainsVerifier passes when expected pattern is present") {
    val tmpFile = Files.createTempFile("log-test-", ".log")
    try {
      Files.write(tmpFile, "Server started on port 3000\n".getBytes("UTF-8"))
      val v = LogContainsVerifier(
        id = "log-3",
        requirementId = "req-3",
        logPath = tmpFile.toString,
        pattern = "Server started",
        forbidden = false,
        timeout = Duration.ofSeconds(5),
        maxRetries = 0,
      )
      val result = VerifierExecutor.execute(v)
      assertEquals(result, VerifierOutcome.Passed)
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  test("LogContainsVerifier fails for missing file") {
    val v = LogContainsVerifier(
      id = "log-4",
      requirementId = "req-4",
      logPath = "/nonexistent/path.log",
      pattern = "anything",
      forbidden = false,
      timeout = Duration.ofSeconds(5),
      maxRetries = 0,
    )
    val result = VerifierExecutor.execute(v)
    assert(result.isInstanceOf[VerifierOutcome.Failed])
  }

  test("StateVerifier always passes (stub)") {
    val v = StateVerifier(
      id = "state-1",
      requirementId = "req-1",
      timeout = Duration.ofSeconds(5),
      maxRetries = 0,
    )
    val result = VerifierExecutor.execute(v)
    assertEquals(result, VerifierOutcome.Passed)
  }

  test("TcpVerifier fails on invalid port") {
    val v = TcpVerifier(
      id = "tcp-1",
      requirementId = "req-1",
      host = "localhost",
      port = 0,
      timeout = Duration.ofSeconds(2),
      maxRetries = 0,
    )
    val result = VerifierExecutor.execute(v)
    assert(result.isInstanceOf[VerifierOutcome.Failed])
  }

  test("HttpVerifier fails on connection refused") {
    val v = HttpVerifier(
      id = "http-1",
      requirementId = "req-1",
      method = "GET",
      url = "http://localhost:19999/nonexistent",
      headers = Map.empty,
      expectedStatus = 200,
      timeout = Duration.ofSeconds(2),
      maxRetries = 0,
    )
    val result = VerifierExecutor.execute(v)
    assert(result != VerifierOutcome.Passed, s"Expected failure, got $result")
  }

  test("ExecVerifier retries on failure when maxRetries > 0") {
    // This test verifies retry logic by running a command that always fails
    val v = ExecVerifier(
      id = "exec-retry",
      requirementId = "req-retry",
      command = List("false"),
      expectedExit = 0,
      timeout = Duration.ofSeconds(5),
      maxRetries = 1,
    )
    val result = VerifierExecutor.execute(v)
    // Should still fail since 'false' always returns 1
    assert(result.isInstanceOf[VerifierOutcome.Failed])
  }
}
