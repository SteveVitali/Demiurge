package demiurge.orchestrator

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}

import demiurge.model._
import demiurge.persistence._
import demiurge.repair._

class AuthBootstrapSuite extends FunSuite with TestFixtures {

  // --- AuthBootstrapExecutor unit tests ---

  test("StaticTestToken mode creates storage state file") {
    withTempDir { tmpDir =>
      val authConfig = ResolvedAuthConfig(
        mode = AuthMode.StaticTestToken,
        loginUrl = None,
        credentials = Map.empty,
        staticToken = Some("my-test-token"),
        storageStateOutput = None,
      )

      val result = AuthBootstrapExecutor.execute(authConfig, None, tmpDir, "run-auth-001")

      assert(result.success, "Static token auth should succeed")
      assert(result.storageStatePath.isDefined, "Should produce storage state path")
      assert(result.apiHeaders.contains("Authorization"), "Should have Authorization header")
      assertEquals(result.apiHeaders("Authorization"), "Bearer my-test-token")

      // Verify file exists
      val path = result.storageStatePath.get
      assert(Files.exists(java.nio.file.Paths.get(path)), s"Storage state file should exist at $path")
    }
  }

  test("DevBypassHeader mode creates storage state with custom headers") {
    withTempDir { tmpDir =>
      val authConfig = ResolvedAuthConfig(
        mode = AuthMode.DevBypassHeader,
        loginUrl = None,
        credentials = Map("X-Dev-Bypass" -> "true", "X-User-Id" -> "test-user"),
        staticToken = None,
        storageStateOutput = None,
      )

      val result = AuthBootstrapExecutor.execute(authConfig, None, tmpDir, "run-auth-002")

      assert(result.success, "Dev bypass header auth should succeed")
      assert(result.storageStatePath.isDefined, "Should produce storage state path")
      assertEquals(result.apiHeaders("X-Dev-Bypass"), "true")
      assertEquals(result.apiHeaders("X-User-Id"), "test-user")
    }
  }

  test("SeededLocalSession mode creates placeholder storage state") {
    withTempDir { tmpDir =>
      val authConfig = ResolvedAuthConfig(
        mode = AuthMode.SeededLocalSession,
        loginUrl = None,
        credentials = Map.empty,
        staticToken = None,
        storageStateOutput = None,
      )

      val result = AuthBootstrapExecutor.execute(authConfig, None, tmpDir, "run-auth-003")

      assert(result.success, "Seeded local session should succeed")
      assert(result.storageStatePath.isDefined, "Should produce storage state path")
    }
  }

  test("BrowserFormLogin without worker fails gracefully") {
    withTempDir { tmpDir =>
      val authConfig = ResolvedAuthConfig(
        mode = AuthMode.BrowserFormLogin,
        loginUrl = Some("http://localhost:3000/login"),
        credentials = Map("username" -> "test", "password" -> "test"),
        staticToken = None,
        storageStateOutput = None,
      )

      val result = AuthBootstrapExecutor.execute(authConfig, None, tmpDir, "run-auth-004")

      assert(!result.success, "Browser form login without worker should fail")
      assert(result.errorMessage.isDefined, "Should have error message")
      assert(result.errorMessage.get.contains("worker"),
        s"Error should mention worker: ${result.errorMessage.get}")
    }
  }

  // --- Orchestrator auth integration tests ---

  test("auth bootstrap enters BootstrappingAuth when auth configured") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "auth-orch-001"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        // Config with auth
        val resolvedConfig = makeResolvedConfigWithAuth()
        val configResolver = new demiurge.config.ConfigResolver {
          override def resolve(
            repoPath: Path,
            taskText: String,
            changedFiles: Option[List[String]],
            inspection: RepoInspectionReport,
            inferenceService: Option[demiurge.inference.InferenceService],
          ): ResolvedConfig = resolvedConfig
        }

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          configResolver = Some(configResolver),
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Verify BootstrappingAuth was entered
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))
        assert(toStatuses.contains("BootstrappingAuth"),
          s"Should have entered BootstrappingAuth: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("auth skipped when no auth in resolved config") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "auth-orch-002"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        // No configResolver → no auth config → no BootstrappingAuth
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
        )

        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Verify BootstrappingAuth was NOT entered
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))
        assert(!toStatuses.contains("BootstrappingAuth"),
          s"Should NOT have entered BootstrappingAuth without auth config: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("auth failure is non-fatal — continues to ReadyToVerify") {
    withTempGitRepoAndDb { (repoRoot, conn) =>
      implicit val c: java.sql.Connection = conn
      val runId = "auth-orch-003"
      val worktreePath = WorktreeManager.create(repoRoot, runId, gitRef = Some("HEAD"))
      val lockPath = LockManager.acquire(repoRoot, runId, worktreePath)

      try {
        SignalHandler.reset()
        val run = makeRun(runId, repoRoot, worktreePath, lockPath)
        TaskRunRepo.insert(run)

        // Config with BrowserFormLogin auth (requires worker, which we don't provide)
        val resolvedConfig = makeResolvedConfigWithBrowserAuth()
        val configResolver = new demiurge.config.ConfigResolver {
          override def resolve(
            repoPath: Path,
            taskText: String,
            changedFiles: Option[List[String]],
            inspection: RepoInspectionReport,
            inferenceService: Option[demiurge.inference.InferenceService],
          ): ResolvedConfig = resolvedConfig
        }

        val ctx = RunContext(run = run, repoRoot = repoRoot, worktreePath = worktreePath, conn = conn)
        val finalRun = RunOrchestrator.execute(
          ctx, StubRepoInspector, StubRequirementCompiler, StubEnvironmentPlanner, StubRuntimeSupervisor,
          configResolver = Some(configResolver),
          // No workerManager → auth will fail
        )

        // Auth failure should be non-fatal — run continues to Succeeded
        assertEquals(finalRun.status, RunStatus.Succeeded)

        // Verify both BootstrappingAuth and ReadyToVerify were entered
        val events = EventRepo.listByRunId(runId, limit = 200)
        val toStatuses = events.filter(_.eventType == "state_transition")
          .flatMap(_.correlationFields.get("to_status"))
        assert(toStatuses.contains("BootstrappingAuth"),
          s"Should have entered BootstrappingAuth: $toStatuses")
        assert(toStatuses.contains("ReadyToVerify"),
          s"Should have continued to ReadyToVerify: $toStatuses")
      } finally {
        LockManager.release(repoRoot)
        WorktreeManager.remove(repoRoot, runId)
      }
    }
  }

  test("storage state path propagated from auth bootstrap") {
    withTempDir { tmpDir =>
      val authConfig = ResolvedAuthConfig(
        mode = AuthMode.StaticTestToken,
        loginUrl = None,
        credentials = Map.empty,
        staticToken = Some("propagation-test-token"),
        storageStateOutput = None,
      )

      val result = AuthBootstrapExecutor.execute(authConfig, None, tmpDir, "run-auth-prop")

      assert(result.success)
      assert(result.storageStatePath.isDefined)

      // Verify the file content is valid JSON with the token
      val content = new String(Files.readAllBytes(java.nio.file.Paths.get(result.storageStatePath.get)))
      assert(content.contains("Bearer propagation-test-token"),
        s"Storage state should contain token: $content")
    }
  }

  // --- Helper: create ResolvedConfig with StaticTestToken auth ---
  private def makeResolvedConfigWithAuth(): ResolvedConfig = {
    ResolvedConfig(
      app = ResolvedAppConfig(appType = "web", rootUrl = "http://localhost:3000", apiUrl = None),
      services = Nil,
      fixtures = None,
      auth = Some(ResolvedAuthConfig(
        mode = AuthMode.StaticTestToken,
        loginUrl = None,
        credentials = Map.empty,
        staticToken = Some("test-token"),
        storageStateOutput = None,
      )),
      verification = ResolvedVerificationConfig(
        defaultVerifierTimeoutMs = 5000,
        defaultBrowserActionTimeoutMs = 5000,
        maxRetries = 0,
        retryDelayMs = 100,
        screenshotOnFailure = false,
        screenshotOnComplete = false,
        traceEnabled = false,
      ),
      inference = ResolvedInferenceConfig(
        defaultProvider = InferenceProvider.Mock,
        models = Map.empty,
      ),
      policies = ResolvedPoliciesConfig(
        maxAttempts = 5,
        runTimeoutMs = 300000L,
        attemptTimeoutMs = 60000L,
        maxPatchLines = 1000,
        maxArtifactDiskBytes = 104857600L,
        allowedHosts = Nil,
        browserAllowedOrigins = Nil,
        allowGitPush = false,
        allowDbDrop = false,
      ),
      observability = None,
      provenance = ConfigProvenance(
        manifestSource = ConfigSource.Default,
        requirementSources = Map.empty,
        serviceSources = Map.empty,
        resolvedAt = Instant.now(),
      ),
    )
  }

  // --- Helper: create ResolvedConfig with BrowserFormLogin auth (will fail without worker) ---
  private def makeResolvedConfigWithBrowserAuth(): ResolvedConfig = {
    makeResolvedConfigWithAuth().copy(
      auth = Some(ResolvedAuthConfig(
        mode = AuthMode.BrowserFormLogin,
        loginUrl = Some("http://localhost:3000/login"),
        credentials = Map("username" -> "test", "password" -> "test"),
        staticToken = None,
        storageStateOutput = None,
      )),
    )
  }
}
