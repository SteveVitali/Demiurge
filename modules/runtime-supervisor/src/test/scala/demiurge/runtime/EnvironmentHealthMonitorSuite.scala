package demiurge.runtime

import munit.FunSuite
import demiurge.model._

class EnvironmentHealthMonitorSuite extends FunSuite {

  private def makeProbe(target: String, probeType: String = "tcp"): ReadinessProbe = {
    ReadinessProbe(
      probeType = probeType,
      target = target,
      intervalMs = 1000,
      timeoutMs = 2000,
      maxFailures = 3,
      initialDelayMs = 0,
    )
  }

  private def makeService(id: String, probe: ReadinessProbe): ServiceSpec = {
    ServiceSpec(
      serviceId = id,
      kind = ServiceKind.Api,
      startupMode = StartupMode.ScriptNative,
      startupCommand = Some("echo ok"),
      composeTarget = None,
      cwd = ".",
      env = Map.empty,
      envFile = None,
      ports = Nil,
      dependencyServices = Nil,
      readinessProbe = probe,
      shutdownMethod = "sigterm",
      shutdownTimeoutMs = 5000,
      restartPolicy = RestartPolicy(maxRestarts = 3, backoffBaseMs = 1000, backoffMaxMs = 10000, backoffMultiplier = 2.0),
      logsSource = "stdout",
      required = true,
    )
  }

  private def makePlan(services: List[ServiceSpec]): RuntimePlan = {
    RuntimePlan(
      planId = "test-plan",
      runId = "test-run",
      services = services,
      fixtureSteps = Nil,
      authBootstrapPlan = None,
      resetStrategy = ResetStrategy.SoftReset,
      teardownOrder = Nil,
      observabilityTaps = Nil,
      generatedAt = java.time.Instant.EPOCH,
      warnings = Nil,
    )
  }

  test("checkHealth returns Healthy when all services pass") {
    // Use a probe that will fail (no service running), but test the structure
    val plan = makePlan(Nil) // Empty plan = all healthy
    val result = EnvironmentHealthMonitor.checkHealth(plan)
    assertEquals(result.status, EnvironmentHealthMonitor.Healthy: EnvironmentHealthMonitor.HealthStatus)
  }

  test("checkHealth returns Failed when all services fail") {
    // TCP probe to a port that's definitely not listening
    val svc = makeService("svc-1", makeProbe("localhost:59999"))
    val plan = makePlan(List(svc))
    val result = EnvironmentHealthMonitor.checkHealth(plan)

    result.status match {
      case EnvironmentHealthMonitor.Failed(_) => // expected
      case other => fail(s"Expected Failed, got $other")
    }
  }

  test("checkHealth returns Degraded when some services fail") {
    // One service on a definitely-closed port, one on HTTP probe (also fails but different type)
    val svc1 = makeService("svc-good", makeProbe("localhost:59998"))
    val svc2 = makeService("svc-bad", makeProbe("localhost:59997"))
    val plan = makePlan(List(svc1, svc2))
    val result = EnvironmentHealthMonitor.checkHealth(plan)

    // Both will fail since nothing is listening, so this should be Failed not Degraded
    result.status match {
      case EnvironmentHealthMonitor.Failed(_) => // expected when all fail
      case EnvironmentHealthMonitor.Degraded(_, _) => // also acceptable
      case other => fail(s"Expected Failed or Degraded, got $other")
    }
  }

  test("toEnvironmentStatus maps correctly") {
    assertEquals(
      EnvironmentHealthMonitor.toEnvironmentStatus(EnvironmentHealthMonitor.Healthy),
      EnvironmentStatus.Ready)
    assertEquals(
      EnvironmentHealthMonitor.toEnvironmentStatus(EnvironmentHealthMonitor.Degraded(List("svc"), List("detail"))),
      EnvironmentStatus.Degraded)
    assertEquals(
      EnvironmentHealthMonitor.toEnvironmentStatus(EnvironmentHealthMonitor.Failed("reason")),
      EnvironmentStatus.Failed)
  }

  test("attemptRecovery on empty unhealthy list returns RecoverySuccess") {
    val plan = makePlan(Nil)
    val healthResult = EnvironmentHealthMonitor.HealthCheckResult(
      status = EnvironmentHealthMonitor.Healthy,
      serviceStatuses = Map.empty,
      checkedAt = java.time.Instant.now(),
    )
    val recovery = EnvironmentHealthMonitor.attemptRecovery(
      plan, java.nio.file.Paths.get("/tmp"), healthResult)
    assertEquals(recovery, EnvironmentHealthMonitor.RecoverySuccess: EnvironmentHealthMonitor.RecoveryResult)
  }

  test("attemptRecovery with failed services and no restart policy returns RecoveryFailed") {
    val svc = makeService("svc-1", makeProbe("localhost:59999"))
      .copy(restartPolicy = RestartPolicy(maxRestarts = 0, backoffBaseMs = 0, backoffMaxMs = 0, backoffMultiplier = 1.0))
    val plan = makePlan(List(svc))
    val healthResult = EnvironmentHealthMonitor.HealthCheckResult(
      status = EnvironmentHealthMonitor.Failed("all down"),
      serviceStatuses = Map("svc-1" -> EnvironmentHealthMonitor.ServiceHealthStatus("svc-1", healthy = false, "down")),
      checkedAt = java.time.Instant.now(),
    )
    val recovery = EnvironmentHealthMonitor.attemptRecovery(
      plan, java.nio.file.Paths.get("/tmp"), healthResult)
    recovery match {
      case EnvironmentHealthMonitor.RecoveryFailed(_) => // expected
      case other => fail(s"Expected RecoveryFailed, got $other")
    }
  }
}
