package demiurge.orchestrator

import demiurge.model._
import demiurge.runtime.ServiceProcessManager

// Gap 5: LogCollector — captures service logs after verification for inclusion
// in repair prompts. Best-effort: never fails the run.
object LogCollector {

  private val DefaultTailLines = 50
  private val DefaultMaxChars = 10000

  case class CollectedLogs(
    serviceLogs:   Map[String, List[String]],
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
      serviceLogs.values.forall(_.isEmpty) && consoleErrors.isEmpty && networkErrors.isEmpty
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

      CollectedLogs(
        serviceLogs = serviceLogs,
        consoleErrors = Nil,
        networkErrors = Nil,
      )
    } catch {
      case _: Exception =>
        CollectedLogs(
          serviceLogs = Map.empty,
          consoleErrors = Nil,
          networkErrors = Nil,
        )
    }
  }
}
