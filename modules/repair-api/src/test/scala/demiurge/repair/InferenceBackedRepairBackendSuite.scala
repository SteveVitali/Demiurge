package demiurge.repair

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

import demiurge.model._
import demiurge.inference.{MockInferenceBackend, InferenceBudgetState, InMemoryInferenceCache, InferenceServiceImpl}

class InferenceBackedRepairBackendSuite extends munit.FunSuite {

  private def makePacket(): FailurePacket = {
    FailurePacket(
      failurePacketId = UUID.randomUUID().toString,
      runId = "run-1",
      attemptNumber = 1,
      primaryFailureClass = FailureClass.BackendContractFailure,
      secondaryFailureClasses = Nil,
      summary = "Test failure: login page returns 500",
      affectedRequirementIds = List("req-1"),
      reproductionSteps = Nil,
      evidenceRefs = Nil,
      suspectedRootCauses = List(SuspectedCause("Bug in auth handler", 0.8, List("auth.js"), Nil, Nil)),
      recommendedRerunScope = List("req-1"),
      recommendedRepairScope = RepairScope(List("auth.js"), Nil, "fix auth", false),
      hardBlockers = Nil,
      softBlockers = Nil,
      producedAt = Instant.now(),
      inferenceRequestId = None,
    )
  }

  private def makeContext(worktreePath: Path, mode: GenerationMode = GenerationMode.Repair): RepairContext = {
    RepairContext(
      runId = "run-1",
      attemptNumber = 1,
      taskText = "Fix the login page bug",
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
        failureMessage = Some("HTTP 500"),
        suggestedRerunScope = None, confidence = 1.0, producedAt = Instant.now(),
      )),
      inspectionReport = None,
      runtimePlan = None,
      patchHistory = Nil,
      generationMode = mode,
    )
  }

  private def withTempDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("inference-repair-test-")
    try { testFn(tmpDir) } finally { deleteRecursive(tmpDir) }
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      val entries = Files.list(path)
      try { entries.forEach(p => deleteRecursive(p)) } finally { entries.close() }
    }
    Files.deleteIfExists(path)
  }

  private val successfulPatchJson =
    """{
      |  "summary": "Fix auth handler",
      |  "hypotheses": ["Missing null check in auth"],
      |  "edits": [
      |    {
      |      "relativePath": "auth.js",
      |      "oldContent": "return null",
      |      "newContent": "return user || defaultUser"
      |    }
      |  ],
      |  "newFiles": [],
      |  "deletions": []
      |}""".stripMargin

  private class TestPromptBuilder(systemResult: String = "system prompt") extends RepairPromptBuilder {
    var lastMode: GenerationMode = GenerationMode.Repair
    var lastPacket: Option[FailurePacket] = None
    var lastContext: Option[RepairContext] = None

    override def buildSystemPrompt(mode: GenerationMode): String = {
      lastMode = mode
      systemResult
    }

    override def buildUserPrompt(packet: FailurePacket, context: RepairContext): String = {
      lastPacket = Some(packet)
      lastContext = Some(context)
      s"Fix: ${packet.summary}"
    }

    override def buildRepairRequestPrompt(request: RepairRequest): String = {
      s"Fix: ${request.failurePacket.summary}"
    }
  }

  private case class TestHarness(
    mockBackend: MockInferenceBackend,
    promptBuilder: TestPromptBuilder,
    repairBackend: InferenceBackedRepairBackend,
  )

  private def mkHarness(
    responseText: String = successfulPatchJson,
    systemResult: String = "system prompt",
    backendError: Option[InferenceError] = None,
  ): TestHarness = {
    val promptBuilder = new TestPromptBuilder(systemResult)
    val mockBackend = backendError match {
      case Some(err) =>
        new MockInferenceBackend(responses = Map("repair_backend" -> Left(err)))
      case None =>
        new MockInferenceBackend(defaultResponse = Some(Right(InferenceResponse(
          requestId = "req-1", responseText = responseText,
          parsedJson = Some(responseText),
          inputTokens = 500, outputTokens = 200, cachedHit = false,
          durationMs = 1000, model = "claude-sonnet-4-20250514", provider = InferenceProvider.Mock,
        ))))
    }
    val service = new InferenceServiceImpl(mockBackend, new InferenceBudgetState(), new InMemoryInferenceCache())
    TestHarness(mockBackend, promptBuilder, new InferenceBackedRepairBackend(service, promptBuilder))
  }

  test("constructs correct InferenceRequest with component, prompts, and params") {
    withTempDir { dir =>
      val h = mkHarness()
      h.repairBackend.proposePatch(makePacket(), makeContext(dir))

      assertEquals(h.mockBackend.calls.size, 1)
      val req = h.mockBackend.calls.head
      assertEquals(req.component, "repair_backend")
      assertEquals(req.systemPrompt, "system prompt")
      assert(req.userPrompt.contains("Fix: Test failure"))
      assertEquals(req.responseFormat, Some("json"))
      assertEquals(req.maxOutputTokens, 8192)
      assertEquals(req.temperature, 0.2)
      assertEquals(req.cacheable, false)
      assertEquals(req.timeoutMs, 120000L)
      assertEquals(req.runId, "run-1")
      assertEquals(req.attemptNumber, Some(1))
    }
  }

  test("successful response parsed into PatchProposal correctly") {
    withTempDir { dir =>
      val h = mkHarness()
      val result = h.repairBackend.proposePatch(makePacket(), makeContext(dir))

      assert(result.isInstanceOf[RepairResponse.Success])
      val success = result.asInstanceOf[RepairResponse.Success]
      assertEquals(success.patch.summary, "Fix auth handler")
      assertEquals(success.patch.hypotheses, List("Missing null check in auth"))
      assertEquals(success.patch.edits.size, 1)
      assertEquals(success.patch.edits.head.relativePath, "auth.js")
      assertEquals(success.patch.edits.head.oldContent, "return null")
      assertEquals(success.patch.edits.head.newContent, "return user || defaultUser")
      assertEquals(success.patch.backendId, "inference")
      assertEquals(success.patch.runId, "run-1")
      assertEquals(success.patch.attemptNumber, 1)
    }
  }

  test("InferenceError mapped to RepairResponse.Failed") {
    withTempDir { dir =>
      val h = mkHarness(backendError = Some(InferenceError.ProviderError("req-1", 500, "Internal server error")))
      val result = h.repairBackend.proposePatch(makePacket(), makeContext(dir))

      assert(result.isInstanceOf[RepairResponse.Failed])
      val failed = result.asInstanceOf[RepairResponse.Failed]
      assert(failed.reason.contains("Provider error"))
      assert(failed.reason.contains("500"))
    }
  }

  test("GenerationMode.InitialBuild uses different system prompt") {
    withTempDir { dir =>
      val h = mkHarness(systemResult = "build mode prompt")
      h.repairBackend.proposePatch(makePacket(), makeContext(dir, GenerationMode.InitialBuild))

      assertEquals(h.promptBuilder.lastMode, GenerationMode.InitialBuild)
      val req = h.mockBackend.calls.head
      assertEquals(req.systemPrompt, "build mode prompt")
    }
  }

  test("empty patch returns InvalidPatch") {
    withTempDir { dir =>
      val h = mkHarness(responseText =
        """{"summary":"Nothing to do","hypotheses":[],"edits":[],"newFiles":[],"deletions":[]}""")
      val result = h.repairBackend.proposePatch(makePacket(), makeContext(dir))
      assert(result.isInstanceOf[RepairResponse.InvalidPatch])
    }
  }

  test("response with newFiles parsed correctly") {
    withTempDir { dir =>
      val h = mkHarness(responseText =
        """{
          |  "summary": "Add config file",
          |  "hypotheses": [],
          |  "edits": [],
          |  "newFiles": [{"relativePath": "config.json", "content": "{\"port\": 3000}"}],
          |  "deletions": []
          |}""".stripMargin)
      val result = h.repairBackend.proposePatch(makePacket(), makeContext(dir))

      assert(result.isInstanceOf[RepairResponse.Success])
      val success = result.asInstanceOf[RepairResponse.Success]
      assertEquals(success.patch.newFiles.size, 1)
      assertEquals(success.patch.newFiles.head.relativePath, "config.json")
    }
  }

  test("markdown-wrapped JSON response is extracted correctly") {
    withTempDir { dir =>
      val h = mkHarness(responseText = "```json\n" + successfulPatchJson + "\n```")
      val result = h.repairBackend.proposePatch(makePacket(), makeContext(dir))

      assert(result.isInstanceOf[RepairResponse.Success])
      val success = result.asInstanceOf[RepairResponse.Success]
      assertEquals(success.patch.summary, "Fix auth handler")
    }
  }

  test("malformed JSON returns InvalidPatch") {
    withTempDir { dir =>
      val h = mkHarness(responseText = "this is not json at all")
      val result = h.repairBackend.proposePatch(makePacket(), makeContext(dir))
      assert(result.isInstanceOf[RepairResponse.InvalidPatch])
      val invalid = result.asInstanceOf[RepairResponse.InvalidPatch]
      assert(invalid.reason.contains("Failed to parse LLM response"))
    }
  }

  test("response with deletions parsed correctly") {
    withTempDir { dir =>
      val h = mkHarness(responseText =
        """{
          |  "summary": "Remove obsolete file",
          |  "hypotheses": [],
          |  "edits": [],
          |  "newFiles": [],
          |  "deletions": [{"relativePath": "old-config.json"}]
          |}""".stripMargin)
      val result = h.repairBackend.proposePatch(makePacket(), makeContext(dir))

      assert(result.isInstanceOf[RepairResponse.Success])
      val success = result.asInstanceOf[RepairResponse.Success]
      assertEquals(success.patch.deletions.size, 1)
      assertEquals(success.patch.deletions.head.relativePath, "old-config.json")
    }
  }
}
