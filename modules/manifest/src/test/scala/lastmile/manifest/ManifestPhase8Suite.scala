package lastmile.manifest

// Phase 8: Tests for remaining manifest sections (auth, verification, inference, policies, observability)
class ManifestPhase8Suite extends munit.FunSuite {

  test("auth section parses correctly") {
    val yaml = """
version: 1
app:
  type: fullstack
  root_url: http://localhost:3000
services:
  api:
    kind: api
    startup_mode: script
    startup_command: npm start
auth:
  mode: browser_form_login
  login_url: http://localhost:3000/login
  credentials:
    username: admin
    password: secret123
  storage_state_output: auth-state.json
"""
    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        assert(m.auth.isDefined)
        val auth = m.auth.get
        assertEquals(auth.mode, "browser_form_login")
        assertEquals(auth.loginUrl, Some("http://localhost:3000/login"))
        assert(auth.credentials.isDefined)
        assertEquals(auth.credentials.get("username"), "admin")
        assertEquals(auth.storageStateOutput, Some("auth-state.json"))
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Parse failed: ${errors.mkString(", ")}")
    }
  }

  test("verification section parses correctly") {
    val yaml = """
version: 1
app:
  type: fullstack
  root_url: http://localhost:3000
services:
  api:
    kind: api
    startup_mode: script
    startup_command: npm start
verification:
  default_verifier_timeout_ms: 30000
  default_browser_action_timeout_ms: 10000
  max_retries: 3
  retry_delay_ms: 2000
  screenshot_on_failure: true
  screenshot_on_complete: false
  trace_enabled: true
"""
    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        assert(m.verification.isDefined)
        val v = m.verification.get
        assertEquals(v.defaultVerifierTimeoutMs, Some(30000))
        assertEquals(v.defaultBrowserActionTimeoutMs, Some(10000))
        assertEquals(v.maxRetries, Some(3))
        assertEquals(v.retryDelayMs, Some(2000))
        assertEquals(v.screenshotOnFailure, Some(true))
        assertEquals(v.screenshotOnComplete, Some(false))
        assertEquals(v.traceEnabled, Some(true))
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Parse failed: ${errors.mkString(", ")}")
    }
  }

  test("inference section parses correctly with model overrides") {
    val yaml = """
version: 1
app:
  type: fullstack
  root_url: http://localhost:3000
services:
  api:
    kind: api
    startup_mode: script
    startup_command: npm start
inference:
  default_provider: anthropic
  models:
    requirement_compiler: claude-sonnet-4-20250514
    failure_analyzer: claude-haiku-3-20250414
"""
    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        assert(m.inference.isDefined)
        val inf = m.inference.get
        assertEquals(inf.defaultProvider, Some("anthropic"))
        assert(inf.models.isDefined)
        assertEquals(inf.models.get.requirementCompiler, Some("claude-sonnet-4-20250514"))
        assertEquals(inf.models.get.failureAnalyzer, Some("claude-haiku-3-20250414"))
        assert(inf.models.get.verifierGenerator.isEmpty)
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Parse failed: ${errors.mkString(", ")}")
    }
  }

  test("policies section parses correctly") {
    val yaml = """
version: 1
app:
  type: fullstack
  root_url: http://localhost:3000
services:
  api:
    kind: api
    startup_mode: script
    startup_command: npm start
policies:
  max_attempts: 3
  run_timeout_ms: 1800000
  attempt_timeout_ms: 600000
  max_patch_lines: 1000
  max_artifact_disk_bytes: 268435456
  allowed_hosts:
    - localhost
    - 127.0.0.1
  browser_allowed_origins:
    - http://localhost:*
  allow_git_push: false
  allow_db_drop: false
"""
    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        assert(m.policies.isDefined)
        val p = m.policies.get
        assertEquals(p.maxAttempts, Some(3))
        assertEquals(p.runTimeoutMs, Some(1800000L))
        assertEquals(p.attemptTimeoutMs, Some(600000L))
        assertEquals(p.maxPatchLines, Some(1000))
        assertEquals(p.maxArtifactDiskBytes, Some(268435456L))
        assert(p.allowedHosts.isDefined)
        assertEquals(p.allowedHosts.get.size, 2)
        assertEquals(p.allowGitPush, Some(false))
        assertEquals(p.allowDbDrop, Some(false))
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Parse failed: ${errors.mkString(", ")}")
    }
  }

  test("observability section with log_queries parses correctly") {
    val yaml = """
version: 1
app:
  type: fullstack
  root_url: http://localhost:3000
services:
  api:
    kind: api
    startup_mode: script
    startup_command: npm start
observability:
  log_queries:
    - id: error_count
      service_id: api
      query: "grep -c ERROR"
      description: Count errors in API logs
  taps:
    - tap_id: api-log
      service_id: api
      tap_type: log_tail
"""
    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        assert(m.observability.isDefined)
        val obs = m.observability.get
        assert(obs.logQueries.isDefined)
        assertEquals(obs.logQueries.get.size, 1)
        assertEquals(obs.logQueries.get.head.id, "error_count")
        assertEquals(obs.logQueries.get.head.serviceId, "api")
        assert(obs.taps.isDefined)
        assertEquals(obs.taps.get.size, 1)
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Parse failed: ${errors.mkString(", ")}")
    }
  }

  test("all sections together in a full manifest") {
    val yaml = """
version: 1
app:
  type: fullstack
  root_url: http://localhost:3000
  api_url: http://localhost:4000
services:
  frontend:
    kind: frontend
    startup_mode: script
    startup_command: npm start
  api:
    kind: api
    startup_mode: script
    startup_command: npm run serve
fixtures:
  reset_strategy: soft
  seed_steps:
    - step_id: seed_db
      command: npm run seed
auth:
  mode: static_test_token
  static_token: test-token-123
verification:
  max_retries: 2
  trace_enabled: true
inference:
  default_provider: mock
policies:
  max_attempts: 3
observability:
  log_queries:
    - id: health
      service_id: api
      query: health
"""
    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        assertEquals(m.version, 1)
        assertEquals(m.services.size, 2)
        assert(m.fixtures.isDefined)
        assert(m.auth.isDefined)
        assertEquals(m.auth.get.mode, "static_test_token")
        assert(m.verification.isDefined)
        assertEquals(m.verification.get.maxRetries, Some(2))
        assert(m.inference.isDefined)
        assertEquals(m.inference.get.defaultProvider, Some("mock"))
        assert(m.policies.isDefined)
        assertEquals(m.policies.get.maxAttempts, Some(3))
        assert(m.observability.isDefined)
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Parse failed: ${errors.mkString(", ")}")
    }
  }

  test("auth section requires mode field") {
    val yaml = """
version: 1
app:
  type: fullstack
  root_url: http://localhost:3000
services:
  api:
    kind: api
    startup_mode: script
    startup_command: npm start
auth:
  login_url: http://localhost:3000/login
"""
    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(_) =>
        fail("Should fail when auth.mode is missing")
      case ManifestParser.ParseFailure(errors) =>
        assert(errors.exists(_.contains("auth.mode")))
    }
  }

  test("optional sections can be omitted without error") {
    val yaml = """
version: 1
app:
  type: api
  root_url: http://localhost:4000
services:
  api:
    kind: api
    startup_mode: script
    startup_command: npm start
"""
    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        assert(m.auth.isEmpty)
        assert(m.verification.isEmpty)
        assert(m.inference.isEmpty)
        assert(m.policies.isEmpty)
        assert(m.observability.isEmpty)
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Parse failed: ${errors.mkString(", ")}")
    }
  }
}
