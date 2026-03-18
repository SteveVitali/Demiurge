package lastmile.worker

import munit.FunSuite
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.PosixFilePermission

import io.circe.Json

class WorkerProcessManagerSuite extends FunSuite {

  // Phase 6: Use python3 mock scripts and the `command` parameter to avoid requiring node in Bazel sandbox.

  private def createMockWorkerScript(tmpDir: Path): Path = {
    val script = tmpDir.resolve("mock_worker.py")
    val content =
      """#!/usr/bin/env python3
        |import sys, json
        |sys.stderr.write('[mock-worker] started\n')
        |sys.stderr.flush()
        |for line in sys.stdin:
        |    line = line.strip()
        |    if not line:
        |        continue
        |    try:
        |        msg = json.loads(line)
        |        mid = msg.get('id')
        |        method = msg.get('method', '')
        |        if method == 'initialize':
        |            resp = {'jsonrpc': '2.0', 'id': mid, 'result': {'browserVersion': 'mock-1.0', 'capabilities': {'browserFlow': True, 'apiRequest': True, 'authBootstrap': True, 'pageSnapshot': True}}}
        |        elif method == 'ping':
        |            resp = {'jsonrpc': '2.0', 'id': mid, 'result': {'pong': True}}
        |        elif method == 'shutdown':
        |            resp = {'jsonrpc': '2.0', 'id': mid, 'result': {'success': True}}
        |            sys.stdout.write(json.dumps(resp) + '\n')
        |            sys.stdout.flush()
        |            sys.exit(0)
        |        elif method == 'cancel':
        |            resp = {'jsonrpc': '2.0', 'id': mid, 'result': {'cancelled': True, 'taskId': None}}
        |        elif method == 'executeBrowserFlow':
        |            resp = {'jsonrpc': '2.0', 'id': mid, 'result': {'status': 'pass', 'observations': [], 'artifacts': [], 'durationMs': 10}}
        |        else:
        |            resp = {'jsonrpc': '2.0', 'id': mid, 'error': {'code': -32601, 'message': 'Method not found: ' + method}}
        |        sys.stdout.write(json.dumps(resp) + '\n')
        |        sys.stdout.flush()
        |    except Exception as e:
        |        resp = {'jsonrpc': '2.0', 'id': None, 'error': {'code': -32700, 'message': 'Parse error'}}
        |        sys.stdout.write(json.dumps(resp) + '\n')
        |        sys.stdout.flush()
        |""".stripMargin
    Files.write(script, content.getBytes("UTF-8"))
    script
  }

  private def createCrashingWorkerScript(tmpDir: Path): Path = {
    val script = tmpDir.resolve("crash_worker.py")
    val content = "#!/usr/bin/env python3\nimport sys\nsys.exit(1)\n"
    Files.write(script, content.getBytes("UTF-8"))
    script
  }

  private def mockMgr(script: Path, maxRestarts: Int = 3): WorkerProcessManager =
    new WorkerProcessManager(script, maxRestarts, command = Some(List("python3", script.toAbsolutePath.toString)))

  private def withTmpDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("worker-test-")
    try {
      testFn(tmpDir)
    } finally {
      Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    }
  }

  test("worker process spawns and initializes") {
    withTmpDir { tmpDir =>
      val script = createMockWorkerScript(tmpDir)
      val mgr = mockMgr(script)
      try {
        val result = mgr.initialize("/tmp/artifacts", "/tmp/worktree", "run-1")
        assert(result.isRight, s"Expected Right, got $result")
        result.foreach { init =>
          assertEquals(init.browserVersion, "mock-1.0")
          assert(init.capabilities("browserFlow"))
        }
        assert(mgr.isAlive)
        assert(mgr.isInitialized)
      } finally {
        mgr.shutdown()
      }
    }
  }

  test("ping/pong works") {
    withTmpDir { tmpDir =>
      val script = createMockWorkerScript(tmpDir)
      val mgr = mockMgr(script)
      try {
        mgr.initialize("/tmp/artifacts", "/tmp/worktree", "run-1")
        assert(mgr.ping())
      } finally {
        mgr.shutdown()
      }
    }
  }

  test("cancel works") {
    withTmpDir { tmpDir =>
      val script = createMockWorkerScript(tmpDir)
      val mgr = mockMgr(script)
      try {
        mgr.initialize("/tmp/artifacts", "/tmp/worktree", "run-1")
        val result = mgr.cancel()
        assert(result.isRight, s"Cancel should succeed, got $result")
      } finally {
        mgr.shutdown()
      }
    }
  }

  test("shutdown works") {
    withTmpDir { tmpDir =>
      val script = createMockWorkerScript(tmpDir)
      val mgr = mockMgr(script)
      mgr.initialize("/tmp/artifacts", "/tmp/worktree", "run-1")
      mgr.shutdown()
      Thread.sleep(200)
      assert(!mgr.isAlive || mgr.hasCrashed, "Worker should be dead after shutdown")
    }
  }

  test("worker crash is detected") {
    withTmpDir { tmpDir =>
      val script = createCrashingWorkerScript(tmpDir)
      val mgr = mockMgr(script, maxRestarts = 0)
      mgr.spawn()
      Thread.sleep(500)
      assert(mgr.hasCrashed, "Worker should be detected as crashed")
    }
  }

  test("executeBrowserFlow succeeds with mock worker") {
    withTmpDir { tmpDir =>
      val script = createMockWorkerScript(tmpDir)
      val mgr = mockMgr(script)
      try {
        mgr.initialize("/tmp/artifacts", "/tmp/worktree", "run-1")
        val params = WorkerMessages.executeBrowserFlowParams(
          taskId = "task-1",
          entryUrl = "http://localhost:3000",
        )
        val result = mgr.executeBrowserFlow(params)
        assert(result.isRight, s"Expected Right, got $result")
        result.foreach { r =>
          assertEquals(r.status, "pass")
        }
      } finally {
        mgr.shutdown()
      }
    }
  }

  test("method not found returns error") {
    withTmpDir { tmpDir =>
      val script = createMockWorkerScript(tmpDir)
      val mgr = mockMgr(script)
      try {
        mgr.initialize("/tmp/artifacts", "/tmp/worktree", "run-1")
        val result = mgr.capturePageSnapshot(
          WorkerMessages.capturePageSnapshotParams("task-1", "http://localhost:3000"),
        )
        assert(result.isLeft, "capturePageSnapshot should fail on mock worker")
      } finally {
        mgr.shutdown()
      }
    }
  }

  test("restart budget is enforced") {
    withTmpDir { tmpDir =>
      // Use the mock (non-crashing) worker so initialize succeeds quickly
      val script = createMockWorkerScript(tmpDir)
      val mgr = mockMgr(script, maxRestarts = 1)

      // Initialize once normally
      mgr.initialize("/tmp/artifacts", "/tmp/worktree", "run-1")
      // Shut it down to simulate crash
      mgr.shutdown()
      Thread.sleep(200)

      // First restart: within budget (restartCount goes to 1, maxRestarts is 1, 1 > 1 is false → allowed)
      val result1 = mgr.restartIfNeeded("/tmp/artifacts", "/tmp/worktree", "run-1")
      // result1 should succeed since mock worker responds to initialize
      mgr.shutdown()
      Thread.sleep(200)

      // Second restart: restartCount goes to 2, 2 > 1 → budget exhausted
      val result2 = mgr.restartIfNeeded("/tmp/artifacts", "/tmp/worktree", "run-1")
      assert(result2.isLeft, s"Should fail with budget exhausted, got $result2")
      assert(result2.left.exists(_.contains("budget exhausted")), s"Error message should mention budget: $result2")
    }
  }

  test("stderr log is captured") {
    withTmpDir { tmpDir =>
      val script = createMockWorkerScript(tmpDir)
      val mgr = mockMgr(script)
      try {
        mgr.initialize("/tmp/artifacts", "/tmp/worktree", "run-1")
        Thread.sleep(300)
        val log = mgr.getStderrLog
        assert(log.contains("[mock-worker] started"), s"Expected stderr to contain worker start message, got: $log")
      } finally {
        mgr.shutdown()
      }
    }
  }
}
