package demiurge.runtime

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Paths
import demiurge.model.FixtureStep

// Spec §8: Fixture step execution for Phase 3.
// After services are healthy, runs startup fixture steps in order.
// Honors depends_on_services, timeout_ms.
object FixtureRunner {

  sealed trait FixtureResult
  case object FixtureSuccess extends FixtureResult
  case class FixtureFailure(stepId: String, reason: String) extends FixtureResult
  case class FixtureTimeout(stepId: String, elapsedMs: Long) extends FixtureResult

  /**
   * Run all fixture steps in order.
   * @param steps sorted fixture steps
   * @param healthyServices set of service IDs that are currently healthy
   * @return list of results for each step
   */
  def runAll(steps: List[FixtureStep], healthyServices: Set[String]): List[(String, FixtureResult)] = {
    steps.map { step =>
      step.stepId -> runStep(step, healthyServices)
    }
  }

  /**
   * Run a single fixture step.
   * Checks depends_on_services before executing.
   */
  def runStep(step: FixtureStep, healthyServices: Set[String]): FixtureResult = {
    // Check service dependencies
    val missingDeps = step.dependsOnServices.filterNot(healthyServices.contains)
    if (missingDeps.nonEmpty) {
      return FixtureFailure(step.stepId,
        s"Dependent services not healthy: ${missingDeps.mkString(", ")}")
    }

    try {
      val cwd = Paths.get(step.cwd)
      val pb = new ProcessBuilder("sh", "-c", step.command)
      pb.directory(cwd.toFile)
      pb.redirectErrorStream(true)

      // Apply env vars
      val env = pb.environment()
      step.env.foreach { case (k, v) => env.put(k, v) }

      val process = pb.start()

      // Capture output
      val reader = new BufferedReader(new InputStreamReader(process.getInputStream))
      val output = new StringBuilder
      val logThread = new Thread(s"fixture-${step.stepId}") {
        override def run(): Unit = {
          try {
            var line = reader.readLine()
            while (line != null) {
              output.append(line).append("\n")
              line = reader.readLine()
            }
          } catch {
            case _: Exception =>
          }
        }
      }
      logThread.setDaemon(true)
      logThread.start()

      val completed = process.waitFor(step.timeoutMs.toLong, java.util.concurrent.TimeUnit.MILLISECONDS)

      if (!completed) {
        process.destroyForcibly()
        FixtureTimeout(step.stepId, step.timeoutMs.toLong)
      } else if (process.exitValue() == 0) {
        FixtureSuccess
      } else {
        FixtureFailure(step.stepId,
          s"Exit code ${process.exitValue()}: ${output.toString().take(500)}")
      }
    } catch {
      case e: Exception =>
        FixtureFailure(step.stepId, s"Exception: ${e.getMessage}")
    }
  }
}
