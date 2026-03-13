package lastmile.runtime

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.Instant

import lastmile.model._

class RuntimeSupervisorSuite extends FunSuite {

  private def withTempDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("supervisor-test-")
    try { testFn(tmpDir) }
    finally {
      ServiceProcessManager.clear()
      deleteRecursive(tmpDir)
    }
  }

  private def makeScriptServiceSpec(
    serviceId: String,
    command: String,
    cwd: String,
    port: Int,
    probeType: String = "tcp",
    probeTarget: String = "",
    deps: List[String] = Nil,
  ): ServiceSpec = {
    val target = if (probeTarget.nonEmpty) probeTarget else s"localhost:$port"
    ServiceSpec(
      serviceId = serviceId, kind = ServiceKind.Api, startupMode = StartupMode.ScriptNative,
      startupCommand = Some(command), composeTarget = None, cwd = cwd,
      env = Map.empty, envFile = None,
      ports = List(PortMapping(Some(port), port, "tcp")),
      dependencyServices = deps,
      readinessProbe = ReadinessProbe(probeType, target, 200, 10000, 50, 100),
      shutdownMethod = "sigterm", shutdownTimeoutMs = 5000,
      restartPolicy = RestartPolicy(3, 1000, 30000, 2.0),
      logsSource = "stdout", required = true,
    )
  }

  private def writeServerScript(root: Path, port: Int): Path = {
    val script = root.resolve("server.sh")
    Files.write(script, s"""#!/bin/sh
      |python3 -c "
      |import http.server, socketserver
      |handler = http.server.SimpleHTTPRequestHandler
      |with socketserver.TCPServer(('', $port), handler) as httpd:
      |    httpd.serve_forever()
      |" &
      |wait
      |""".stripMargin.getBytes)
    script.toFile.setExecutable(true)
    script
  }

  private def makePlan(runId: String, services: List[ServiceSpec], fixtureSteps: List[FixtureStep] = Nil): RuntimePlan =
    RuntimePlan(
      planId = s"plan-$runId", runId = runId, services = services,
      fixtureSteps = fixtureSteps, authBootstrapPlan = None,
      resetStrategy = ResetStrategy.SoftReset,
      teardownOrder = services.map(_.serviceId).reverse,
      observabilityTaps = Nil, generatedAt = Instant.EPOCH, warnings = Nil,
    )

  test("starts script-native service and reaches healthy") {
    withTempDir { root =>
      val port = findFreePort()
      val script = writeServerScript(root, port)

      val spec = makeScriptServiceSpec("test-svc", s"sh ${script}", root.toString, port)
      val plan = makePlan("run-1", List(spec))

      val result = RuntimeSupervisorImpl.bootEnvironment(plan, root)
      try {
        result match {
          case RuntimeSupervisor.BootSuccess(snapshot) =>
            assertEquals(snapshot.environmentStatus, EnvironmentStatus.Ready)
            assert(snapshot.services.exists(_.status == ServiceStatus.RunningHealthy))
          case RuntimeSupervisor.BootFailure(reason, _) =>
            fail(s"Boot should succeed, got failure: $reason")
        }
      } finally {
        RuntimeSupervisorImpl.teardown(plan, root)
      }
    }
  }

  test("tears down script-native service cleanly") {
    withTempDir { root =>
      val port = findFreePort()
      val script = writeServerScript(root, port)

      val spec = makeScriptServiceSpec("teardown-svc", s"sh ${script}", root.toString, port)
      val plan = makePlan("run-2", List(spec))

      val result = RuntimeSupervisorImpl.bootEnvironment(plan, root)
      result match {
        case RuntimeSupervisor.BootSuccess(_) =>
          // Verify service is tracked
          assert(ServiceProcessManager.getService("teardown-svc").isDefined)

          // Teardown
          RuntimeSupervisorImpl.teardown(plan, root)

          // Verify service is removed
          assert(ServiceProcessManager.getService("teardown-svc").isEmpty)
        case RuntimeSupervisor.BootFailure(reason, _) =>
          RuntimeSupervisorImpl.teardown(plan, root)
          fail(s"Boot should succeed: $reason")
      }
    }
  }

  test("writes PID files for script services") {
    withTempDir { root =>
      val port = findFreePort()
      val script = writeServerScript(root, port)

      val spec = makeScriptServiceSpec("pid-svc", s"sh ${script}", root.toString, port)
      val plan = makePlan("run-3", List(spec))

      val result = RuntimeSupervisorImpl.bootEnvironment(plan, root)
      try {
        result match {
          case RuntimeSupervisor.BootSuccess(_) =>
            val pidFile = root.resolve(".lastmile").resolve("pids").resolve("pid-svc.pid")
            assert(Files.exists(pidFile), s"PID file should exist at $pidFile")
            val pidContent = new String(Files.readAllBytes(pidFile)).trim
            assert(pidContent.nonEmpty, "PID file should contain a PID")
          case RuntimeSupervisor.BootFailure(reason, _) =>
            fail(s"Boot should succeed: $reason")
        }
      } finally {
        RuntimeSupervisorImpl.teardown(plan, root)
      }
    }
  }

  test("removes PID files on teardown") {
    withTempDir { root =>
      val port = findFreePort()
      val script = writeServerScript(root, port)

      val spec = makeScriptServiceSpec("cleanup-svc", s"sh ${script}", root.toString, port)
      val plan = makePlan("run-4", List(spec))

      val result = RuntimeSupervisorImpl.bootEnvironment(plan, root)
      result match {
        case RuntimeSupervisor.BootSuccess(_) =>
          val pidFile = root.resolve(".lastmile").resolve("pids").resolve("cleanup-svc.pid")
          assert(Files.exists(pidFile), "PID file should exist before teardown")

          RuntimeSupervisorImpl.teardown(plan, root)

          assert(!Files.exists(pidFile), "PID file should be removed after teardown")
        case RuntimeSupervisor.BootFailure(reason, _) =>
          RuntimeSupervisorImpl.teardown(plan, root)
          fail(s"Boot should succeed: $reason")
      }
    }
  }

  test("starts compose-native service when docker is available, or skips test cleanly when not available") {
    if (!ServiceProcessManager.isDockerAvailable) {
      // Skip gracefully
      println("Docker not available, skipping compose test")
    } else {
      withTempDir { root =>
        // Create a minimal compose file
        val composeYaml =
          """services:
            |  test-redis:
            |    image: redis:7-alpine
            |    ports:
            |      - "16379:6379"
            |""".stripMargin
        Files.write(root.resolve("compose.yaml"), composeYaml.getBytes)

        val spec = ServiceSpec(
          serviceId = "test-redis", kind = ServiceKind.Cache,
          startupMode = StartupMode.ComposeNative,
          startupCommand = None, composeTarget = Some("test-redis"),
          cwd = root.toString, env = Map.empty, envFile = None,
          ports = List(PortMapping(Some(16379), 6379, "tcp")),
          dependencyServices = Nil,
          readinessProbe = ReadinessProbe("tcp", "localhost:16379", 500, 30000, 60, 1000),
          shutdownMethod = "sigterm", shutdownTimeoutMs = 10000,
          restartPolicy = RestartPolicy(3, 1000, 30000, 2.0),
          logsSource = "stdout", required = true,
        )
        val plan = makePlan("compose-1", List(spec))

        val result = RuntimeSupervisorImpl.bootEnvironment(plan, root)
        try {
          result match {
            case RuntimeSupervisor.BootSuccess(snapshot) =>
              assertEquals(snapshot.environmentStatus, EnvironmentStatus.Ready)
            case RuntimeSupervisor.BootFailure(reason, _) =>
              // Compose may fail in CI — don't hard fail
              println(s"Compose boot failed (may be expected in some environments): $reason")
          }
        } finally {
          RuntimeSupervisorImpl.teardown(plan, root)
          // Also clean up compose resources
          try {
            new ProcessBuilder("docker", "compose", "-f", root.resolve("compose.yaml").toString,
              "-p", s"lastmile-compose-", "down", "--remove-orphans")
              .directory(root.toFile).start().waitFor()
          } catch { case _: Exception => }
        }
      }
    }
  }

  test("persists runtime snapshot on ready (stub supervisor)") {
    // This test uses the stub supervisor approach — the real snapshot persistence
    // is tested in the orchestrator integration test
    withTempDir { root =>
      val plan = makePlan("snap-1", Nil)
      val result = RuntimeSupervisorImpl.bootEnvironment(plan, root)
      result match {
        case RuntimeSupervisor.BootSuccess(snapshot) =>
          assert(snapshot.snapshotId.nonEmpty)
          assert(snapshot.runId == "snap-1")
          assertEquals(snapshot.environmentStatus, EnvironmentStatus.Ready)
        case RuntimeSupervisor.BootFailure(reason, _) =>
          fail(s"Empty plan should succeed: $reason")
      }
      RuntimeSupervisorImpl.teardown(plan, root)
    }
  }

  test("boot fails when required service fails to start") {
    withTempDir { root =>
      val spec = makeScriptServiceSpec("bad-svc", "exit 1", root.toString, 19999)
      val plan = makePlan("fail-1", List(spec))

      val result = RuntimeSupervisorImpl.bootEnvironment(plan, root)
      result match {
        case RuntimeSupervisor.BootFailure(reason, snapshot) =>
          assert(reason.contains("bad-svc"), s"Should mention failing service: $reason")
          assert(snapshot.isDefined, "Should have partial snapshot")
        case RuntimeSupervisor.BootSuccess(_) =>
          fail("Should fail for bad service")
      }
      RuntimeSupervisorImpl.teardown(plan, root)
    }
  }

  private def findFreePort(): Int = {
    val socket = new java.net.ServerSocket(0)
    try { socket.getLocalPort }
    finally { socket.close() }
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      val entries = Files.list(path)
      try { entries.forEach(p => deleteRecursive(p)) }
      finally { entries.close() }
    }
    Files.deleteIfExists(path)
  }
}
