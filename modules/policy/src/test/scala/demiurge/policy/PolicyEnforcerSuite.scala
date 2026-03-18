package demiurge.policy

import munit.FunSuite
import demiurge.model._

class PolicyEnforcerSuite extends FunSuite {

  private val defaultFsPolicy = FilesystemPolicy(
    allowedWritePaths = List("/work/**"),
    forbiddenWritePaths = List("/etc/**", "/usr/**"),
    allowDeletePaths = List("/work/tmp/**"),
  )

  private val defaultNetPolicy = NetworkPolicy(
    allowedHosts = List("example.com", "api.example.com"),
    allowedPorts = List(80, 443, 8080),
    allowExternalEgress = false,
    externalAllowlist = List("api.anthropic.com"),
  )

  private val defaultBrowserPolicy = BrowserPolicy(
    allowedOrigins = List("http://localhost"),
    forbiddenOrigins = List("http://evil.com"),
    maxConcurrentContexts = 1,
  )

  private val defaultToolPolicy = ToolPolicy(
    allowedTools = List("read_file", "write_file"),
    forbiddenTools = List("rm_rf"),
    requireApprovalTools = List("run_command"),
  )

  private val defaultDestructivePolicy = DestructiveActionPolicy(
    allowGitCommit = true,
    allowGitPush = false,
    allowGitBranch = true,
    allowDbWrite = false,
    allowDbDrop = false,
    allowDockerVolumeRemove = false,
    allowExternalSubmission = false,
  )

  // --- Filesystem tests ---

  test("filesystem write allowed within allowed paths") {
    val result = PolicyEnforcer.validateFilesystemWrite("/work/src/main.scala", defaultFsPolicy, "/work")
    assertEquals(result, None)
  }

  test("filesystem write forbidden in forbidden paths") {
    val result = PolicyEnforcer.validateFilesystemWrite("/etc/passwd", defaultFsPolicy, "/work")
    assert(result.isDefined)
    assert(result.get.message.contains("forbidden"))
  }

  test("filesystem write rejected outside allowed paths") {
    val result = PolicyEnforcer.validateFilesystemWrite("/home/user/file.txt", defaultFsPolicy, "/work")
    assert(result.isDefined)
    assert(result.get.message.contains("not in allowed"))
  }

  test("filesystem delete allowed in allowed delete paths") {
    val result = PolicyEnforcer.validateFilesystemDelete("/work/tmp/cache.dat", defaultFsPolicy, "/work")
    assertEquals(result, None)
  }

  test("filesystem delete rejected outside allowed delete paths") {
    val result = PolicyEnforcer.validateFilesystemDelete("/work/src/main.scala", defaultFsPolicy, "/work")
    assert(result.isDefined)
    assert(result.get.message.contains("not in allowed delete"))
  }

  // --- Network tests ---

  test("localhost network requests always allowed") {
    val result = PolicyEnforcer.validateNetworkRequest("localhost", 8080, defaultNetPolicy)
    assertEquals(result, None)
  }

  test("127.0.0.1 network requests always allowed") {
    val result = PolicyEnforcer.validateNetworkRequest("127.0.0.1", 3000, defaultNetPolicy)
    assertEquals(result, None)
  }

  test("allowed host on allowed port passes") {
    val result = PolicyEnforcer.validateNetworkRequest("api.example.com", 443, defaultNetPolicy)
    assertEquals(result, None)
  }

  test("external egress blocked when disabled") {
    val result = PolicyEnforcer.validateNetworkRequest("evil.com", 443, defaultNetPolicy)
    assert(result.isDefined)
    assert(result.get.message.contains("blocked"))
  }

  test("external allowlist honored when egress disabled") {
    val result = PolicyEnforcer.validateNetworkRequest("api.anthropic.com", 443, defaultNetPolicy)
    assertEquals(result, None)
  }

  test("disallowed port rejected") {
    val result = PolicyEnforcer.validateNetworkRequest("example.com", 9999, defaultNetPolicy)
    assert(result.isDefined)
    assert(result.get.message.contains("port"))
  }

  // --- Browser tests ---

  test("allowed browser origin passes") {
    val result = PolicyEnforcer.validateBrowserOrigin("http://localhost:3000/page", defaultBrowserPolicy)
    assertEquals(result, None)
  }

  test("forbidden browser origin blocked") {
    val result = PolicyEnforcer.validateBrowserOrigin("http://evil.com/hack", defaultBrowserPolicy)
    assert(result.isDefined)
    assert(result.get.message.contains("forbidden"))
  }

  test("unknown browser origin rejected when allowedOrigins set") {
    val result = PolicyEnforcer.validateBrowserOrigin("http://other.com/page", defaultBrowserPolicy)
    assert(result.isDefined)
    assert(result.get.message.contains("not in allowed"))
  }

  // --- Tool tests ---

  test("allowed tool passes") {
    val result = PolicyEnforcer.validateToolUsage("read_file", defaultToolPolicy)
    assertEquals(result, Right(false))
  }

  test("forbidden tool rejected") {
    val result = PolicyEnforcer.validateToolUsage("rm_rf", defaultToolPolicy)
    assert(result.isLeft)
    assert(result.left.get.message.contains("forbidden"))
  }

  test("approval-required tool returns true") {
    val result = PolicyEnforcer.validateToolUsage("run_command", defaultToolPolicy)
    assertEquals(result, Right(true))
  }

  test("unknown tool rejected when allowedTools set") {
    val result = PolicyEnforcer.validateToolUsage("unknown_tool", defaultToolPolicy)
    assert(result.isLeft)
    assert(result.left.get.message.contains("not in allowed"))
  }

  // --- Destructive action tests ---

  test("allowed destructive action passes") {
    val result = PolicyEnforcer.validateDestructiveAction("git_commit", defaultDestructivePolicy)
    assertEquals(result, None)
  }

  test("forbidden destructive action blocked") {
    val result = PolicyEnforcer.validateDestructiveAction("git_push", defaultDestructivePolicy)
    assert(result.isDefined)
    assert(result.get.message.contains("not allowed"))
  }

  test("db_write blocked when not allowed") {
    val result = PolicyEnforcer.validateDestructiveAction("db_write", defaultDestructivePolicy)
    assert(result.isDefined)
  }

  test("unknown destructive action passes by default") {
    val result = PolicyEnforcer.validateDestructiveAction("unknown_action", defaultDestructivePolicy)
    assertEquals(result, None)
  }

  // --- Default policy snapshot ---

  test("defaultPolicySnapshot creates valid snapshot") {
    val budget = ExecutionBudget(
      runTimeoutMs = 3600000L,
      attemptTimeoutMs = 600000L,
      verifierTimeoutMs = 30000L,
      browserActionTimeoutMs = 15000L,
      repairBackendTimeoutMs = 120000L,
      inferenceTimeoutMs = 60000L,
      softResetTimeoutMs = 60000L,
      degradedRecoveryTimeoutMs = 30000L,
      maxAttempts = 5,
      maxRepairRetriesPerAttempt = 2,
      maxRepairTokensPerInvocation = 8192L,
      maxExploratorySteps = 10,
      maxEnvBootRetries = 3,
      maxArtifactDiskBytes = 500000000L,
      maxLogCaptureBytes = 10000000L,
      maxPatchLines = 1000,
      maxServiceRestarts = 3,
      healthCheckIntervalMs = 5000,
      healthCheckMaxFailures = 3,
    )
    val snapshot = PolicyEnforcer.defaultPolicySnapshot("run-1", "/work", budget)

    assertEquals(snapshot.runId, "run-1")
    assert(snapshot.policySnapshotId.nonEmpty)
    assert(snapshot.filesystemPolicy.forbiddenWritePaths.nonEmpty)
    assert(!snapshot.networkPolicy.allowExternalEgress)
    assertEquals(snapshot.browserPolicy.maxConcurrentContexts, 1)
    assertEquals(snapshot.executionBudget.maxAttempts, 5)
  }
}
