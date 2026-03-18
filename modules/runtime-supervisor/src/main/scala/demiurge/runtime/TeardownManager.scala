package demiurge.runtime

import java.nio.file.{Files, Path, Paths}
import demiurge.model.{RuntimePlan, ServiceSpec, StartupMode}

// Spec §8: Teardown logic for Phase 3.
// Stops script services, stops compose services, removes PID files.
// Leaves volumes preserved by default.
object TeardownManager {

  /**
   * Tear down all services in the specified order.
   * @param plan the RuntimePlan with teardownOrder
   * @param pidDir directory where PID files are stored
   * @param composeProjectName project name for compose services
   * @param composePaths compose file paths
   */
  def teardownAll(
    plan: RuntimePlan,
    pidDir: Path,
    composeProjectName: String,
    composePaths: List[Path] = Nil,
  ): List[(String, Boolean)] = {
    val serviceMap = plan.services.map(s => s.serviceId -> s).toMap

    plan.teardownOrder.map { serviceId =>
      serviceMap.get(serviceId) match {
        case Some(spec) =>
          val success = teardownService(spec, pidDir, composeProjectName, composePaths)
          serviceId -> success
        case None =>
          serviceId -> true // unknown service, consider it done
      }
    }
  }

  /**
   * Tear down a single service.
   */
  def teardownService(
    spec: ServiceSpec,
    pidDir: Path,
    composeProjectName: String,
    composePaths: List[Path],
  ): Boolean = {
    try {
      spec.startupMode match {
        case StartupMode.ScriptNative =>
          ServiceProcessManager.stopScript(spec.serviceId, spec.shutdownTimeoutMs, pidDir)
          true
        case StartupMode.ComposeNative =>
          ServiceProcessManager.stopCompose(
            spec.serviceId, composeProjectName, composePaths, Paths.get(spec.cwd))
          true
        case StartupMode.Hybrid =>
          // Try both
          ServiceProcessManager.stopScript(spec.serviceId, spec.shutdownTimeoutMs, pidDir)
          ServiceProcessManager.stopCompose(
            spec.serviceId, composeProjectName, composePaths, Paths.get(spec.cwd))
          true
        case _ =>
          true // VerifierOwnedContainer not handled in Phase 3
      }
    } catch {
      case e: Exception =>
        System.err.println(s"Warning: teardown of ${spec.serviceId} failed: ${e.getMessage}")
        false
    }
  }

  /** Clean up all PID files in the directory. */
  def cleanupPidFiles(pidDir: Path): Unit = {
    if (Files.exists(pidDir)) {
      try {
        val stream = Files.list(pidDir)
        try {
          stream.forEach { p =>
            if (p.toString.endsWith(".pid")) Files.deleteIfExists(p)
          }
        } finally {
          stream.close()
        }
      } catch {
        case _: Exception => // best effort
      }
    }
  }
}
