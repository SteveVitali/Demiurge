package lastmile.cli

import munit.FunSuite
import java.nio.file.Paths
import java.time.Instant
import io.circe.parser.{decode => jsonDecode}
import io.circe.Json

import lastmile.model._
import lastmile.cli.CommandParsers.OutputFormat

// Phase 7: Output formatter tests — Spec §14.2
class OutputFormatterSuite extends FunSuite {

  private val sampleRun = TaskRun(
    runId = "run-1",
    repoPath = Paths.get("/tmp/repo"),
    worktreePath = Paths.get("/tmp/repo"),
    gitRef = Some("main"),
    taskText = "Fix login",
    changedFiles = None,
    status = RunStatus.Succeeded,
    runMode = RunMode.Full,
    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    startedAt = Some(Instant.parse("2025-01-01T00:00:01Z")),
    endedAt = Some(Instant.parse("2025-01-01T00:01:00Z")),
    maxAttempts = 5,
    attemptCount = 1,
    envBootAttempts = 0,
    currentAttemptId = None,
    finalVerdict = Some(VerdictStatus.Pass),
    finalSummary = Some("All 3 verifiers passed"),
    policySnapshotId = "policy-1",
    lockFilePath = Paths.get("/tmp/repo/.lastmile/run.lock"),
    artifactRootPath = Paths.get("/tmp/repo/.lastmile/artifacts"),
  )

  test("JSON mode emits machine-readable output for run") {
    val output = OutputFormatter.formatRun(sampleRun, OutputFormat.Json)
    val parsed = jsonDecode[Json](output)
    assert(parsed.isRight, s"JSON parse failed: $output")
    val json = parsed.toOption.get
    assertEquals(json.hcursor.get[String]("runId").toOption, Some("run-1"))
    assertEquals(json.hcursor.get[String]("status").toOption, Some("Succeeded"))
  }

  test("human mode emits readable output for run") {
    val output = OutputFormatter.formatRun(sampleRun, OutputFormat.Human)
    assert(output.contains("run-1"))
    assert(output.contains("Succeeded"))
    assert(output.contains("Fix login"))
  }

  test("JSON mode emits machine-readable output for run list") {
    val output = OutputFormatter.formatRunList(List(sampleRun), OutputFormat.Json)
    val parsed = jsonDecode[Json](output)
    assert(parsed.isRight)
    val arr = parsed.toOption.get.asArray
    assert(arr.isDefined)
    assertEquals(arr.get.length, 1)
  }

  test("JSON mode emits valid JSON for empty run list") {
    val output = OutputFormatter.formatRunList(Nil, OutputFormat.Json)
    val parsed = jsonDecode[Json](output)
    assert(parsed.isRight)
  }

  test("human mode handles empty run list") {
    val output = OutputFormatter.formatRunList(Nil, OutputFormat.Human)
    assert(output.contains("No runs found"))
  }

  test("JSON mode emits machine-readable success message") {
    val output = OutputFormatter.formatSuccess("done", OutputFormat.Json)
    val parsed = jsonDecode[Json](output)
    assert(parsed.isRight)
    val json = parsed.toOption.get
    assertEquals(json.hcursor.get[Boolean]("ok").toOption, Some(true))
  }

  test("JSON mode emits machine-readable error message") {
    val output = OutputFormatter.formatError("bad input", OutputFormat.Json)
    val parsed = jsonDecode[Json](output)
    assert(parsed.isRight)
    val json = parsed.toOption.get
    assertEquals(json.hcursor.get[Boolean]("ok").toOption, Some(false))
  }

  test("doctor check JSON format") {
    val output = OutputFormatter.formatDoctorCheck("Node.js", "pass", "v20.0.0", OutputFormat.Json)
    val parsed = jsonDecode[Json](output)
    assert(parsed.isRight)
    val json = parsed.toOption.get
    assertEquals(json.hcursor.get[String]("check").toOption, Some("Node.js"))
    assertEquals(json.hcursor.get[String]("status").toOption, Some("pass"))
  }

  test("clean targets JSON format") {
    val output = OutputFormatter.formatCleanTargets(List("run: abc"), true, OutputFormat.Json)
    val parsed = jsonDecode[Json](output)
    assert(parsed.isRight)
    val json = parsed.toOption.get
    assertEquals(json.hcursor.get[Boolean]("dryRun").toOption, Some(true))
  }

  test("failure explanation JSON format") {
    val verdict = RequirementVerdict(
      verdictId = "v1",
      runId = "r1",
      attemptNumber = 1,
      requirementId = "req-1",
      verifierId = "ver-1",
      status = VerdictStatus.Fail,
      executionDurationMs = 500,
      retryCount = 0,
      observations = List(Observation("assertion", "Button not found", None, None, None, Instant.now())),
      evidenceRefs = Nil,
      failureClass = Some(FailureClass.FrontendRenderError),
      failureMessage = Some("Button missing"),
      suggestedRerunScope = None,
      confidence = 0.9,
      producedAt = Instant.now(),
    )
    val output = OutputFormatter.formatFailureExplanation(List(verdict), OutputFormat.Json)
    val parsed = jsonDecode[Json](output)
    assert(parsed.isRight)
    val json = parsed.toOption.get
    assertEquals(json.hcursor.get[Int]("failureCount").toOption, Some(1))
  }
}
