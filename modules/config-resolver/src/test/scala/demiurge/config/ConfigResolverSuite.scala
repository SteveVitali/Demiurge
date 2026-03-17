package demiurge.config

import java.nio.file.{Files, Path}
import java.time.Instant

import munit.FunSuite
import demiurge.model._

class ConfigResolverSuite extends FunSuite {

  private def createTempRepo(): Path = {
    val dir = Files.createTempDirectory("demiurge-test-repo")
    dir.toFile.deleteOnExit()
    dir
  }

  private def fakeInspection(runId: String = "test-run", repoRoot: Path = Path.of("/tmp")): RepoInspectionReport =
    RepoInspectionReport(
      reportId = s"inspection-$runId",
      runId = runId,
      inspectedAt = Instant.now(),
      repoRoot = repoRoot,
      languages = List(ScoredInference("javascript", 0.9, "test")),
      frameworks = List(ScoredInference("express", 0.8, "test")),
      candidateServices = List(CandidateService(
        serviceId = "node-app",
        kind = ServiceKind.Api,
        confidence = 0.7,
        provenance = "test",
        startupHint = Some("npm start"),
        portHint = Some(3000),
        healthHint = Some("http://localhost:3000/health"),
      )),
      startupCommands = List(ScoredInference("npm start", 0.8, "test")),
      healthEndpointHints = List(ScoredInference("http://localhost:3000/health", 0.5, "test")),
      dbDependencies = Nil,
      queueDependencies = Nil,
      frontendEntrypoints = Nil,
      apiBasePaths = Nil,
      testFrameworkHints = Nil,
      authHints = Nil,
      changedSurfaceMap = None,
      manifestsFound = Nil,
      warnings = Nil,
    )

  test("resolve with no YAML files produces inferred config from inspection") {
    val repo = createTempRepo()
    val inspection = fakeInspection(repoRoot = repo)

    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)

    assertEquals(config.app.appType, "api")
    assertEquals(config.app.rootUrl, "http://localhost:3000")
    assert(config.services.nonEmpty, "Should have inferred services")
    assertEquals(config.provenance.manifestSource, ConfigSource.Inferred)
  }

  test("resolve with explicit demiurge.yaml uses manifest") {
    val repo = createTempRepo()
    val manifest = """version: 1
                     |app:
                     |  type: fullstack
                     |  root_url: http://localhost:8080
                     |  api_url: http://localhost:8080/api
                     |services:
                     |  web:
                     |    kind: frontend
                     |    startup_mode: script
                     |    startup_command: npm run dev
                     |    ports:
                     |      - container: 8080
                     |    readiness:
                     |      probe_type: http
                     |      target: http://localhost:8080/
                     |    required: true
                     |""".stripMargin
    Files.writeString(repo.resolve("demiurge.yaml"), manifest)

    val inspection = fakeInspection(repoRoot = repo)
    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)

    assertEquals(config.app.appType, "fullstack")
    assertEquals(config.app.rootUrl, "http://localhost:8080")
    assertEquals(config.provenance.manifestSource, ConfigSource.Explicit)
    assert(config.services.exists(_.serviceId == "web"))
  }

  test("resolve uses cached manifest when no explicit exists") {
    val repo = createTempRepo()
    val inferredDir = repo.resolve(".demiurge").resolve("inferred")
    Files.createDirectories(inferredDir)
    val cachedManifest = """version: 1
                           |app:
                           |  type: api
                           |  root_url: http://localhost:4000
                           |services:
                           |  api:
                           |    kind: api
                           |    startup_mode: script
                           |    startup_command: node server.js
                           |    ports:
                           |      - container: 4000
                           |    readiness:
                           |      probe_type: http
                           |      target: http://localhost:4000/health
                           |    required: true
                           |""".stripMargin
    Files.writeString(inferredDir.resolve("demiurge.yaml"), cachedManifest)

    val inspection = fakeInspection(repoRoot = repo)
    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)

    assertEquals(config.app.rootUrl, "http://localhost:4000")
    assertEquals(config.provenance.manifestSource, ConfigSource.Cached)
  }

  test("resolve infers api type from express framework") {
    val repo = createTempRepo()
    val inspection = fakeInspection(repoRoot = repo).copy(
      frameworks = List(ScoredInference("express", 0.8, "test")),
    )

    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)
    assertEquals(config.app.appType, "api")
  }

  test("resolve infers fullstack type from react + express") {
    val repo = createTempRepo()
    val inspection = fakeInspection(repoRoot = repo).copy(
      frameworks = List(
        ScoredInference("react", 0.8, "test"),
        ScoredInference("express", 0.8, "test"),
      ),
    )

    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)
    assertEquals(config.app.appType, "fullstack")
  }

  test("resolve produces default verification config when no YAML") {
    val repo = createTempRepo()
    val inspection = fakeInspection(repoRoot = repo)
    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)

    assertEquals(config.verification.defaultVerifierTimeoutMs, 30000)
    assertEquals(config.verification.maxRetries, 1)
    assertEquals(config.verification.screenshotOnFailure, false)
  }

  test("resolve produces default policies when no YAML") {
    val repo = createTempRepo()
    val inspection = fakeInspection(repoRoot = repo)
    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)

    assertEquals(config.policies.maxAttempts, 5)
    assertEquals(config.policies.runTimeoutMs, 3600000L)
    assertEquals(config.policies.allowGitPush, false)
  }

  test("resolve with manifest policies overrides defaults") {
    val repo = createTempRepo()
    val manifest = """version: 1
                     |app:
                     |  type: api
                     |  root_url: http://localhost:3000
                     |services:
                     |  app:
                     |    kind: api
                     |    startup_mode: script
                     |    startup_command: npm start
                     |policies:
                     |  max_attempts: 10
                     |  run_timeout_ms: 7200000
                     |""".stripMargin
    Files.writeString(repo.resolve("demiurge.yaml"), manifest)

    val inspection = fakeInspection(repoRoot = repo)
    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)

    assertEquals(config.policies.maxAttempts, 10)
    assertEquals(config.policies.runTimeoutMs, 7200000L)
  }

  test("cacheResolvedConfig writes to .demiurge/inferred/") {
    val repo = createTempRepo()
    val inspection = fakeInspection(repoRoot = repo)
    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)

    ConfigResolverImpl.cacheResolvedConfig(repo, config)

    val cachedPath = repo.resolve(".demiurge").resolve("inferred").resolve("demiurge.yaml")
    assert(Files.exists(cachedPath), s"Cached manifest should exist at $cachedPath")
    val content = Files.readString(cachedPath)
    assert(content.contains("version: 1"), "Cached manifest should contain version")
    assert(content.contains(config.app.rootUrl), "Cached manifest should contain root URL")
  }

  test("InferredConfigWriter round-trips through ManifestParser") {
    val repo = createTempRepo()
    val inspection = fakeInspection(repoRoot = repo)
    val config = ConfigResolverImpl.resolve(repo, "Test task", None, inspection, None)

    val yaml = InferredConfigWriter.toManifestYaml(config)
    Files.writeString(repo.resolve("round-trip.yaml"), yaml)

    val parsed = demiurge.manifest.ManifestParser.parseFile(repo.resolve("round-trip.yaml"))
    parsed match {
      case demiurge.manifest.ManifestParser.ParseSuccess(m) =>
        assertEquals(m.app.rootUrl, config.app.rootUrl)
        assertEquals(m.app.appType, config.app.appType)
      case demiurge.manifest.ManifestParser.ParseFailure(errors) =>
        fail(s"Round-trip parse failed: ${errors.mkString(", ")}")
    }
  }
}
