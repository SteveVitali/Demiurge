package demiurge.manifest

import munit.FunSuite

class ManifestParserSuite extends FunSuite {

  test("parses minimal valid demiurge.yaml") {
    val yaml =
      """version: 1
        |app:
        |  type: web
        |  root_url: http://localhost:3000
        |services:
        |  api:
        |    kind: api
        |    startup_mode: script
        |    startup_command: "node server.js"
        |    ports:
        |      - container: 3000
        |    readiness:
        |      probe_type: http
        |      target: http://localhost:3000/health
        |""".stripMargin

    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        assertEquals(m.version, 1)
        assertEquals(m.app.appType, "web")
        assertEquals(m.app.rootUrl, "http://localhost:3000")
        assertEquals(m.services.size, 1)
        assert(m.services.contains("api"))
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Expected success, got errors: ${errors.mkString(", ")}")
    }
  }

  test("parses script-native service") {
    val yaml =
      """version: 1
        |app:
        |  type: web
        |  root_url: http://localhost:3000
        |services:
        |  backend:
        |    kind: api
        |    startup_mode: script
        |    startup_command: "npm start"
        |    cwd: ./backend
        |    env:
        |      NODE_ENV: test
        |      PORT: "3000"
        |    ports:
        |      - host: 3000
        |        container: 3000
        |        protocol: tcp
        |    readiness:
        |      probe_type: http
        |      target: http://localhost:3000/health
        |      interval_ms: 500
        |      timeout_ms: 15000
        |      max_failures: 5
        |      initial_delay_ms: 1000
        |    shutdown_method: sigterm
        |    shutdown_timeout_ms: 5000
        |    required: true
        |""".stripMargin

    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        val svc = m.services("backend")
        assertEquals(svc.kind, "api")
        assertEquals(svc.startupMode, "script")
        assertEquals(svc.startupCommand, Some("npm start"))
        assertEquals(svc.cwd, Some("./backend"))
        assertEquals(svc.env, Some(Map("NODE_ENV" -> "test", "PORT" -> "3000")))
        assertEquals(svc.shutdownMethod, Some("sigterm"))
        assertEquals(svc.shutdownTimeoutMs, Some(5000))
        assertEquals(svc.required, Some(true))

        val readiness = svc.readiness.get
        assertEquals(readiness.probeType, "http")
        assertEquals(readiness.intervalMs, Some(500))
        assertEquals(readiness.timeoutMs, Some(15000))
        assertEquals(readiness.maxFailures, Some(5))
        assertEquals(readiness.initialDelayMs, Some(1000))
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Expected success, got errors: ${errors.mkString(", ")}")
    }
  }

  test("parses compose-native service") {
    val yaml =
      """version: 1
        |app:
        |  type: web
        |  root_url: http://localhost:5432
        |services:
        |  db:
        |    kind: db
        |    startup_mode: compose
        |    compose_target: postgres
        |    ports:
        |      - container: 5432
        |    readiness:
        |      probe_type: tcp
        |      target: "localhost:5432"
        |""".stripMargin

    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        val svc = m.services("db")
        assertEquals(svc.kind, "db")
        assertEquals(svc.startupMode, "compose")
        assertEquals(svc.composeTarget, Some("postgres"))
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Expected success, got errors: ${errors.mkString(", ")}")
    }
  }

  test("fails on missing required service fields") {
    val yaml =
      """version: 1
        |app:
        |  type: web
        |  root_url: http://localhost:3000
        |services:
        |  broken:
        |    startup_mode: script
        |    ports:
        |      - container: 3000
        |""".stripMargin

    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseFailure(errors) =>
        assert(errors.exists(_.contains("kind")), s"Should report missing kind: $errors")
      case ManifestParser.ParseSuccess(_) =>
        fail("Expected parse failure for missing kind")
    }
  }

  test("fails on invalid enum values") {
    val yaml =
      """version: 1
        |app:
        |  type: web
        |  root_url: http://localhost:3000
        |services:
        |  svc:
        |    kind: invalid_kind
        |    startup_mode: script
        |    startup_command: "echo hi"
        |    ports:
        |      - container: 3000
        |""".stripMargin

    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseFailure(errors) =>
        assert(errors.exists(_.contains("unknown value")), s"Should report unknown kind: $errors")
      case ManifestParser.ParseSuccess(_) =>
        fail("Expected failure for invalid enum")
    }
  }

  test("validation fails when readiness and ports are both missing") {
    val yaml =
      """version: 1
        |app:
        |  type: web
        |  root_url: http://localhost:3000
        |services:
        |  svc:
        |    kind: api
        |    startup_mode: script
        |    startup_command: "echo hi"
        |""".stripMargin

    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        val validation = ManifestValidation.validate(m)
        assert(!validation.isValid, "Should fail validation")
        assert(validation.errors.exists(_.contains("ports or readiness")),
          s"Should report missing ports/readiness: ${validation.errors}")
      case ManifestParser.ParseFailure(_) =>
        // Also acceptable — parser may catch it
    }
  }

  test("parses fixtures with seed steps") {
    val yaml =
      """version: 1
        |app:
        |  type: web
        |  root_url: http://localhost:3000
        |services:
        |  api:
        |    kind: api
        |    startup_mode: script
        |    startup_command: "node server.js"
        |    ports:
        |      - container: 3000
        |fixtures:
        |  seed_steps:
        |    - step_id: seed-db
        |      command: "node seed.js"
        |      description: "Seed the database"
        |      timeout_ms: 30000
        |      depends_on_services:
        |        - api
        |  reset_strategy: soft_reset
        |""".stripMargin

    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        assert(m.fixtures.isDefined)
        val steps = m.fixtures.get.seedSteps.get
        assertEquals(steps.size, 1)
        assertEquals(steps.head.stepId, "seed-db")
        assertEquals(steps.head.command, "node seed.js")
        assertEquals(steps.head.timeoutMs, Some(30000))
        assertEquals(steps.head.dependsOnServices, Some(List("api")))
        assertEquals(m.fixtures.get.resetStrategy, Some("soft_reset"))
      case ManifestParser.ParseFailure(errors) =>
        fail(s"Expected success, got errors: ${errors.mkString(", ")}")
    }
  }

  test("validation fails on unknown dependency reference") {
    val yaml =
      """version: 1
        |app:
        |  type: web
        |  root_url: http://localhost:3000
        |services:
        |  api:
        |    kind: api
        |    startup_mode: script
        |    startup_command: "node server.js"
        |    ports:
        |      - container: 3000
        |    depends_on:
        |      - nonexistent
        |""".stripMargin

    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        val validation = ManifestValidation.validate(m)
        assert(!validation.isValid, "Should fail validation")
        assert(validation.errors.exists(_.contains("nonexistent")),
          s"Should reference unknown service: ${validation.errors}")
      case ManifestParser.ParseFailure(_) =>
        // Also acceptable
    }
  }

  test("validation fails on wrong version") {
    val yaml =
      """version: 2
        |app:
        |  type: web
        |  root_url: http://localhost:3000
        |services:
        |  api:
        |    kind: api
        |    startup_mode: script
        |    startup_command: "node server.js"
        |    ports:
        |      - container: 3000
        |""".stripMargin

    ManifestParser.parseString(yaml) match {
      case ManifestParser.ParseSuccess(m) =>
        val validation = ManifestValidation.validate(m)
        assert(!validation.isValid)
        assert(validation.errors.exists(_.contains("version must be 1")))
      case ManifestParser.ParseFailure(_) =>
        // Also acceptable
    }
  }
}
