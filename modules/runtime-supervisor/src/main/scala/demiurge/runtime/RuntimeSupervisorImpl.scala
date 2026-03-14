package demiurge.runtime

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

import demiurge.model._

// Spec §8: RuntimeSupervisorImpl for Phase 3.
// Orchestrates service startup in dependency order, readiness probes,
// fixture execution, and snapshot capture.
object RuntimeSupervisorImpl extends RuntimeSupervisor {

  private val composeFileNames = List(
    "compose.yaml", "compose.yml",
    "docker-compose.yaml", "docker-compose.yml",
  )

  override def bootEnvironment(plan: RuntimePlan, repoRoot: Path): RuntimeSupervisor.BootResult = {
    val pidDir = repoRoot.resolve(".demiurge").resolve("pids")
    Files.createDirectories(pidDir)

    val composeProjectName = s"demiurge-${plan.runId.take(8)}"
    val composePaths = detectComposePaths(repoRoot)
    val startTime = System.currentTimeMillis()

    val serviceSnapshots = scala.collection.mutable.Map[String, ServiceSnapshot]()
    val healthyServices = scala.collection.mutable.Set[String]()

    // Start services in dependency order (already topologically sorted in plan)
    for (spec <- plan.services) {
      // Check that dependencies are healthy before starting this service
      val unhealthyDeps = spec.dependencyServices.filterNot(healthyServices.contains)
      if (unhealthyDeps.nonEmpty && spec.required) {
        val snapshot = buildSnapshot(plan, serviceSnapshots.toMap, healthyServices.toSet, startTime)
        return RuntimeSupervisor.BootFailure(
          s"Service ${spec.serviceId}: dependencies not healthy: ${unhealthyDeps.mkString(", ")}",
          Some(snapshot),
        )
      }

      // Start the service
      val startResult = spec.startupMode match {
        case StartupMode.ScriptNative =>
          ServiceProcessManager.startScript(spec, pidDir)
        case StartupMode.ComposeNative =>
          ServiceProcessManager.startCompose(spec, composeProjectName, composePaths)
        case StartupMode.Hybrid =>
          // Try script first for Phase 3
          ServiceProcessManager.startScript(spec, pidDir)
        case _ =>
          Left(s"Service ${spec.serviceId}: unsupported startup mode ${spec.startupMode}")
      }

      startResult match {
        case Right(managed) =>
          serviceSnapshots(spec.serviceId) = ServiceSnapshot(
            serviceId = spec.serviceId,
            status = ServiceStatus.Starting,
            pid = managed.pid,
            containerId = managed.containerId,
            healthyAt = None,
            lastProbeResult = None,
            restartCount = 0,
            logTailLines = Nil,
          )

          // Run readiness check — pass log lines by-name so they're re-evaluated on each probe
          val ready = ReadinessChecker.waitUntilReady(
            spec.readinessProbe, ServiceProcessManager.getLogLines(spec.serviceId))

          if (ready) {
            val now = Instant.now()
            healthyServices += spec.serviceId
            serviceSnapshots(spec.serviceId) = serviceSnapshots(spec.serviceId).copy(
              status = ServiceStatus.RunningHealthy,
              healthyAt = Some(now),
              lastProbeResult = Some("healthy"),
              logTailLines = ServiceProcessManager.getLogLines(spec.serviceId).takeRight(10),
            )
          } else {
            serviceSnapshots(spec.serviceId) = serviceSnapshots(spec.serviceId).copy(
              status = ServiceStatus.Failed,
              lastProbeResult = Some("readiness check failed"),
              logTailLines = ServiceProcessManager.getLogLines(spec.serviceId).takeRight(10),
            )
            if (spec.required) {
              val snapshot = buildSnapshot(plan, serviceSnapshots.toMap, healthyServices.toSet, startTime)
              return RuntimeSupervisor.BootFailure(
                s"Service ${spec.serviceId}: readiness check failed",
                Some(snapshot),
              )
            }
          }

        case Left(error) =>
          serviceSnapshots(spec.serviceId) = ServiceSnapshot(
            serviceId = spec.serviceId,
            status = ServiceStatus.Failed,
            pid = None,
            containerId = None,
            healthyAt = None,
            lastProbeResult = Some(error),
            restartCount = 0,
            logTailLines = Nil,
          )
          if (spec.required) {
            val snapshot = buildSnapshot(plan, serviceSnapshots.toMap, healthyServices.toSet, startTime)
            return RuntimeSupervisor.BootFailure(error, Some(snapshot))
          }
      }
    }

    // Run fixture steps after all services are healthy
    if (plan.fixtureSteps.nonEmpty) {
      val fixtureResults = FixtureRunner.runAll(plan.fixtureSteps, healthyServices.toSet)
      val failures = fixtureResults.collect {
        case (id, FixtureRunner.FixtureFailure(_, reason)) => s"$id: $reason"
        case (id, FixtureRunner.FixtureTimeout(_, ms)) => s"$id: timed out after ${ms}ms"
      }
      if (failures.nonEmpty) {
        val snapshot = buildSnapshot(plan, serviceSnapshots.toMap, healthyServices.toSet, startTime)
        return RuntimeSupervisor.BootFailure(
          s"Fixture failures: ${failures.mkString("; ")}",
          Some(snapshot),
        )
      }
    }

    val snapshot = buildSnapshot(plan, serviceSnapshots.toMap, healthyServices.toSet, startTime)
    RuntimeSupervisor.BootSuccess(snapshot)
  }

  override def teardown(plan: RuntimePlan, repoRoot: Path): Unit = {
    val pidDir = repoRoot.resolve(".demiurge").resolve("pids")
    val composeProjectName = s"demiurge-${plan.runId.take(8)}"
    val composePaths = detectComposePaths(repoRoot)

    TeardownManager.teardownAll(plan, pidDir, composeProjectName, composePaths)
    TeardownManager.cleanupPidFiles(pidDir)
    ServiceProcessManager.clear()
  }

  private def buildSnapshot(
    plan: RuntimePlan,
    serviceSnapshots: Map[String, ServiceSnapshot],
    healthyServices: Set[String],
    startTime: Long,
  ): RuntimeSnapshot = {
    val allServices = plan.services.map { spec =>
      serviceSnapshots.getOrElse(spec.serviceId, ServiceSnapshot(
        serviceId = spec.serviceId,
        status = ServiceStatus.Pending,
        pid = None, containerId = None, healthyAt = None,
        lastProbeResult = None, restartCount = 0, logTailLines = Nil,
      ))
    }

    val envStatus = if (healthyServices.size == plan.services.size) EnvironmentStatus.Ready
      else if (healthyServices.nonEmpty) EnvironmentStatus.PartiallyHealthy
      else EnvironmentStatus.Failed

    val portMappings = plan.services.map { spec =>
      spec.serviceId -> spec.ports
    }.toMap

    val resolvedUrls = plan.services.flatMap { spec =>
      spec.ports.headOption.map { pm =>
        val port = pm.hostPort.getOrElse(pm.containerPort)
        spec.serviceId -> s"http://localhost:$port"
      }
    }.toMap

    RuntimeSnapshot(
      snapshotId = s"snapshot-${plan.runId}-${UUID.randomUUID().toString.take(8)}",
      runId = plan.runId,
      capturedAt = Instant.now(),
      environmentStatus = envStatus,
      services = allServices,
      activePortMappings = portMappings,
      resolvedUrls = resolvedUrls,
      uptimeMs = System.currentTimeMillis() - startTime,
    )
  }

  private def detectComposePaths(repoRoot: Path): List[Path] = {
    composeFileNames.map(repoRoot.resolve).filter(Files.exists(_))
  }
}
