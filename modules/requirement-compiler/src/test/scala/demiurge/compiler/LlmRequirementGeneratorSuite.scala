package demiurge.compiler

import java.nio.file.Path
import java.time.Instant

import munit.FunSuite
import demiurge.model._
import demiurge.inference.{InferenceService, MockInferenceBackend, InferenceServiceImpl, InferenceBudgetState, InMemoryInferenceCache}

class LlmRequirementGeneratorSuite extends FunSuite {

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

    val graph = LlmRequirementGenerator.buildFallbackGraph("run-1", inspection, config, None)

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

    val graph = LlmRequirementGenerator.buildFallbackGraph("run-1", inspection, config, None)

    assert(graph.nodes.isEmpty, "Should have no requirements")
    assert(graph.warnings.nonEmpty, "Should have a warning")
    assertEquals(graph.warnings.head.code, "NO_REQUIREMENTS")
  }

  test("buildFallbackGraph creates HTTP verifier for http probe") {
    val config = fakeConfig()
    val inspection = fakeInspection()

    val graph = LlmRequirementGenerator.buildFallbackGraph("run-1", inspection, config, None)
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

    val graph = LlmRequirementGenerator.buildFallbackGraph("run-1", inspection, config, None)
    val node = graph.nodes.head
    val verifier = node.verifiers.head

    assertEquals(verifier.verifierType, VerifierType.EnvironmentReadiness)
    assert(verifier.envReadinessSpec.isDefined)
  }

  test("parseRequirementsFromJson extracts requirements from LLM response") {
    val json = """{
      "requirements": [
        {"id": "health-check", "type": "http", "description": "Health endpoint returns 200", "severity": "required", "url": "http://localhost:3000/health", "expected_status": 200},
        {"id": "db-reachable", "type": "tcp", "description": "Database is reachable", "severity": "required", "host_port": "localhost:5432"},
        {"id": "login-flow", "type": "browser_flow", "description": "User can log in", "severity": "important", "entry_url": "http://localhost:3000/login"}
      ]
    }"""

    val nodes = LlmRequirementGenerator.parseRequirementsFromJson(json)

    assertEquals(nodes.size, 3)
    assertEquals(nodes(0).requirementId, "health-check")
    assertEquals(nodes(0).category, RequirementCategory.ApiContract)
    assertEquals(nodes(1).requirementId, "db-reachable")
    assertEquals(nodes(1).category, RequirementCategory.EnvironmentReadiness)
    assertEquals(nodes(2).requirementId, "login-flow")
    assertEquals(nodes(2).category, RequirementCategory.UiFlow)
    assertEquals(nodes(2).priority, RequirementPriority.Important)
  }

  test("parseRequirementsFromJson returns empty list for malformed JSON") {
    val nodes = LlmRequirementGenerator.parseRequirementsFromJson("not json at all")
    assert(nodes.isEmpty)
  }

  test("generate falls back to readiness graph when LLM fails") {
    val mockBackend = new MockInferenceBackend(
      defaultResponse = Some(Left(InferenceError.Timeout("req-1", 60000))),
    )
    val budgetState = new InferenceBudgetState()
    val svc = new InferenceServiceImpl(mockBackend, budgetState, new InMemoryInferenceCache())

    val config = fakeConfig()
    val inspection = fakeInspection()

    val graph = LlmRequirementGenerator.generate("run-1", "Test task", inspection, config, svc)

    // Should fall back to readiness graph
    assert(graph.nodes.nonEmpty || graph.warnings.nonEmpty,
      "Should produce either nodes or warnings on fallback")
  }

  test("generate uses LLM response when successful") {
    val llmResponse = InferenceResponse(
      requestId = "req-1",
      responseText = """{"requirements": [{"id": "api-works", "type": "http", "description": "API responds", "severity": "required", "url": "http://localhost:3000/api"}]}""",
      parsedJson = Some("""{"requirements": [{"id": "api-works", "type": "http", "description": "API responds", "severity": "required", "url": "http://localhost:3000/api"}]}"""),
      inputTokens = 100,
      outputTokens = 50,
      cachedHit = false,
      durationMs = 500,
      model = "test-model",
      provider = InferenceProvider.Mock,
    )
    val mockBackend = new MockInferenceBackend(defaultResponse = Some(Right(llmResponse)))
    val budgetState = new InferenceBudgetState()
    val svc = new InferenceServiceImpl(mockBackend, budgetState, new InMemoryInferenceCache())

    val config = fakeConfig()
    val inspection = fakeInspection()

    val graph = LlmRequirementGenerator.generate("run-1", "Test task", inspection, config, svc)

    assert(graph.nodes.exists(_.requirementId == "api-works"),
      "Should contain LLM-generated requirement")
    assert(graph.inferenceRequestId.isDefined, "Should have inference request ID")
  }
}
