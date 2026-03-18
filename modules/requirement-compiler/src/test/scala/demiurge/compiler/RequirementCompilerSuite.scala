package demiurge.compiler

import munit.FunSuite
import java.nio.file.Paths
import java.time.Instant

import demiurge.model._
import demiurge.requirements._
import demiurge.selectors._

class RequirementCompilerSuite extends FunSuite {

  private val stubInspection = RepoInspectionReport(
    reportId = "test-report",
    runId = "test-run",
    inspectedAt = Instant.EPOCH,
    repoRoot = Paths.get("/tmp/test"),
    languages = Nil,
    frameworks = Nil,
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

  test("compiles requirements into RequirementGraph with correct nodes") {
    val reqs = RequirementsFile(List(
      RequirementEntry("req-1", "http", "Health check", None, Some("http://localhost:3000/health"), Some(5000L), Some(2), Some("required")),
      RequirementEntry("req-2", "process", "Server runs", None, None, Some(10000L), None, Some("important")),
    ))
    val sels = SelectorsFile(Nil)
    val compiler = new RequirementCompilerImpl(reqs, sels)
    val graph = compiler.compile("run-1", stubInspection, "test task")

    assertEquals(graph.nodes.size, 2)
    assertEquals(graph.runId, "run-1")
    assertEquals(graph.nodes.head.requirementId, "req-1")
    assertEquals(graph.nodes.head.priority, RequirementPriority.Required)
    assertEquals(graph.nodes.head.category, RequirementCategory.ApiContract)
    assertEquals(graph.nodes(1).requirementId, "req-2")
    assertEquals(graph.nodes(1).priority, RequirementPriority.Important)
  }

  test("each node has exactly one verifier") {
    val reqs = RequirementsFile(List(
      RequirementEntry("req-1", "http", "Health check", None, Some("http://localhost:3000"), None, None, None),
    ))
    val sels = SelectorsFile(Nil)
    val compiler = new RequirementCompilerImpl(reqs, sels)
    val graph = compiler.compile("run-1", stubInspection, "test task")

    assertEquals(graph.nodes.head.verifiers.size, 1)
    assertEquals(graph.nodes.head.verifiers.head.verifierId, "v-req-1")
    assertEquals(graph.nodes.head.verifiers.head.verifierType, VerifierType.HttpApiContract)
  }

  test("resolves selectors from selectors file") {
    val reqs = RequirementsFile(List(
      RequirementEntry("req-1", "http", "API check", Some("login-btn"), None, None, None, None),
    ))
    val sels = SelectorsFile(List(
      SelectorEntry("login-btn", "css", "button#login", Some("Login button")),
    ))
    val compiler = new RequirementCompilerImpl(reqs, sels)
    val graph = compiler.compile("run-1", stubInspection, "test task")

    assertEquals(graph.nodes.size, 1)
  }

  test("deterministic output for same input") {
    val reqs = RequirementsFile(List(
      RequirementEntry("req-1", "http", "Health", None, Some("http://localhost:3000"), Some(5000), None, None),
      RequirementEntry("req-2", "log", "No errors", None, Some("error"), None, None, None),
    ))
    val sels = SelectorsFile(Nil)
    val compiler = new RequirementCompilerImpl(reqs, sels)

    val graph1 = compiler.compile("run-1", stubInspection, "test")
    val graph2 = compiler.compile("run-1", stubInspection, "test")

    assertEquals(graph1.nodes.size, graph2.nodes.size)
    graph1.nodes.zip(graph2.nodes).foreach { case (n1, n2) =>
      assertEquals(n1.requirementId, n2.requirementId)
      assertEquals(n1.verifiers.map(_.verifierId), n2.verifiers.map(_.verifierId))
      assertEquals(n1.priority, n2.priority)
      assertEquals(n1.category, n2.category)
    }
  }

  test("empty requirements produces empty graph") {
    val reqs = RequirementsFile(Nil)
    val sels = SelectorsFile(Nil)
    val compiler = new RequirementCompilerImpl(reqs, sels)
    val graph = compiler.compile("run-1", stubInspection, "test")

    assertEquals(graph.nodes.size, 0)
    assertEquals(graph.edges.size, 0)
  }

  test("maps type to correct verifier type") {
    val types = Map(
      "http" -> VerifierType.HttpApiContract,
      "tcp" -> VerifierType.EnvironmentReadiness,
      "process" -> VerifierType.StateAssertion,
      "state" -> VerifierType.StateAssertion,
      "log" -> VerifierType.ConsoleLogSanity,
      "env_readiness" -> VerifierType.EnvironmentReadiness,
    )
    types.foreach { case (reqType, expectedVerifierType) =>
      val reqs = RequirementsFile(List(
        RequirementEntry(s"req-$reqType", reqType, s"test $reqType", None, None, None, None, None),
      ))
      val compiler = new RequirementCompilerImpl(reqs, SelectorsFile(Nil))
      val graph = compiler.compile("run-1", stubInspection, "test")
      assertEquals(
        graph.nodes.head.verifiers.head.verifierType,
        expectedVerifierType,
        s"Type '$reqType' should map to $expectedVerifierType",
      )
    }
  }

  test("maps severity to correct priority") {
    val severities = Map(
      "required" -> RequirementPriority.Required,
      "important" -> RequirementPriority.Important,
      "nice_to_have" -> RequirementPriority.NiceToHave,
    )
    severities.foreach { case (sev, expectedPriority) =>
      val reqs = RequirementsFile(List(
        RequirementEntry("req-1", "http", "test", None, None, None, None, Some(sev)),
      ))
      val compiler = new RequirementCompilerImpl(reqs, SelectorsFile(Nil))
      val graph = compiler.compile("run-1", stubInspection, "test")
      assertEquals(graph.nodes.head.priority, expectedPriority)
    }
  }
}
