package demiurge.cli.Commands

import java.nio.file.{Files, Path}

import io.circe._
import io.circe.syntax._

import demiurge.agent.AgentConfig
import demiurge.worker.WorkerProcessManager

// Phase 1: AgentInitExecutor — uses Claude Code CLI (via the worker process)
// to explore a repository and generate demiurge.yaml + requirements.yaml.
// This is the "smart" path for `demiurge init --smart`.
object AgentInitExecutor {

  case class InitResult(
    success: Boolean,
    demiurgeYaml: Option[String],
    requirementsYaml: Option[String],
    summary: String,
    costUsd: Double = 0.0,
    durationMs: Long = 0,
  )

  /**
   * Execute an agent session to generate configuration files for a repository.
   * The agent reads the repo's source files, understands its structure, and
   * writes demiurge.yaml + requirements.yaml to the repo root.
   */
  def execute(
    repoRoot: Path,
    outputPath: Path,
    force: Boolean,
    quiet: Boolean,
  ): InitResult = {
    val workerPath = resolveWorkerPath()
    if (workerPath.isEmpty) {
      return InitResult(
        success = false,
        demiurgeYaml = None,
        requirementsYaml = None,
        summary = "Cannot run --smart: no worker available. Set DEMIURGE_WORKER_PATH or install the Demiurge worker.",
      )
    }

    val config = AgentConfig.fromEnvironment(AgentConfig(
      timeoutMs = 120000, // 2 min for init — should be quick
      maxTurns = Some(30),
      enableMcpTools = false, // no Demiurge MCP tools needed for init
    ))

    val wm = new WorkerProcessManager(workerPath.get)
    try {
      wm.spawn()

      // Initialize worker with repo root as working directory
      val artifactDir = repoRoot.resolve(".demiurge").resolve("artifacts")
      Files.createDirectories(artifactDir)
      val runId = s"init-${java.util.UUID.randomUUID().toString.take(8)}"

      wm.initialize(artifactDir.toString, repoRoot.toString, runId) match {
        case Right(_) =>
          if (!quiet) System.err.println("[init] Worker initialized for smart init")
        case Left(err) =>
          return InitResult(false, None, None, s"Worker initialization failed: $err")
      }

      val systemPrompt = buildSystemPrompt(outputPath, repoRoot)
      val userPrompt = buildUserPrompt(repoRoot)

      val params = Json.obj(
        "runId"        -> runId.asJson,
        "systemPrompt" -> systemPrompt.asJson,
        "userPrompt"   -> userPrompt.asJson,
        "worktreePath" -> repoRoot.toAbsolutePath.toString.asJson,
        "repoRoot"     -> repoRoot.toAbsolutePath.toString.asJson,
        "serviceIds"   -> Json.arr(),
        "agentConfig"  -> Json.obj(
          "model"          -> config.model.asJson,
          "maxTurns"       -> config.maxTurns.asJson,
          "maxBudgetUsd"   -> config.maxBudgetUsd.asJson,
          "timeoutMs"      -> config.timeoutMs.asJson,
          "enableMcpTools" -> false.asJson,
          "sessionId"      -> config.sessionId.asJson,
          "resume"         -> config.resume.asJson,
          "pathToClaudeCodeExecutable" -> config.pathToClaudeCodeExecutable.asJson,
        ),
      )

      val rpcTimeoutMs = config.timeoutMs + 30000
      val rpcResult = try {
        wm.sendRawRequest("agent/execute", params, rpcTimeoutMs)
      } catch {
        case e: Exception =>
          return InitResult(false, None, None, s"Agent RPC failed: ${e.getMessage}")
      }

      rpcResult match {
        case Left(err) =>
          InitResult(false, None, None, s"Agent execution failed: $err")

        case Right(json) =>
          parseInitResult(json, repoRoot, outputPath)
      }
    } finally {
      try { wm.shutdown() } catch { case _: Exception => }
    }
  }

  private def buildSystemPrompt(outputPath: Path, repoRoot: Path): String = {
    val outputName = outputPath.getFileName.toString
    s"""You are a repository configuration generator for Demiurge, a verification-first
       |code automation system.
       |
       |Your job is to explore this repository thoroughly and generate two configuration files:
       |
       |1. **$outputName** — the main Demiurge manifest describing how to run this application
       |2. **requirements.yaml** — verification requirements that define what "healthy" means
       |
       |## How to explore
       |
       |1. Read the project's README, package.json, docker-compose files, and entry points
       |2. Identify the primary language, framework, and application type
       |3. Find the startup command (e.g. npm start, npm run dev, docker compose up)
       |4. Find the port the application listens on
       |5. Identify health/readiness endpoints
       |6. Identify any environment variables needed (check .env.example, .env.sample, etc.)
       |7. Identify database or other service dependencies
       |8. Find API routes that can be used as verification endpoints
       |
       |## $outputName schema
       |
       |```yaml
       |version: 1
       |
       |app:
       |  type: <api|frontend|fullstack>
       |  root_url: http://localhost:<port>
       |  api_url: http://localhost:<port>/api  # if applicable
       |
       |services:
       |  <service_id>:
       |    kind: <api|frontend|db|cache|queue|worker>
       |    startup_mode: <script|compose>
       |    # If startup_mode=script: startup_command is REQUIRED (shell command to run)
       |    # If startup_mode=compose: compose_target is REQUIRED (docker compose service name)
       |    startup_command: <command to start the service>  # for script mode
       |    compose_target: <compose service name>           # for compose mode
       |    cwd: <working directory, use absolute path>
       |    env_file: <path to .env file if needed>
       |    env:
       |      KEY: "value"  # any env var overrides
       |    ports:
       |      - host: <port>
       |        container: <port>
       |    depends_on:
       |      - <other_service_id>
       |    readiness:
       |      probe_type: <http|tcp>
       |      target: <url or host:port>
       |      interval_ms: 2000
       |      timeout_ms: 30000
       |      max_failures: 15
       |      initial_delay_ms: 3000
       |    required: true
       |
       |verification:
       |  default_verifier_timeout_ms: 30000
       |  max_retries: 2
       |  retry_delay_ms: 2000
       |
       |inference:
       |  default_provider: anthropic
       |
       |policies:
       |  max_attempts: 3
       |  run_timeout_ms: 600000
       |  attempt_timeout_ms: 300000
       |  max_patch_lines: 2000
       |```
       |
       |## requirements.yaml schema
       |
       |```yaml
       |requirements:
       |  - id: <kebab-case-id>
       |    type: <http|tcp|exec|browser_flow|state>
       |    description: <human description>
       |    expected: <url for http, host:port for tcp>
       |    timeout_ms: 10000
       |    retry: 2
       |    severity: <required|important|nice_to_have>
       |```
       |
       |## Common Architecture Patterns
       |
       |### Pattern A: Frontend proxy (VERY COMMON for React/Vue/Svelte apps)
       |Many modern fullstack apps have a separate frontend dev server (Vite, webpack-dev-server,
       |Next.js dev, etc.) that proxies API requests to a backend server. Look for:
       |- A `frontend/`, `client/`, `web/`, or `ui/` directory with its own package.json
       |- A vite.config.ts/js with `server.proxy` configuration
       |- A webpack config with `devServer.proxy`
       |- next.config.js with `rewrites` or API routes
       |
       |When you find this pattern, configure TWO services:
       |1. **backend** (kind: api) — the API server on its own port
       |2. **frontend** (kind: frontend) — the dev server on its own port (usually 3000 or 5173)
       |   - startup_command should include `npm install &&` before `npm run dev` to ensure deps
       |     are available in the working directory
       |   - Set readiness timeout_ms to at least 60000 (npm install + dev server startup)
       |   - The frontend `depends_on` the backend
       |   - Set `required: false` since the backend API is what matters for verification
       |
       |### Pattern B: Monolith-served UI
       |Some apps serve the frontend from the backend (e.g. Express serving static files, or a
       |Java/Scala server with embedded frontend build). In this case, configure ONE service.
       |
       |### Pattern C: Build-tool projects (Bazel, Gradle, Maven, sbt)
       |For projects using build tools, use the build tool's run command directly:
       |- Bazel: `bazel run //path/to:target`
       |- Gradle: `./gradlew bootRun` or `./gradlew run`
       |- Maven: `mvn spring-boot:run` or `mvn exec:java`
       |- sbt: `sbt run`
       |
       |This ensures that code changes made during repair are picked up on restart.
       |Build-tool projects may take 10+ minutes for the initial build in a clean workspace.
       |Set readiness timeout_ms to at least 600000 (10 min) for Bazel/compiled projects.
       |Set initial_delay_ms to at least 15000.
       |Set run_timeout_ms and attempt_timeout_ms high enough to accommodate build time
       |(e.g. run_timeout_ms: 1800000, attempt_timeout_ms: 1200000).
       |
       |## Severity Guidelines for requirements.yaml
       |
       |- `required`: Health endpoints, core public APIs that don't need authentication
       |- `important`: Authenticated API endpoints (will return 401/403 without credentials)
       |- `nice_to_have`: Optional features, documentation endpoints, worker health checks
       |
       |Auth-protected endpoints should NEVER be severity: required — they will always fail
       |without credentials and block the overall verification verdict.
       |
       |## Instructions
       |
       |1. Explore the repository thoroughly using file read and shell tools
       |2. Write $outputName to ${outputPath.toAbsolutePath}
       |3. Write requirements.yaml to ${repoRoot.toAbsolutePath}/requirements.yaml
       |4. Include at least one readiness requirement per service
       |5. Include HTTP requirements for key API endpoints you discover
       |6. Use absolute paths for cwd
       |7. Be specific — use exact startup commands, ports, and paths you find in the code
       |8. If you find a .env or .env.example, reference it with env_file
       |9. Check if endpoints require authentication before setting severity: required
       |
       |Write BOTH files. Do not ask for confirmation.""".stripMargin
  }

  private def buildUserPrompt(repoRoot: Path): String =
    s"""Explore the repository at ${repoRoot.toAbsolutePath} and generate demiurge.yaml
       |and requirements.yaml configuration files.
       |
       |Read the codebase to understand:
       |- What framework and language is used
       |- How to start the application
       |- What port it runs on
       |- What API endpoints exist
       |- What environment setup is needed
       |
       |Then write both config files to the repo root.""".stripMargin

  private def parseInitResult(json: Json, repoRoot: Path, outputPath: Path): InitResult = {
    val c = json.hcursor
    val success    = c.downField("success").as[Boolean].getOrElse(false)
    val resultText = c.downField("resultText").as[String].getOrElse("")
    val costUsd    = c.downField("costUsd").as[Double].getOrElse(0.0)
    val durationMs = c.downField("durationMs").as[Long].getOrElse(0L)

    // Check if the agent actually wrote the files
    // The agent may write to outputPath directly or to repoRoot/outputName
    val demiurgeYaml = if (Files.exists(outputPath)) {
      Some(Files.readString(outputPath))
    } else {
      // Fallback: check if agent wrote to repoRoot/outputName instead
      val fallbackPath = repoRoot.resolve(outputPath.getFileName)
      if (Files.exists(fallbackPath) && fallbackPath != outputPath) {
        // Copy to the intended output path
        Files.copy(fallbackPath, outputPath)
        Some(Files.readString(outputPath))
      } else None
    }

    val reqsPath = repoRoot.resolve("requirements.yaml")
    val requirementsYaml = if (Files.exists(reqsPath)) {
      Some(Files.readString(reqsPath))
    } else None

    InitResult(
      success = success && demiurgeYaml.isDefined,
      demiurgeYaml = demiurgeYaml,
      requirementsYaml = requirementsYaml,
      summary = if (success && demiurgeYaml.isDefined) {
        val reqCount = if (requirementsYaml.isDefined) " + requirements.yaml" else ""
        s"Generated ${outputPath.getFileName}$reqCount (cost: $$${f"$costUsd%.4f"}, ${durationMs}ms)"
      } else {
        s"Agent completed but config files were not written. Agent output: $resultText"
      },
      costUsd = costUsd,
      durationMs = durationMs,
    )
  }

  /** Resolve the worker entry point from environment or standard locations. */
  private def resolveWorkerPath(): Option[Path] = {
    // Check DEMIURGE_WORKER_PATH env var first
    Option(System.getenv("DEMIURGE_WORKER_PATH"))
      .map(Path.of(_))
      .filter(Files.exists(_))
      .orElse {
        // Check standard locations relative to current working directory
        val locations = List(
          "worker/src/index.ts",
          "worker/dist/index.js",
          "node_modules/.bin/demiurge-worker",
        )
        locations.map(Path.of(_)).find(Files.exists(_))
      }
  }
}
