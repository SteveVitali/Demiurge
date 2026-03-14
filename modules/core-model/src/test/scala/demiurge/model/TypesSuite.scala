package demiurge.model

import munit.FunSuite
import io.circe._
import io.circe.syntax._
import io.circe.parser._
import demiurge.model.JsonCodecs._
import java.nio.file.Paths
import java.time.Instant

class TypesSuite extends FunSuite {

  private def roundTripTest[A: Encoder: Decoder](value: A): Unit = {
    val json = value.asJson.noSpaces
    val decoded = decode[A](json)
    decoded match {
      case Right(result) => assertEquals(result, value)
      case Left(err) => fail(s"Failed to decode: $err\nJSON: $json")
    }
  }

  test("TaskRun serialization round-trip") {
    val run = TaskRun(
      runId = "run-001",
      repoPath = Paths.get("/home/user/project"),
      worktreePath = Paths.get("/home/user/.demiurge/worktrees/run-001"),
      gitRef = Some("abc123"),
      taskText = "Add login button",
      changedFiles = Some(List("src/App.tsx", "src/Login.tsx")),
      status = RunStatus.Created,
      runMode = RunMode.Full,
      createdAt = Instant.parse("2025-01-01T00:00:00Z"),
      startedAt = None,
      endedAt = None,
      maxAttempts = 5,
      attemptCount = 0,
      envBootAttempts = 0,
      currentAttemptId = None,
      finalVerdict = None,
      finalSummary = None,
      policySnapshotId = "ps-001",
      lockFilePath = Paths.get("/home/user/project/.demiurge/run.lock"),
      artifactRootPath = Paths.get("/home/user/project/.runs/run-001"),
    )
    roundTripTest(run)
  }

  test("Attempt serialization round-trip") {
    val attempt = Attempt(
      attemptId = "att-001",
      runId = "run-001",
      attemptNumber = 1,
      status = AttemptStatus.Verifying,
      startedAt = Instant.parse("2025-01-01T00:01:00Z"),
      endedAt = None,
      repairBackend = Some("claude-agent-sdk"),
      patchRecordId = None,
      failurePacketId = None,
      rerunPlanId = None,
      repairRetriesUsed = 0,
      verdictSummary = Some(AttemptVerdictSummary(
        totalRequired = 5,
        passCount = 3,
        failCount = 1,
        inconclusiveCount = 0,
        blockedCount = 1,
        timeoutCount = 0,
        flakeCount = 0,
      )),
    )
    roundTripTest(attempt)
  }

  test("ExecutionBudget serialization round-trip") {
    val budget = ExecutionBudgetDefaults.defaults
    roundTripTest(budget)
  }

  test("ArtifactRecord serialization round-trip") {
    val artifact = ArtifactRecord(
      artifactId = "art-001",
      runId = "run-001",
      attemptNumber = Some(1),
      artifactType = ArtifactType.Screenshot,
      producerComponent = "browser_worker",
      logicalScope = Some("verifier-001"),
      relativePath = "attempts/1/verification/screenshots/verifier-001-final.png",
      contentType = "image/png",
      sizeBytes = 102400L,
      checksumSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      compressed = false,
      compressionFormat = None,
      createdAt = Instant.parse("2025-01-01T00:05:00Z"),
      metadata = Map("label" -> "final", "viewport" -> "1280x720"),
    )
    roundTripTest(artifact)
  }

  test("SystemEvent serialization round-trip") {
    val event = SystemEvent(
      eventId = "evt-001",
      runId = "run-001",
      attemptNumber = Some(1),
      eventType = "StateTransition",
      component = "orchestrator",
      severity = "info",
      timestamp = Instant.parse("2025-01-01T00:00:30Z"),
      correlationFields = Map("verifierId" -> "v-001", "serviceId" -> "frontend"),
      payload = Map("from" -> Json.fromString("Created"), "to" -> Json.fromString("Verifying")),
      humanMessage = "Run transitioned from Created to Verifying",
    )
    roundTripTest(event)
  }

  test("RequirementVerdict serialization round-trip") {
    val verdict = RequirementVerdict(
      verdictId = "vrd-001",
      runId = "run-001",
      attemptNumber = 1,
      requirementId = "req-001",
      verifierId = "ver-001",
      status = VerdictStatus.Pass,
      executionDurationMs = 3500L,
      retryCount = 0,
      observations = List(
        Observation(
          observationType = "assertion_passed",
          message = "Login button visible",
          selector = Some("#login-btn"),
          expected = Some("visible"),
          actual = Some("visible"),
          timestamp = Instant.parse("2025-01-01T00:02:00Z"),
        )
      ),
      evidenceRefs = List("art-001", "art-002"),
      failureClass = None,
      failureMessage = None,
      suggestedRerunScope = None,
      confidence = 0.95,
      producedAt = Instant.parse("2025-01-01T00:02:03Z"),
    )
    roundTripTest(verdict)
  }
}
