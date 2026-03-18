package lastmile.repair.claude

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

import lastmile.model._
import lastmile.repair._

class ClaudeRepairBackendSuite extends FunSuite {

  // Mock repair backend that returns a fixed patch proposal
  private class MockRepairBackend(proposal: PatchProposal) extends RepairBackend {
    var callCount = 0
    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      RepairResponse.Success(proposal)
    }
  }

  // Mock repair backend that returns failure
  private class FailingRepairBackend(reason: String) extends RepairBackend {
    var callCount = 0
    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      RepairResponse.Failed(reason)
    }
  }

  // Mock repair backend that returns invalid patch
  private class InvalidPatchBackend(reason: String) extends RepairBackend {
    var callCount = 0
    override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
      callCount += 1
      RepairResponse.InvalidPatch(reason)
    }
  }

  private def makePacket(): FailurePacket = {
    FailurePacket(
      failurePacketId = UUID.randomUUID().toString,
      runId = "run-1",
      attemptNumber = 1,
      primaryFailureClass = FailureClass.BackendContractFailure,
      secondaryFailureClasses = Nil,
      summary = "Test failure",
      affectedRequirementIds = List("req-1"),
      reproductionSteps = Nil,
      evidenceRefs = Nil,
      suspectedRootCauses = Nil,
      recommendedRerunScope = List("req-1"),
      recommendedRepairScope = RepairScope(Nil, Nil, "test", false),
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
      taskText = "Fix the bug",
      worktreePath = worktreePath,
      graph = RequirementGraph(
        graphId = "g1", runId = "run-1", nodes = Nil, edges = Nil,
        generatedAt = Instant.now(), inferenceRequestId = None, warnings = Nil,
      ),
      verdicts = Nil,
      inspectionReport = None,
      runtimePlan = None,
      patchHistory = Nil,
    )
  }

  private def withTempDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("claude-test-")
    try { testFn(tmpDir) } finally { deleteRecursive(tmpDir) }
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      val entries = Files.list(path)
      try { entries.forEach(p => deleteRecursive(p)) } finally { entries.close() }
    }
    Files.deleteIfExists(path)
  }

  test("mock backend returns success with patch proposal") {
    withTempDir { dir =>
      val proposal = PatchProposal(
        patchId = "patch-1", runId = "run-1", attemptNumber = 1, backendId = "mock",
        edits = List(FileEdit("file.txt", "old", "new")),
        newFiles = Nil, deletions = Nil,
        summary = "Fix bug", hypotheses = List("root cause"), createdAt = Instant.now(),
      )

      val backend = new MockRepairBackend(proposal)
      val result = backend.proposePatch(makePacket(), makeContext(dir))

      assert(result.isInstanceOf[RepairResponse.Success])
      val success = result.asInstanceOf[RepairResponse.Success]
      assertEquals(success.patch.patchId, "patch-1")
      assertEquals(success.patch.edits.size, 1)
      assertEquals(backend.callCount, 1)
    }
  }

  test("failing backend returns failure response") {
    withTempDir { dir =>
      val backend = new FailingRepairBackend("API error")
      val result = backend.proposePatch(makePacket(), makeContext(dir))

      assert(result.isInstanceOf[RepairResponse.Failed])
      val failed = result.asInstanceOf[RepairResponse.Failed]
      assertEquals(failed.reason, "API error")
    }
  }

  test("invalid patch backend returns invalid patch response") {
    withTempDir { dir =>
      val backend = new InvalidPatchBackend("Malformed JSON")
      val result = backend.proposePatch(makePacket(), makeContext(dir))

      assert(result.isInstanceOf[RepairResponse.InvalidPatch])
      val invalid = result.asInstanceOf[RepairResponse.InvalidPatch]
      assertEquals(invalid.reason, "Malformed JSON")
    }
  }

  test("RepairExecutor applies patch from mock backend") {
    withTempDir { dir =>
      // Init git repo
      import scala.sys.process._
      Process(Seq("git", "init"), dir.toFile).!
      Process(Seq("git", "config", "user.email", "test@test.com"), dir.toFile).!
      Process(Seq("git", "config", "user.name", "Test"), dir.toFile).!

      // Create source file
      Files.write(dir.resolve("app.js"), "console.log('hello');\n".getBytes)
      Process(Seq("git", "add", "."), dir.toFile).!
      Process(Seq("git", "commit", "-m", "init"), dir.toFile).!

      val proposal = PatchProposal(
        patchId = "patch-2", runId = "run-1", attemptNumber = 1, backendId = "mock",
        edits = List(FileEdit("app.js", "hello", "world")),
        newFiles = Nil, deletions = Nil,
        summary = "Fix output", hypotheses = Nil, createdAt = Instant.now(),
      )

      val backend = new MockRepairBackend(proposal)
      val input = FailurePacketBuilder.FailurePacketInput(
        runId = "run-1", attemptNumber = 1, taskText = "Fix",
        verdicts = List(RequirementVerdict(
          verdictId = "v1", runId = "run-1", attemptNumber = 1,
          requirementId = "req-1", verifierId = "ver-1",
          status = VerdictStatus.Fail, executionDurationMs = 100, retryCount = 0,
          observations = Nil, evidenceRefs = Nil,
          failureClass = Some(FailureClass.BackendContractFailure),
          failureMessage = Some("Wrong output"),
          suggestedRerunScope = None, confidence = 1.0, producedAt = Instant.now(),
        )),
        graph = RequirementGraph("g1", "run-1", Nil, Nil, Instant.now(), None, Nil),
        inspectionReport = None, runtimePlan = None, patchHistory = Nil, logs = None,
      )

      val context = makeContext(dir)
      val outcome = RepairExecutor.executeRepair(backend, dir, input, context)

      assert(outcome.isInstanceOf[RepairExecutor.RepairApplied])
      val applied = outcome.asInstanceOf[RepairExecutor.RepairApplied]
      assertEquals(applied.filesChanged, List("app.js"))

      // Verify file was actually modified
      val content = new String(Files.readAllBytes(dir.resolve("app.js")))
      assert(content.contains("world"))
    }
  }

  test("RepairExecutor rejects when backend fails") {
    withTempDir { dir =>
      val backend = new FailingRepairBackend("Network error")
      val input = FailurePacketBuilder.FailurePacketInput(
        runId = "run-1", attemptNumber = 1, taskText = "Fix",
        verdicts = List(RequirementVerdict(
          verdictId = "v1", runId = "run-1", attemptNumber = 1,
          requirementId = "req-1", verifierId = "ver-1",
          status = VerdictStatus.Fail, executionDurationMs = 100, retryCount = 0,
          observations = Nil, evidenceRefs = Nil, failureClass = None,
          failureMessage = Some("fail"), suggestedRerunScope = None,
          confidence = 1.0, producedAt = Instant.now(),
        )),
        graph = RequirementGraph("g1", "run-1", Nil, Nil, Instant.now(), None, Nil),
        inspectionReport = None, runtimePlan = None, patchHistory = Nil, logs = None,
      )

      val context = makeContext(dir)
      val outcome = RepairExecutor.executeRepair(backend, dir, input, context)

      assert(outcome.isInstanceOf[RepairExecutor.RepairRejected])
      val rejected = outcome.asInstanceOf[RepairExecutor.RepairRejected]
      assert(rejected.reason.contains("Network error"))
    }
  }

  test("ClaudePromptBuilder builds system prompt") {
    val systemPrompt = ClaudePromptBuilder.buildSystemPrompt()
    assert(systemPrompt.contains("code repair agent"))
    assert(systemPrompt.contains("RESPONSE FORMAT"))
    assert(systemPrompt.contains("edits"))
    assert(systemPrompt.contains("newFiles"))
    assert(systemPrompt.contains("deletions"))
  }

  test("ClaudePromptBuilder builds user prompt with failure info") {
    withTempDir { dir =>
      Files.write(dir.resolve("app.js"), "console.log('test');\n".getBytes)

      val packet = makePacket()
      val context = makeContext(dir)
      val userPrompt = ClaudePromptBuilder.buildUserPrompt(packet, context)

      assert(userPrompt.contains("# Task"))
      assert(userPrompt.contains("# Failure Summary"))
      assert(userPrompt.contains("# Requirements"))
    }
  }

  test("RepairExecutor rejects empty patch from backend") {
    withTempDir { dir =>
      val emptyProposal = PatchProposal(
        patchId = "patch-empty", runId = "run-1", attemptNumber = 1, backendId = "mock",
        edits = Nil, newFiles = Nil, deletions = Nil,
        summary = "empty", hypotheses = Nil, createdAt = Instant.now(),
      )

      val backend = new MockRepairBackend(emptyProposal)
      val input = FailurePacketBuilder.FailurePacketInput(
        runId = "run-1", attemptNumber = 1, taskText = "Fix",
        verdicts = List(RequirementVerdict(
          verdictId = "v1", runId = "run-1", attemptNumber = 1,
          requirementId = "req-1", verifierId = "ver-1",
          status = VerdictStatus.Fail, executionDurationMs = 100, retryCount = 0,
          observations = Nil, evidenceRefs = Nil, failureClass = None,
          failureMessage = Some("fail"), suggestedRerunScope = None,
          confidence = 1.0, producedAt = Instant.now(),
        )),
        graph = RequirementGraph("g1", "run-1", Nil, Nil, Instant.now(), None, Nil),
        inspectionReport = None, runtimePlan = None, patchHistory = Nil, logs = None,
      )

      val context = makeContext(dir)
      val outcome = RepairExecutor.executeRepair(backend, dir, input, context)

      assert(outcome.isInstanceOf[RepairExecutor.RepairRejected])
      val rejected = outcome.asInstanceOf[RepairExecutor.RepairRejected]
      assert(rejected.reason.contains("empty"))
    }
  }
}
