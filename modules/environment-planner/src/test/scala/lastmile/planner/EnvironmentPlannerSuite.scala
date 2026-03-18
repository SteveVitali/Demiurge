package lastmile.planner

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.Instant

import lastmile.model._
import lastmile.manifest._

class EnvironmentPlannerSuite extends FunSuite {

  private def withTempDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("planner-test-")
    try { testFn(tmpDir) }
    finally { deleteRecursive(tmpDir) }
  }

  private def makeInspection(runId: String, repoRoot: Path, manifestsFound: List[ManifestRef] = Nil): RepoInspectionReport =
    RepoInspectionReport(
      reportId = s"test-inspection-$runId", runId = runId, inspectedAt = Instant.EPOCH,
      repoRoot = repoRoot, languages = Nil, frameworks = Nil, candidateServices = Nil,
      startupCommands = Nil, healthEndpointHints = Nil, dbDependencies = Nil,
      queueDependencies = Nil, frontendEntrypoints = Nil, apiBasePaths = Nil,
      testFrameworkHints = Nil, authHints = Nil, changedSurfaceMap = None,
      manifestsFound = manifestsFound, warnings = Nil,
    )

  private def makeRequirements(runId: String): RequirementGraph =
    RequirementGraph(
      graphId = s"test-graph-$runId", runId = runId, nodes = Nil, edges = Nil,
      generatedAt = Instant.EPOCH, inferenceRequestId = None, warnings = Nil,
    )

  test("builds RuntimePlan from manifest") {
    withTempDir { root =>
      val yaml =
        """version: 1
          |app:
          |  type: web
          |  root_url: http://localhost:3000
          |services:
          |  api:
          |    kind: api
          |    startup_mode: script
          |    startup_command: "node server.js"
          |    ports:
          |      - container: 3000
          |    readiness:
          |      probe_type: http
          |      target: http://localhost:3000/health
          |""".stripMargin
      Files.write(root.resolve("lastmile.yaml"), yaml.getBytes)

      val manifestRef = ManifestRef("lastmile", "lastmile.yaml", parsedSuccessfully = true, parseErrors = Nil)
      val inspection = makeInspection("run-1", root, List(manifestRef))
      val requirements = makeRequirements("run-1")

      val plan = EnvironmentPlannerImpl.plan("run-1", inspection, requirements)
      assertEquals(plan.services.size, 1)
      assertEquals(plan.services.head.serviceId, "api")
      assertEquals(plan.services.head.startupMode, StartupMode.ScriptNative)
      assertEquals(plan.services.head.startupCommand, Some("node server.js"))
      assert(plan.teardownOrder.nonEmpty)
    }
  }

  test("topologically sorts service dependencies") {
    withTempDir { root =>
      val yaml =
        """version: 1
          |app:
          |  type: web
          |  root_url: http://localhost:3000
          |services:
          |  frontend:
          |    kind: frontend
          |    startup_mode: script
          |    startup_command: "npm start"
          |    ports:
          |      - container: 3000
          |    depends_on:
          |      - api
          |  api:
          |    kind: api
          |    startup_mode: script
          |    startup_command: "node api.js"
          |    ports:
          |      - container: 4000
          |    depends_on:
          |      - db
          |  db:
          |    kind: db
          |    startup_mode: compose
          |    compose_target: postgres
          |    ports:
          |      - container: 5432
          |""".stripMargin
      Files.write(root.resolve("lastmile.yaml"), yaml.getBytes)

      val manifestRef = ManifestRef("lastmile", "lastmile.yaml", parsedSuccessfully = true, parseErrors = Nil)
      val inspection = makeInspection("run-2", root, List(manifestRef))
      val requirements = makeRequirements("run-2")

      val plan = EnvironmentPlannerImpl.plan("run-2", inspection, requirements)
      val serviceIds = plan.services.map(_.serviceId)

      // db should come before api, api before frontend
      assert(serviceIds.indexOf("db") < serviceIds.indexOf("api"),
        s"db should come before api in: $serviceIds")
      assert(serviceIds.indexOf("api") < serviceIds.indexOf("frontend"),
        s"api should come before frontend in: $serviceIds")
    }
  }

  test("fails on dependency cycle") {
    withTempDir { root =>
      val yaml =
        """version: 1
          |app:
          |  type: web
          |  root_url: http://localhost:3000
          |services:
          |  a:
          |    kind: api
          |    startup_mode: script
          |    startup_command: "echo a"
          |    ports:
          |      - container: 3000
          |    depends_on:
          |      - b
          |  b:
          |    kind: api
          |    startup_mode: script
          |    startup_command: "echo b"
          |    ports:
          |      - container: 3001
          |    depends_on:
          |      - a
          |""".stripMargin
      Files.write(root.resolve("lastmile.yaml"), yaml.getBytes)

      val manifestRef = ManifestRef("lastmile", "lastmile.yaml", parsedSuccessfully = true, parseErrors = Nil)
      val inspection = makeInspection("run-3", root, List(manifestRef))
      val requirements = makeRequirements("run-3")

      intercept[EnvironmentPlannerImpl.PlanningError] {
        EnvironmentPlannerImpl.plan("run-3", inspection, requirements)
      }
    }
  }

  test("manifest overrides inferred hints") {
    withTempDir { root =>
      val yaml =
        """version: 1
          |app:
          |  type: web
          |  root_url: http://localhost:8080
          |services:
          |  api:
          |    kind: api
          |    startup_mode: script
          |    startup_command: "python app.py"
          |    ports:
          |      - container: 8080
          |    readiness:
          |      probe_type: http
          |      target: http://localhost:8080/ready
          |""".stripMargin
      Files.write(root.resolve("lastmile.yaml"), yaml.getBytes)

      val manifestRef = ManifestRef("lastmile", "lastmile.yaml", parsedSuccessfully = true, parseErrors = Nil)
      // Inspection suggests node, but manifest says python
      val inspection = makeInspection("run-4", root, List(manifestRef)).copy(
        candidateServices = List(CandidateService("node-app", ServiceKind.Api, 0.7, "inferred",
          Some("npm start"), Some(3000), Some("http://localhost:3000/")))
      )
      val requirements = makeRequirements("run-4")

      val plan = EnvironmentPlannerImpl.plan("run-4", inspection, requirements)
      // Manifest service should be used, not the inferred one
      assertEquals(plan.services.size, 1)
      assertEquals(plan.services.head.serviceId, "api")
      assertEquals(plan.services.head.startupCommand, Some("python app.py"))
    }
  }

  test("teardown order is reverse dependency order") {
    withTempDir { root =>
      val yaml =
        """version: 1
          |app:
          |  type: web
          |  root_url: http://localhost:3000
          |services:
          |  frontend:
          |    kind: frontend
          |    startup_mode: script
          |    startup_command: "npm start"
          |    ports:
          |      - container: 3000
          |    depends_on:
          |      - api
          |  api:
          |    kind: api
          |    startup_mode: script
          |    startup_command: "node api.js"
          |    ports:
          |      - container: 4000
          |    depends_on:
          |      - db
          |  db:
          |    kind: db
          |    startup_mode: compose
          |    compose_target: postgres
          |    ports:
          |      - container: 5432
          |""".stripMargin
      Files.write(root.resolve("lastmile.yaml"), yaml.getBytes)

      val manifestRef = ManifestRef("lastmile", "lastmile.yaml", parsedSuccessfully = true, parseErrors = Nil)
      val inspection = makeInspection("run-5", root, List(manifestRef))
      val requirements = makeRequirements("run-5")

      val plan = EnvironmentPlannerImpl.plan("run-5", inspection, requirements)
      val startupOrder = plan.services.map(_.serviceId)
      val teardownOrder = plan.teardownOrder

      assertEquals(teardownOrder, startupOrder.reverse)
    }
  }

  test("plans from inspection when no manifest") {
    withTempDir { root =>
      val inspection = makeInspection("run-6", root).copy(
        candidateServices = List(
          CandidateService("node-app", ServiceKind.Api, 0.7, "inferred",
            Some("npm start"), Some(3000), Some("http://localhost:3000/"))
        )
      )
      val requirements = makeRequirements("run-6")

      val plan = EnvironmentPlannerImpl.plan("run-6", inspection, requirements)
      assert(plan.warnings.exists(_.contains("No valid lastmile.yaml")))
      assertEquals(plan.services.size, 1)
      assertEquals(plan.services.head.serviceId, "node-app")
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
