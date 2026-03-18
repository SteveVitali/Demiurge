package demiurge.orchestrator

import demiurge.model._
import demiurge.runtime.ServiceProcessManager

// Spec §13: LogCollector — captures service logs and observability tap data
// after verification for inclusion in repair prompts. Best-effort: never fails the run.
object LogCollector {

  private val DefaultTailLines = 50
  private val DefaultMaxChars = 10000

  case class CollectedLogs(
    serviceLogs:   Map[String, List[String]],
    tapLogs:       Map[String, List[String]] = Map.empty,
    consoleErrors: List[String],
    networkErrors: List[String],
  ) {
    def serialize(maxChars: Int = DefaultMaxChars): Option[String] = {
      if (isEmpty) return None
      val sb = new StringBuilder

      serviceLogs.foreach { case (serviceId, lines) =>
        if (lines.nonEmpty) {
          sb.append(s"=== Service: $serviceId ===\n")
          lines.foreach { line =>
            if (sb.length < maxChars) {
              sb.append(line).append('\n')
            }
          }
          sb.append('\n')
        }
      }

      // Spec §13: Include observability tap data
      tapLogs.foreach { case (tapId, lines) =>
        if (lines.nonEmpty) {
          sb.append(s"=== Tap: $tapId ===\n")
          lines.foreach { line =>
            if (sb.length < maxChars) {
              sb.append(line).append('\n')
            }
          }
          sb.append('\n')
        }
      }

      if (consoleErrors.nonEmpty) {
        sb.append("=== Console Errors ===\n")
        consoleErrors.foreach { err =>
          if (sb.length < maxChars) {
            sb.append(err).append('\n')
          }
        }
        sb.append('\n')
      }

      if (networkErrors.nonEmpty) {
        sb.append("=== Network Errors ===\n")
        networkErrors.foreach { err =>
          if (sb.length < maxChars) {
            sb.append(err).append('\n')
          }
        }
        sb.append('\n')
      }

      val result = sb.toString
      if (result.length > maxChars) Some(result.take(maxChars))
      else if (result.isEmpty) None
      else Some(result)
    }

    def isEmpty: Boolean =
      serviceLogs.values.forall(_.isEmpty) && tapLogs.values.forall(_.isEmpty) &&
        consoleErrors.isEmpty && networkErrors.isEmpty
  }

  def collectAfterVerification(
    runtimePlan: RuntimePlan,
  ): CollectedLogs = {
    try {
      val serviceLogs = runtimePlan.services.map { service =>
        val lines = try {
          ServiceProcessManager.getLogLines(service.serviceId).takeRight(DefaultTailLines)
        } catch {
          case _: Exception => Nil
        }
        service.serviceId -> lines
      }.toMap

      // Spec §13: Read observability taps
      val tapLogs = runtimePlan.observabilityTaps.map { tap =>
        val lines = try {
          readTapData(tap).takeRight(DefaultTailLines)
        } catch {
          case _: Exception => Nil
        }
        tap.tapId -> lines
      }.toMap

      CollectedLogs(
        serviceLogs = serviceLogs,
        tapLogs = tapLogs,
        consoleErrors = Nil,
        networkErrors = Nil,
      )
    } catch {
      case _: Exception =>
        CollectedLogs(
          serviceLogs = Map.empty,
          tapLogs = Map.empty,
          consoleErrors = Nil,
          networkErrors = Nil,
        )
    }
  }

  // Spec §13: Read data from an observability tap based on its type
  private def readTapData(tap: ObservabilityTap): List[String] = {
    tap.tapType match {
      case "log_file" =>
        // Read lines from a log file path specified in config
        tap.config.get("path").map { path =>
          try {
            val p = java.nio.file.Paths.get(path)
            if (java.nio.file.Files.exists(p)) {
              val source = scala.io.Source.fromFile(p.toFile)
              try source.getLines().toList finally source.close()
            } else Nil
          } catch { case _: Exception => Nil }
        }.getOrElse(Nil)

      case "docker_logs" =>
        // Read docker logs for a container
        tap.config.get("container").map { container =>
          try {
            val pb = new ProcessBuilder("docker", "logs", "--tail", DefaultTailLines.toString, container)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val source = scala.io.Source.fromInputStream(proc.getInputStream)
            val lines = try source.getLines().toList finally source.close()
            val finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            lines
          } catch { case _: Exception => Nil }
        }.getOrElse(Nil)

      case "service_stdout" =>
        // Delegate to ServiceProcessManager
        try {
          ServiceProcessManager.getLogLines(tap.serviceId).takeRight(DefaultTailLines)
        } catch { case _: Exception => Nil }

      case _ => Nil
    }
  }
}
