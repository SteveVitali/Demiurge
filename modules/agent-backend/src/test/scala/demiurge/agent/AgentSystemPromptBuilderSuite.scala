package demiurge.agent

import java.nio.file.Path
import java.time.{Duration, Instant}
import munit.FunSuite
import demiurge.model._
import demiurge.repair.{RepairContext, PatchProposal}

class AgentSystemPromptBuilderSuite extends FunSuite {

  private def makeVerifierSpec(
    verifierId: String,
    displayName: String,
    requirementId: String,
    verifierType: VerifierType = VerifierType.HttpApiContract,
  ): VerifierSpec = VerifierSpec(
    verifierId = verifierId,
    verifierType = verifierType,
    displayName = displayName,
    requirementId = requirementId,
    executionLayer = 0,
    parallelSafe = true,
    timeout = Duration.ofSeconds(5),
    maxRetries = 0,
    retryDelayMs = 100,
    browserFlowSpec = None,
    apiContractSpec = None,
    stateAssertionSpec = None,
    envReadinessSpec = None,
    consoleLogSpec = None,
    networkSpec = None,
    queueJobSpec = None,
    persistenceSpec = None,
    regressionSpec = None,
  )

  private def makeNode(
    reqId: String,
    description: String,
    category: RequirementCategory = RequirementCategory.ApiContract,
    verifiers: List[VerifierSpec] = Nil,
  ): RequirementNode = RequirementNode(
    requirementId = reqId,
    humanDescription = description,
    machineDescription = description,
    priority = RequirementPriority.Required,
    category = category,
    dependencies = Set.empty,
    verifiers = verifiers,
    evidenceRequired = Nil,
    destructiveRiskLevel = 0,
    inferredFrom = Nil,
    confidence = 1.0,
    stopOnFailure = false,
  )

  private def makeGraph(nodes: List[RequirementNode]): RequirementGraph = RequirementGraph(
    graphId = "graph-test",
    runId = "run-test-1",
    nodes = nodes,
    edges = Nil,
    generatedAt = Instant.now(),
    inferenceRequestId = None,
    warnings = Nil,
  )

  private def makeVerdict(
    reqId: String,
    status: VerdictStatus,
    failureMessage: Option[String] = None,
  ): RequirementVerdict = RequirementVerdict(
    verdictId = s"v-$reqId",
    runId = "run-test-1",
    attemptNumber = 1,
    requirementId = reqId,
    verifierId = s"ver-$reqId",
    status = status,
    executionDurationMs = 100,
    retryCount = 0,
    observations = Nil,
    evidenceRefs = Nil,
    failureClass = None,
    failureMessage = failureMessage,
    suggestedRerunScope = None,
    confidence = 1.0,
    producedAt = Instant.now(),
  )

  private val healthVerifier = makeVerifierSpec("v-001", "GET /health returns 200", "REQ-001")

  private val baseGraph = makeGraph(List(
    makeNode("REQ-001", "Server responds with 200 on GET /health", verifiers = List(healthVerifier)),
    makeNode("REQ-002", "Database migration runs successfully", category = RequirementCategory.PersistenceState),
  ))

  private val baseContext = RepairContext(
    runId = "run-test-1",
    attemptNumber = 1,
    taskText = "Add a health check endpoint to the API server",
    worktreePath = Path.of("/tmp/worktrees/run-test-1"),
    graph = baseGraph,
    verdicts = Nil,
    inspectionReport = None,
    runtimePlan = None,
    patchHistory = Nil,
    generationMode = GenerationMode.Repair,
  )

  test("system prompt includes task text") {
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(baseContext)
    assert(prompt.contains("Add a health check endpoint"), s"Prompt should contain task text")
  }

  test("system prompt includes generation mode") {
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(baseContext)
    assert(prompt.contains("## Generation Mode"), "Prompt should have Generation Mode section")
    assert(prompt.contains("Repair"), "Prompt should show Repair mode")
  }

  test("system prompt includes working directory") {
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(baseContext)
    assert(prompt.contains("/tmp/worktrees/run-test-1"), "Prompt should include worktree path")
  }

  test("system prompt includes requirement IDs and descriptions") {
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(baseContext)
    assert(prompt.contains("REQ-001"), "Prompt should include requirement ID")
    assert(prompt.contains("Server responds with 200 on GET /health"), "Prompt should include requirement description")
    assert(prompt.contains("REQ-002"), "Prompt should include second requirement ID")
  }

  test("system prompt includes verifier specs") {
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(baseContext)
    assert(prompt.contains("HttpApiContract"), "Prompt should include verifier type")
    assert(prompt.contains("GET /health returns 200"), "Prompt should include verifier displayName")
  }

  test("system prompt includes MCP tool descriptions") {
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(baseContext)
    assert(prompt.contains("verify_requirements"), "Prompt should describe verify_requirements tool")
    assert(prompt.contains("get_service_logs"), "Prompt should describe get_service_logs tool")
    assert(prompt.contains("restart_service"), "Prompt should describe restart_service tool")
    assert(prompt.contains("check_service_health"), "Prompt should describe check_service_health tool")
    assert(prompt.contains("get_requirement_details"), "Prompt should describe get_requirement_details tool")
  }

  test("system prompt includes repair instructions for Repair mode") {
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(baseContext)
    assert(prompt.contains("Identify the root cause"), "Repair mode should include root cause instruction")
    assert(prompt.contains("verify_requirements()"), "Repair mode should instruct to verify")
  }

  test("system prompt includes build instructions for InitialBuild mode") {
    val buildCtx = baseContext.copy(
      generationMode = GenerationMode.InitialBuild,
      featureSpec = Some("Build a REST API"),
      featurePlan = Some(FeaturePlan(
        planId = "fplan-1",
        runId = "run-test-1",
        taskText = "Build a REST API",
        summary = "Create a Node.js Express server with health endpoint",
        filesToCreate = List(
          PlannedFile("src/server.ts", "Main server file", "entry"),
        ),
        filesToModify = Nil,
        filesToDelete = Nil,
        requiresNewDeps = List("express"),
        requiresMigration = false,
        estimatedComplexity = "small",
        createdAt = Instant.now(),
      )),
    )
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(buildCtx)
    assert(prompt.contains("InitialBuild"), "Should show InitialBuild mode")
    assert(prompt.contains("Create a Node.js Express server"), "Should include feature plan summary")
    assert(prompt.contains("src/server.ts"), "Should include planned files")
    assert(prompt.contains("express"), "Should include required dependencies")
    assert(prompt.contains("Create all necessary files"), "Should include build instructions")
  }

  test("system prompt includes verification status when verdicts present") {
    val ctx = baseContext.copy(
      verdicts = List(makeVerdict("REQ-001", VerdictStatus.Fail, Some("Expected 200 but got 404"))),
    )
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(ctx)
    assert(prompt.contains("Current Verification Status"), "Should include verification status section")
    assert(prompt.contains("Expected 200 but got 404"), "Should include failure message")
  }

  test("system prompt includes patch history when present") {
    val ctx = baseContext.copy(
      patchHistory = List(
        PatchProposal(
          patchId = "patch-1",
          runId = "run-test-1",
          attemptNumber = 1,
          backendId = "test",
          edits = Nil,
          newFiles = List(demiurge.repair.NewFile("src/routes.ts", "// handler")),
          deletions = Nil,
          summary = "Added health endpoint handler",
          hypotheses = List("Missing route handler"),
          createdAt = Instant.now(),
        ),
      ),
    )
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(ctx)
    assert(prompt.contains("Prior Repair Attempts"), "Should include patch history section")
    assert(prompt.contains("Added health endpoint handler"), "Should include patch summary")
    assert(prompt.contains("src/routes.ts"), "Should include changed files")
  }

  test("system prompt does NOT embed file contents") {
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(baseContext)
    assert(!prompt.contains("```"), "System prompt should NOT contain code blocks with embedded files")
  }

  test("user prompt for repair mode mentions failing count") {
    val ctx = baseContext.copy(
      verdicts = List(
        makeVerdict("REQ-001", VerdictStatus.Fail, Some("fail")),
        makeVerdict("REQ-002", VerdictStatus.Pass),
      ),
    )
    val userPrompt = AgentSystemPromptBuilder.buildUserPrompt(ctx)
    assert(userPrompt.contains("1 requirement(s) are failing"), "User prompt should mention fail count")
  }

  test("user prompt for build mode mentions implementation") {
    val buildCtx = baseContext.copy(generationMode = GenerationMode.InitialBuild)
    val userPrompt = AgentSystemPromptBuilder.buildUserPrompt(buildCtx)
    assert(userPrompt.contains("Implement the feature"), "Build user prompt should mention implementation")
  }

  test("system prompt includes service logs when present") {
    val ctx = baseContext.copy(logs = Some("Error: ECONNREFUSED\n  at connect"))
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(ctx)
    assert(prompt.contains("Service Logs"), "Should include service logs section")
    assert(prompt.contains("ECONNREFUSED"), "Should include actual log content")
  }

  test("system prompt truncates very long service logs") {
    val longLogs = "x" * 20000
    val ctx = baseContext.copy(logs = Some(longLogs))
    val prompt = AgentSystemPromptBuilder.buildSystemPrompt(ctx)
    val logsSection = prompt.substring(prompt.indexOf("## Service Logs"))
    assert(logsSection.length < 15000, "Logs section should be truncated")
  }
}
