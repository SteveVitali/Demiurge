package demiurge.repair.claude

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

import demiurge.model._
import demiurge.repair._

class ClaudePromptBuilderSuite extends munit.FunSuite {

  private def makePacket(): FailurePacket = {
    FailurePacket(
      failurePacketId = UUID.randomUUID().toString,
      runId = "run-1",
      attemptNumber = 1,
      primaryFailureClass = FailureClass.BackendContractFailure,
      secondaryFailureClasses = Nil,
      summary = "Login page returns 500",
      affectedRequirementIds = List("req-1"),
      reproductionSteps = Nil,
      evidenceRefs = Nil,
      suspectedRootCauses = List(SuspectedCause("Null pointer in auth", 0.9, List("auth.js"), Nil, Nil)),
      recommendedRerunScope = List("req-1"),
      recommendedRepairScope = RepairScope(List("auth.js"), Nil, "fix auth", false),
      hardBlockers = Nil,
      softBlockers = Nil,
      producedAt = Instant.now(),
      inferenceRequestId = None,
    )
  }

  private def makeContext(worktreePath: Path): RepairContext = {
    RepairContext(
      runId = "run-1",
      attemptNumber = 1,
      taskText = "Fix the login page authentication bug",
      worktreePath = worktreePath,
      graph = RequirementGraph(
        graphId = "g1", runId = "run-1",
        nodes = List(RequirementNode(
          requirementId = "req-1",
          humanDescription = "Login page should return 200",
          machineDescription = "GET /login returns HTTP 200",
          priority = RequirementPriority.Required,
          category = RequirementCategory.ApiContract,
          dependencies = Set.empty,
          verifiers = Nil,
          evidenceRequired = Nil,
          destructiveRiskLevel = 0,
          inferredFrom = Nil,
          confidence = 1.0,
          stopOnFailure = false,
        )),
        edges = Nil,
        generatedAt = Instant.now(), inferenceRequestId = None, warnings = Nil,
      ),
      verdicts = List(RequirementVerdict(
        verdictId = "v1", runId = "run-1", attemptNumber = 1,
        requirementId = "req-1", verifierId = "ver-1",
        status = VerdictStatus.Fail, executionDurationMs = 100, retryCount = 0,
        observations = Nil, evidenceRefs = Nil,
        failureClass = Some(FailureClass.BackendContractFailure),
        failureMessage = Some("HTTP 500 on /login"),
        suggestedRerunScope = None, confidence = 1.0, producedAt = Instant.now(),
      )),
      inspectionReport = None,
      runtimePlan = None,
      patchHistory = List(PatchProposal(
        patchId = "patch-prev", runId = "run-1", attemptNumber = 0, backendId = "claude",
        edits = List(FileEdit("auth.js", "old", "new")),
        newFiles = Nil, deletions = Nil,
        summary = "Previous fix attempt", hypotheses = Nil, createdAt = Instant.now(),
      )),
    )
  }

  private def withTempDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("prompt-builder-test-")
    try { testFn(tmpDir) } finally { deleteRecursive(tmpDir) }
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      val entries = Files.list(path)
      try { entries.forEach(p => deleteRecursive(p)) } finally { entries.close() }
    }
    Files.deleteIfExists(path)
  }

  test("buildSystemPrompt(Repair) contains 'code repair agent'") {
    val prompt = ClaudePromptBuilder.buildSystemPrompt(GenerationMode.Repair)
    assert(prompt.contains("code repair agent"))
    assert(prompt.contains("RESPONSE FORMAT"))
    assert(prompt.contains("edits"))
    assert(prompt.contains("newFiles"))
    assert(prompt.contains("deletions"))
    assert(prompt.contains("Keep changes minimal"))
  }

  test("buildSystemPrompt(InitialBuild) contains code generation instructions") {
    val prompt = ClaudePromptBuilder.buildSystemPrompt(GenerationMode.InitialBuild)
    assert(prompt.contains("code generation agent"))
    assert(prompt.contains("implement"))
    assert(prompt.contains("RESPONSE FORMAT"))
    assert(prompt.contains("edits"))
    assert(prompt.contains("newFiles"))
    assert(prompt.contains("deletions"))
    assert(!prompt.contains("code repair agent"))
  }

  test("zero-arg buildSystemPrompt defaults to Repair mode") {
    val defaultPrompt = ClaudePromptBuilder.buildSystemPrompt()
    val repairPrompt = ClaudePromptBuilder.buildSystemPrompt(GenerationMode.Repair)
    assertEquals(defaultPrompt, repairPrompt)
  }

  test("buildUserPrompt includes task text") {
    withTempDir { dir =>
      val prompt = ClaudePromptBuilder.buildUserPrompt(makePacket(), makeContext(dir))
      assert(prompt.contains("# Task"))
      assert(prompt.contains("Fix the login page authentication bug"))
    }
  }

  test("buildUserPrompt includes failure summary") {
    withTempDir { dir =>
      val prompt = ClaudePromptBuilder.buildUserPrompt(makePacket(), makeContext(dir))
      assert(prompt.contains("# Failure Summary"))
      assert(prompt.contains("Login page returns 500"))
    }
  }

  test("buildUserPrompt includes failed requirements") {
    withTempDir { dir =>
      val prompt = ClaudePromptBuilder.buildUserPrompt(makePacket(), makeContext(dir))
      assert(prompt.contains("# Failed Requirements"))
      assert(prompt.contains("req-1"))
      assert(prompt.contains("Fail"))
    }
  }

  test("buildUserPrompt includes requirements graph") {
    withTempDir { dir =>
      val prompt = ClaudePromptBuilder.buildUserPrompt(makePacket(), makeContext(dir))
      assert(prompt.contains("# Requirements"))
      assert(prompt.contains("Login page should return 200"))
    }
  }

  test("buildUserPrompt includes suspected root causes") {
    withTempDir { dir =>
      val prompt = ClaudePromptBuilder.buildUserPrompt(makePacket(), makeContext(dir))
      assert(prompt.contains("# Suspected Root Causes"))
      assert(prompt.contains("Null pointer in auth"))
    }
  }

  test("buildUserPrompt includes patch history") {
    withTempDir { dir =>
      val prompt = ClaudePromptBuilder.buildUserPrompt(makePacket(), makeContext(dir))
      assert(prompt.contains("# Prior Patch Attempts"))
      assert(prompt.contains("Previous fix attempt"))
    }
  }

  test("buildUserPrompt includes relevant source files from worktree") {
    withTempDir { dir =>
      Files.write(dir.resolve("app.js"), "console.log('hello');\n".getBytes)
      val prompt = ClaudePromptBuilder.buildUserPrompt(makePacket(), makeContext(dir))
      assert(prompt.contains("# Relevant Source Files"))
      assert(prompt.contains("app.js"))
      assert(prompt.contains("console.log"))
    }
  }

  test("ClaudePromptBuilder implements RepairPromptBuilder trait") {
    val builder: RepairPromptBuilder = ClaudePromptBuilder
    val prompt = builder.buildSystemPrompt(GenerationMode.Repair)
    assert(prompt.contains("code repair agent"))
  }

  test("buildUserPrompt in InitialBuild mode shows feature plan instead of failure context") {
    withTempDir { dir =>
      Files.write(dir.resolve("app.js"), "console.log('hello');\n".getBytes)
      val context = makeContext(dir).copy(
        generationMode = GenerationMode.InitialBuild,
        featureSpec = Some("Add a /api/ping endpoint"),
        featurePlan = Some(FeaturePlan(
          planId = "fp-1", runId = "run-1", taskText = "Add ping endpoint",
          summary = "Create a new ping endpoint",
          filesToCreate = List(PlannedFile("src/ping.ts", "Ping route handler", "route")),
          filesToModify = List(PlannedModification("src/router.ts", "Register ping route", "add_route")),
          filesToDelete = Nil,
          requiresNewDeps = List("express"),
          requiresMigration = false,
          estimatedComplexity = "small",
          createdAt = Instant.now(),
        )),
        verdicts = Nil,
      )
      val prompt = ClaudePromptBuilder.buildUserPrompt(makePacket(), context)

      // Should contain feature plan details
      assert(prompt.contains("# Feature Plan"), "Should have Feature Plan section")
      assert(prompt.contains("Create a new ping endpoint"), "Should contain plan summary")
      assert(prompt.contains("src/ping.ts"), "Should list files to create")
      assert(prompt.contains("src/router.ts"), "Should list files to modify")
      assert(prompt.contains("express"), "Should list new dependencies")
      assert(prompt.contains("# Feature Specification"), "Should have Feature Specification section")
      assert(prompt.contains("Add a /api/ping endpoint"), "Should contain feature spec")

      // Should NOT contain repair-mode sections
      assert(!prompt.contains("# Failure Summary"), "Should not have Failure Summary in build mode")
      assert(!prompt.contains("# Failed Requirements"), "Should not have Failed Requirements in build mode")
      assert(!prompt.contains("# Suspected Root Causes"), "Should not have Suspected Root Causes in build mode")

      // Should still contain shared sections
      assert(prompt.contains("# Requirements"), "Should still have Requirements section")
      assert(prompt.contains("# Relevant Source Files"), "Should still have Relevant Source Files section")
    }
  }

  test("buildUserPrompt in Repair mode does not show feature plan sections") {
    withTempDir { dir =>
      Files.write(dir.resolve("app.js"), "console.log('hello');\n".getBytes)
      val prompt = ClaudePromptBuilder.buildUserPrompt(makePacket(), makeContext(dir))

      // Should have repair-mode sections
      assert(prompt.contains("# Failure Summary"))
      assert(prompt.contains("# Failed Requirements"))
      assert(prompt.contains("# Suspected Root Causes"))

      // Should NOT have build-mode sections
      assert(!prompt.contains("# Feature Plan"))
      assert(!prompt.contains("# Feature Specification"))
    }
  }
}
