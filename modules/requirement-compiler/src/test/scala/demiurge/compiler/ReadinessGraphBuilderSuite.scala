package demiurge.compiler

import java.nio.file.Path
import java.time.Instant

import munit.FunSuite
import demiurge.model._

class ReadinessGraphBuilderSuite extends FunSuite {

  private def fakeInspection(runId: String = "test-run"): RepoInspectionReport =
    RepoInspectionReport(
      reportId = s"inspection-$runId",
      runId = runId,
      inspectedAt = Instant.now(),
      repoRoot = Path.of("/tmp/test"),
      languages = List(ScoredInference("javascript", 0.9, "test")),
      frameworks = List(ScoredInference("express", 0.8, "test")),
      candidateServices = Nil,
      startupCommands = Nil,
      healthEndpointHints = Nil,
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

  private def fakeConfig(): ResolvedConfig =
    ResolvedConfig(
      app = ResolvedAppConfig("api", "http://localhost:3000", None),
      services = List(ResolvedServiceConfig(
        serviceId = "api",
        kind = "api",
        startupMode = "script",
        startupCommand = Some("npm start"),
        composeTarget = None,
        cwd = None,
        env = Map.empty,
        ports = List(ResolvedPortConfig(Some(3000), 3000)),
        dependsOn = Nil,
        readiness = Some(ResolvedReadinessConfig("http", "http://localhost:3000/health", 1000, 3000, 10)),
        required = true,
      )),
      fixtures = None,
      auth = None,
      verification = ResolvedVerificationConfig(30000, 15000, 1, 1000, false, false, false),
      inference = ResolvedInferenceConfig(InferenceProvider.Mock, Map.empty),
      policies = ResolvedPoliciesConfig(5, 3600000L, 900000L, 2000, 536870912L,
        List("localhost"), List("http://localhost:*"), false, false),
      observability = None,
      provenance = ConfigProvenance(ConfigSource.Inferred, Map.empty, Map.empty, Instant.now()),
    )

  test("buildFallbackGraph creates readiness requirements from config services") {
    val config = fakeConfig()
    val inspection = fakeInspection()

    val graph = ReadinessGraphBuilder.buildFallbackGraph("run-1", inspection, config, None)

    assert(graph.nodes.nonEmpty, "Should have at least one requirement node")
    assertEquals(graph.nodes.head.requirementId, "api-readiness")
    assertEquals(graph.nodes.head.priority, RequirementPriority.Required)
    assert(graph.nodes.head.verifiers.nonEmpty)
  }

  test("buildFallbackGraph with no required services produces warning") {
    val config = fakeConfig().copy(services = List(
      fakeConfig().services.head.copy(required = false, readiness = None),
    ))
    val inspection = fakeInspection()

    val graph = ReadinessGraphBuilder.buildFallbackGraph("run-1", inspection, config, None)

    assert(graph.nodes.isEmpty, "Should have no requirements")
    assert(graph.warnings.nonEmpty, "Should have a warning")
    assertEquals(graph.warnings.head.code, "NO_REQUIREMENTS")
  }

  test("buildFallbackGraph creates HTTP verifier for http probe") {
    val config = fakeConfig()
    val inspection = fakeInspection()

    val graph = ReadinessGraphBuilder.buildFallbackGraph("run-1", inspection, config, None)
    val node = graph.nodes.head
    val verifier = node.verifiers.head

    assertEquals(verifier.verifierType, VerifierType.HttpApiContract)
    assert(verifier.apiContractSpec.isDefined)
    assertEquals(verifier.apiContractSpec.get.path, "http://localhost:3000/health")
  }

  test("buildFallbackGraph creates TCP verifier for tcp probe") {
    val config = fakeConfig().copy(services = List(
      ResolvedServiceConfig(
        serviceId = "db",
        kind = "db",
        startupMode = "compose",
        startupCommand = None,
        composeTarget = Some("postgres"),
        cwd = None,
        env = Map.empty,
        ports = List(ResolvedPortConfig(Some(5432), 5432)),
        dependsOn = Nil,
        readiness = Some(ResolvedReadinessConfig("tcp", "localhost:5432", 1000, 3000, 10)),
        required = true,
      ),
    ))
    val inspection = fakeInspection()

    val graph = ReadinessGraphBuilder.buildFallbackGraph("run-1", inspection, config, None)
    val node = graph.nodes.head
    val verifier = node.verifiers.head

    assertEquals(verifier.verifierType, VerifierType.EnvironmentReadiness)
    assert(verifier.envReadinessSpec.isDefined)
  }
}
