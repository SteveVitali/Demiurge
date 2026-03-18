package demiurge.runtime

import demiurge.model._

// Spec §8: Environment health monitoring with degradation detection and recovery.
// Runs health checks on all services and attempts recovery when degradation is detected.
// Integrates with ReadinessChecker for probe execution.
object EnvironmentHealthMonitor {

  sealed trait HealthStatus
  case object Healthy extends HealthStatus
  case class Degraded(unhealthyServices: List[String], details: List[String]) extends HealthStatus
  case class Failed(reason: String) extends HealthStatus

  case class HealthCheckResult(
    status:             HealthStatus,
    serviceStatuses:    Map[String, ServiceHealthStatus],
    checkedAt:          java.time.Instant,
  )

  case class ServiceHealthStatus(
    serviceId: String,
    healthy:   Boolean,
    message:   String,
  )

  sealed trait RecoveryResult
  case object RecoverySuccess extends RecoveryResult
  case class RecoveryPartial(recoveredServices: List[String], failedServices: List[String]) extends RecoveryResult
  case class RecoveryFailed(reason: String) extends RecoveryResult

  /**
   * Run a health check on all services in the runtime plan.
   * Uses each service's readiness probe to determine health.
   */
  def checkHealth(plan: RuntimePlan): HealthCheckResult = {
    val statuses = plan.services.map { svc =>
      val status = checkServiceHealth(svc)
      svc.serviceId -> status
    }.toMap

    val unhealthy = statuses.filter(!_._2.healthy)
    val healthStatus = if (unhealthy.isEmpty) {
      Healthy
    } else if (unhealthy.size == plan.services.size) {
      Failed(s"All ${plan.services.size} services unhealthy")
    } else {
      val details = unhealthy.map { case (id, s) => s"$id: ${s.message}" }.toList
      Degraded(unhealthy.keys.toList, details)
    }

    HealthCheckResult(
      status = healthStatus,
      serviceStatuses = statuses,
      checkedAt = java.time.Instant.now(),
    )
  }

  /**
   * Attempt to recover degraded services by restarting them.
   * Only restarts services that failed health checks and respect restart policy limits.
   */
  def attemptRecovery(
    plan:         RuntimePlan,
    repoRoot:     java.nio.file.Path,
    healthResult: HealthCheckResult,
  ): RecoveryResult = {
    val unhealthy = healthResult.serviceStatuses.filter(!_._2.healthy).keys.toList
    if (unhealthy.isEmpty) return RecoverySuccess

    val recovered = scala.collection.mutable.ListBuffer[String]()
    val failed = scala.collection.mutable.ListBuffer[String]()

    for (serviceId <- unhealthy) {
      plan.services.find(_.serviceId == serviceId) match {
        case Some(svc) if svc.restartPolicy.maxRestarts > 0 =>
          // Attempt to restart the service
          val restarted = restartService(svc, repoRoot)
          if (restarted) {
            // Verify health after restart
            val healthAfter = checkServiceHealth(svc)
            if (healthAfter.healthy) {
              recovered += serviceId
            } else {
              failed += serviceId
            }
          } else {
            failed += serviceId
          }
        case Some(_) =>
          // No restarts allowed by policy
          failed += serviceId
        case None =>
          failed += serviceId
      }
    }

    if (failed.isEmpty) RecoverySuccess
    else if (recovered.nonEmpty) RecoveryPartial(recovered.toList, failed.toList)
    else RecoveryFailed(s"Failed to recover services: ${failed.mkString(", ")}")
  }

  /**
   * Monitor health during verification — check between layers.
   * Returns the current environment status based on health check.
   */
  def toEnvironmentStatus(health: HealthStatus): EnvironmentStatus = health match {
    case Healthy               => EnvironmentStatus.Ready
    case Degraded(_, _)        => EnvironmentStatus.Degraded
    case Failed(_)             => EnvironmentStatus.Failed
  }

  /** Check a single service's health using its readiness probe. */
  private def checkServiceHealth(svc: ServiceSpec): ServiceHealthStatus = {
    try {
      val result = ReadinessChecker.checkOnce(svc.readinessProbe)
      result match {
        case ReadinessChecker.ProbeSuccess =>
          ServiceHealthStatus(svc.serviceId, healthy = true, "healthy")
        case ReadinessChecker.ProbeFailure(reason) =>
          ServiceHealthStatus(svc.serviceId, healthy = false, reason)
        case ReadinessChecker.ProbeTimeout(elapsed) =>
          ServiceHealthStatus(svc.serviceId, healthy = false, s"probe timed out after ${elapsed}ms")
      }
    } catch {
      case e: Exception =>
        ServiceHealthStatus(svc.serviceId, healthy = false, s"health check error: ${e.getMessage}")
    }
  }

  /** Attempt to restart a single service. */
  private def restartService(svc: ServiceSpec, repoRoot: java.nio.file.Path): Boolean = {
    try {
      // Stop the service
      svc.startupMode match {
        case StartupMode.ComposeNative =>
          svc.composeTarget.foreach { target =>
            val stop = new ProcessBuilder("docker", "compose", "restart", target)
              .directory(repoRoot.resolve(svc.cwd).toFile)
              .redirectErrorStream(true)
              .start()
            stop.waitFor(svc.shutdownTimeoutMs.toLong, java.util.concurrent.TimeUnit.MILLISECONDS)
          }
          true

        case StartupMode.ScriptNative =>
          // Re-run startup command (assumes previous process exited or will be replaced)
          svc.startupCommand.foreach { cmd =>
            val process = new ProcessBuilder("sh", "-c", cmd)
              .directory(repoRoot.resolve(svc.cwd).toFile)
              .redirectErrorStream(true)
              .start()
            // Wait briefly for startup
            Thread.sleep(math.min(2000, svc.readinessProbe.initialDelayMs.toLong))
          }
          true

        case _ =>
          // Hybrid and VerifierOwnedContainer: best-effort
          false
      }
    } catch {
      case _: Exception => false
    }
  }
}
