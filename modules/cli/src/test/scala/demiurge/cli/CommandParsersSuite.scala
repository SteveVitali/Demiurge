package demiurge.cli

import munit.FunSuite
import demiurge.cli.CommandParsers._

// Phase 7: CLI command parser tests
class CommandParsersSuite extends FunSuite {

  // --- Parses all required commands ---

  test("parses run command with --task") {
    val result = CommandParsers.parse(Array("run", "--task", "Fix login"))
    assert(result.isRight)
    val ParseResult(global, cmd) = result.toOption.get
    assert(cmd.isInstanceOf[RunCmd])
    assertEquals(cmd.asInstanceOf[RunCmd].task, "Fix login")
  }

  test("parses plan command with --task") {
    val result = CommandParsers.parse(Array("plan", "--task", "Add tests"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[PlanCmd]
    assertEquals(cmd.task, "Add tests")
  }

  test("parses resume command with --run-id") {
    val result = CommandParsers.parse(Array("resume", "--run-id", "abc-123"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[ResumeCmd]
    assertEquals(cmd.runId, "abc-123")
  }

  test("parses status command without run-id") {
    val result = CommandParsers.parse(Array("status"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[StatusCmd]
    assertEquals(cmd.runId, None)
  }

  test("parses status command with --run-id") {
    val result = CommandParsers.parse(Array("status", "--run-id", "xyz"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[StatusCmd]
    assertEquals(cmd.runId, Some("xyz"))
  }

  test("parses inspect-run command") {
    val result = CommandParsers.parse(Array("inspect-run", "--run-id", "r1", "--attempt", "2", "--show-verdicts", "--show-artifacts"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[InspectRunCmd]
    assertEquals(cmd.runId, "r1")
    assertEquals(cmd.attempt, Some(2))
    assert(cmd.showVerdicts)
    assert(cmd.showArtifacts)
  }

  test("parses open-artifact command") {
    val result = CommandParsers.parse(Array("open-artifact", "--run-id", "r1", "--artifact-id", "a1", "--print-path"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[OpenArtifactCmd]
    assertEquals(cmd.runId, "r1")
    assertEquals(cmd.artifactId, Some("a1"))
    assert(cmd.printPath)
  }

  test("parses explain-failure command") {
    val result = CommandParsers.parse(Array("explain-failure", "--run-id", "r1"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[ExplainFailureCmd]
    assertEquals(cmd.runId, "r1")
  }

  test("parses cancel command") {
    val result = CommandParsers.parse(Array("cancel", "--run-id", "r1"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[CancelCmd]
    assertEquals(cmd.runId, Some("r1"))
  }

  test("parses clean command with --all") {
    val result = CommandParsers.parse(Array("clean", "--all", "--dry-run", "--include-artifacts"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[CleanCmd]
    assert(cmd.all)
    assert(cmd.dryRun)
    assert(cmd.includeArtifacts)
  }

  test("parses doctor command") {
    val result = CommandParsers.parse(Array("doctor"))
    assert(result.isRight)
    assert(result.toOption.get.command.isInstanceOf[DoctorCmd])
  }

  test("parses init-manifest command") {
    val result = CommandParsers.parse(Array("init-manifest", "--output", "out.yaml", "--force"))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[InitManifestCmd]
    assertEquals(cmd.output, "out.yaml")
    assert(cmd.force)
  }

  // --- Required flags ---

  test("parses run command with all budget flags") {
    val result = CommandParsers.parse(Array(
      "run", "--task", "Test",
      "--max-attempts", "3",
      "--run-timeout", "30m",
      "--attempt-timeout", "5m",
      "--verifier-timeout", "60s",
      "--repair-timeout", "10m",
      "--inference-timeout", "2m",
      "--max-patch-lines", "500",
      "--max-artifact-disk", "256MB",
      "--max-repair-tokens", "100000",
      "--max-exploratory-steps", "20",
      "--changed-files", "a.ts,b.ts",
      "--git-ref", "main",
      "--mode", "Full",
      "--run-id", "custom-id",
      "--replay-inference",
    ))
    assert(result.isRight)
    val cmd = result.toOption.get.command.asInstanceOf[RunCmd]
    assertEquals(cmd.maxAttempts, Some(3))
    assertEquals(cmd.runTimeout, Some(1800000L))
    assertEquals(cmd.attemptTimeout, Some(300000L))
    assertEquals(cmd.verifierTimeout, Some(60000L))
    assertEquals(cmd.repairTimeout, Some(600000L))
    assertEquals(cmd.inferenceTimeout, Some(120000L))
    assertEquals(cmd.maxPatchLines, Some(500))
    assertEquals(cmd.maxArtifactDisk, Some(268435456L))
    assertEquals(cmd.maxRepairTokens, Some(100000L))
    assertEquals(cmd.maxExploratorySteps, Some(20))
    assertEquals(cmd.changedFiles, Some(List("a.ts", "b.ts")))
    assertEquals(cmd.gitRef, Some("main"))
    assertEquals(cmd.mode, Some("Full"))
    assertEquals(cmd.runId, Some("custom-id"))
    assert(cmd.replayInference)
  }

  test("parses global --format json") {
    val result = CommandParsers.parse(Array("--format", "json", "status"))
    assert(result.isRight)
    assertEquals(result.toOption.get.global.format, OutputFormat.Json: OutputFormat)
  }

  test("parses global --verbose and --quiet") {
    val result = CommandParsers.parse(Array("--verbose", "status"))
    assert(result.isRight)
    assert(result.toOption.get.global.verbose)

    val result2 = CommandParsers.parse(Array("--quiet", "status"))
    assert(result2.isRight)
    assert(result2.toOption.get.global.quiet)
  }

  // --- Rejects invalid input ---

  test("rejects invalid duration syntax") {
    val result = CommandParsers.parseDuration("abc", "--test")
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Invalid duration"))
  }

  test("rejects invalid size syntax") {
    val result = CommandParsers.parseSize("abc", "--test")
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Invalid size"))
  }

  test("rejects unknown command") {
    val result = CommandParsers.parse(Array("unknown-cmd"))
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Unknown command"))
  }

  test("rejects run without --task") {
    val result = CommandParsers.parse(Array("run"))
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("requires --task"))
  }

  test("rejects resume without --run-id") {
    val result = CommandParsers.parse(Array("resume"))
    assert(result.isLeft)
  }

  test("rejects clean without --run-id, --all, or --max-age") {
    val result = CommandParsers.parse(Array("clean"))
    assert(result.isLeft)
  }

  // --- Duration / size parsing ---

  test("parseDuration handles seconds suffix") {
    assertEquals(CommandParsers.parseDuration("30s", "--t"), Right(30000L))
  }

  test("parseDuration handles minutes suffix") {
    assertEquals(CommandParsers.parseDuration("5m", "--t"), Right(300000L))
  }

  test("parseDuration handles hours suffix") {
    assertEquals(CommandParsers.parseDuration("1h", "--t"), Right(3600000L))
  }

  test("parseDuration handles ms suffix") {
    assertEquals(CommandParsers.parseDuration("500ms", "--t"), Right(500L))
  }

  test("parseDuration handles plain number as seconds") {
    assertEquals(CommandParsers.parseDuration("60", "--t"), Right(60000L))
  }

  test("parseSize handles MB suffix") {
    assertEquals(CommandParsers.parseSize("512MB", "--t"), Right(536870912L))
  }

  test("parseSize handles GB suffix") {
    assertEquals(CommandParsers.parseSize("1GB", "--t"), Right(1073741824L))
  }

  test("parseSize handles plain number as bytes") {
    assertEquals(CommandParsers.parseSize("1024", "--t"), Right(1024L))
  }

  // --- No command specified ---
  test("no command returns error") {
    val result = CommandParsers.parse(Array.empty[String])
    assert(result.isLeft)
  }
}
