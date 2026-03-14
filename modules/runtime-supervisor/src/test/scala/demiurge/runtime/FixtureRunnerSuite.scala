package demiurge.runtime

import munit.FunSuite
import java.nio.file.{Files, Path}
import demiurge.model.FixtureStep

class FixtureRunnerSuite extends FunSuite {

  private def withTempDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("fixture-test-")
    try { testFn(tmpDir) }
    finally { deleteRecursive(tmpDir) }
  }

  private def makeStep(
    stepId: String,
    command: String,
    cwd: String,
    timeoutMs: Int = 30000,
    dependsOnServices: List[String] = Nil,
    order: Int = 0,
  ): FixtureStep = FixtureStep(
    stepId = stepId, description = s"Test step $stepId", command = command,
    cwd = cwd, env = Map.empty, timeoutMs = timeoutMs,
    dependsOnServices = dependsOnServices, runOnReset = false,
    runOnInitOnly = false, order = order,
  )

  test("runs fixture steps in order") {
    withTempDir { root =>
      val outputFile = root.resolve("output.txt")
      val steps = List(
        makeStep("step-1", s"echo 'first' >> ${outputFile}", root.toString, order = 0),
        makeStep("step-2", s"echo 'second' >> ${outputFile}", root.toString, order = 1),
        makeStep("step-3", s"echo 'third' >> ${outputFile}", root.toString, order = 2),
      )

      val results = FixtureRunner.runAll(steps, Set.empty)
      results.foreach { case (id, result) =>
        assertEquals(result, FixtureRunner.FixtureSuccess, s"Step $id should succeed")
      }

      // Verify order
      val lines = new String(Files.readAllBytes(outputFile)).trim.split("\n").toList
      assertEquals(lines, List("first", "second", "third"))
    }
  }

  test("respects timeouts") {
    withTempDir { root =>
      val step = makeStep("slow", "sleep 10", root.toString, timeoutMs = 500)
      val result = FixtureRunner.runStep(step, Set.empty)
      assert(result.isInstanceOf[FixtureRunner.FixtureTimeout],
        s"Should timeout, got: $result")
    }
  }

  test("fails when dependent service is not healthy") {
    withTempDir { root =>
      val step = makeStep("needs-db", "echo ok", root.toString,
        dependsOnServices = List("db", "cache"))

      // Only db is healthy, cache is not
      val result = FixtureRunner.runStep(step, Set("db"))
      assert(result.isInstanceOf[FixtureRunner.FixtureFailure],
        s"Should fail on missing deps, got: $result")
      val failure = result.asInstanceOf[FixtureRunner.FixtureFailure]
      assert(failure.reason.contains("cache"), s"Should mention missing dep: ${failure.reason}")
    }
  }

  test("succeeds when all dependent services are healthy") {
    withTempDir { root =>
      val step = makeStep("needs-db", "echo ok", root.toString,
        dependsOnServices = List("db", "cache"))

      val result = FixtureRunner.runStep(step, Set("db", "cache"))
      assertEquals(result, FixtureRunner.FixtureSuccess)
    }
  }

  test("reports failure on non-zero exit code") {
    withTempDir { root =>
      val step = makeStep("bad", "exit 1", root.toString)
      val result = FixtureRunner.runStep(step, Set.empty)
      assert(result.isInstanceOf[FixtureRunner.FixtureFailure])
    }
  }

  test("passes env vars to fixture command") {
    withTempDir { root =>
      val outputFile = root.resolve("env_output.txt")
      val step = FixtureStep(
        stepId = "env-test", description = "test env", command = s"echo $$MY_VAR > ${outputFile}",
        cwd = root.toString, env = Map("MY_VAR" -> "hello_world"), timeoutMs = 5000,
        dependsOnServices = Nil, runOnReset = false, runOnInitOnly = false, order = 0,
      )
      val result = FixtureRunner.runStep(step, Set.empty)
      assertEquals(result, FixtureRunner.FixtureSuccess)

      val content = new String(Files.readAllBytes(outputFile)).trim
      assertEquals(content, "hello_world")
    }
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
