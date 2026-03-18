package demiurge.inspector

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

import io.circe.parser.{parse => parseJson}
import io.circe.Json

import demiurge.model._
import demiurge.manifest.{ManifestParser, ManifestValidation}

// Deterministic filesystem-based repo inspector.
// Detects compose files, package.json (proper JSON parsing), language/framework hints,
// manifest files, monorepo structures, .env files, database dependencies, and route patterns.
// No LLM or inference — purely filesystem scanning.
object RepoInspectorImpl extends RepoInspector {

  private val composeFileNames = Set(
    "compose.yaml", "compose.yml",
    "docker-compose.yaml", "docker-compose.yml",
  )

  override def inspect(runId: String, repoRoot: Path, changedFiles: Option[List[String]]): RepoInspectionReport = {
    val warnings = scala.collection.mutable.ListBuffer[String]()

    // Read and parse package.json once
    val packageJsonContent = readFileContent(repoRoot, "package.json")
    val packageJsonParsed = packageJsonContent.flatMap(c => parseJson(c).toOption)

    val languages = detectLanguages(repoRoot, packageJsonContent, packageJsonParsed)
    val frameworks = detectFrameworks(repoRoot, packageJsonParsed)
    val candidateServices = detectCandidateServices(repoRoot, packageJsonParsed)
    val startupCommands = detectStartupCommands(repoRoot, packageJsonParsed)
    val healthEndpointHints = detectHealthEndpoints(repoRoot, packageJsonParsed)
    val dbDependencies = detectDbDependencies(repoRoot, packageJsonParsed)
    val manifestsFound = detectManifests(repoRoot)
    val envFileHints = detectEnvFiles(repoRoot)
    val apiBasePaths = detectApiBasePaths(repoRoot)
    val authHints = detectAuthHints(repoRoot, packageJsonParsed)
    val testFrameworkHints = detectTestFrameworks(packageJsonParsed)
    val isMonorepo = detectMonorepo(repoRoot, packageJsonParsed)

    if (isMonorepo) {
      warnings += "Monorepo detected — inspection covers root only. Consider running per-workspace."
    }

    // Changed-file impact analysis
    val impactMap = changedFiles.filter(_.nonEmpty).map { files =>
      buildImpactMap(repoRoot, files)
    }

    RepoInspectionReport(
      reportId = s"inspection-$runId-${UUID.randomUUID().toString.take(8)}",
      runId = runId,
      inspectedAt = Instant.now(),
      repoRoot = repoRoot,
      languages = languages,
      frameworks = frameworks,
      candidateServices = candidateServices,
      startupCommands = startupCommands,
      healthEndpointHints = healthEndpointHints,
      dbDependencies = dbDependencies,
      queueDependencies = Nil,
      frontendEntrypoints = Nil,
      apiBasePaths = apiBasePaths,
      testFrameworkHints = testFrameworkHints,
      authHints = authHints,
      changedSurfaceMap = impactMap,
      manifestsFound = manifestsFound,
      warnings = warnings.toList,
    )
  }

  // --- Language detection ---

  private def detectLanguages(
    repoRoot: Path,
    packageJsonRaw: Option[String],
    packageJson: Option[Json],
  ): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()

    if (packageJsonRaw.isDefined)
      results += ScoredInference("javascript", 0.9, "package.json found")

    if (hasFileWithExtension(repoRoot, ".ts"))
      results += ScoredInference("typescript", 0.9, ".ts files found")

    if (hasFileWithExtension(repoRoot, ".py"))
      results += ScoredInference("python", 0.9, ".py files found")

    if (hasFileWithExtension(repoRoot, ".rb"))
      results += ScoredInference("ruby", 0.8, ".rb files found")

    if (hasFileWithExtension(repoRoot, ".go"))
      results += ScoredInference("go", 0.9, ".go files found")

    if (hasFileWithExtension(repoRoot, ".java"))
      results += ScoredInference("java", 0.9, ".java files found")

    if (hasFileWithExtension(repoRoot, ".scala"))
      results += ScoredInference("scala", 0.9, ".scala files found")

    if (hasFileWithExtension(repoRoot, ".rs"))
      results += ScoredInference("rust", 0.9, ".rs files found")

    results.toList
  }

  // --- Framework detection (proper JSON parsing) ---

  private def detectFrameworks(repoRoot: Path, packageJson: Option[Json]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()

    packageJson.foreach { json =>
      val allDeps = extractAllDependencies(json)

      if (allDeps.contains("next")) results += ScoredInference("nextjs", 0.9, "package.json dependency: next")
      if (allDeps.contains("react")) results += ScoredInference("react", 0.8, "package.json dependency: react")
      if (allDeps.contains("express")) results += ScoredInference("express", 0.9, "package.json dependency: express")
      if (allDeps.contains("fastify")) results += ScoredInference("fastify", 0.9, "package.json dependency: fastify")
      if (allDeps.contains("koa")) results += ScoredInference("koa", 0.8, "package.json dependency: koa")
      if (allDeps.contains("hapi") || allDeps.contains("@hapi/hapi"))
        results += ScoredInference("hapi", 0.8, "package.json dependency: hapi")
      if (allDeps.contains("vue")) results += ScoredInference("vue", 0.8, "package.json dependency: vue")
      if (allDeps.contains("nuxt") || allDeps.contains("nuxt3"))
        results += ScoredInference("nuxt", 0.8, "package.json dependency: nuxt")
      if (allDeps.contains("@angular/core")) results += ScoredInference("angular", 0.8, "package.json dependency: @angular/core")
      if (allDeps.contains("svelte") || allDeps.contains("@sveltejs/kit"))
        results += ScoredInference("svelte", 0.8, "package.json dependency: svelte")
      if (allDeps.contains("gatsby")) results += ScoredInference("gatsby", 0.7, "package.json dependency: gatsby")
      if (allDeps.contains("remix") || allDeps.contains("@remix-run/node"))
        results += ScoredInference("remix", 0.8, "package.json dependency: remix")
    }

    // Python frameworks
    readFileContent(repoRoot, "requirements.txt").foreach { content =>
      if (content.contains("django") || content.contains("Django"))
        results += ScoredInference("django", 0.9, "requirements.txt: django")
      if (content.contains("flask") || content.contains("Flask"))
        results += ScoredInference("flask", 0.9, "requirements.txt: flask")
      if (content.contains("fastapi") || content.contains("FastAPI"))
        results += ScoredInference("fastapi", 0.9, "requirements.txt: fastapi")
    }

    // Ruby frameworks
    readFileContent(repoRoot, "Gemfile").foreach { content =>
      if (content.contains("'rails'") || content.contains("\"rails\""))
        results += ScoredInference("rails", 0.9, "Gemfile: rails")
      if (content.contains("'sinatra'") || content.contains("\"sinatra\""))
        results += ScoredInference("sinatra", 0.8, "Gemfile: sinatra")
    }

    // Go frameworks
    readFileContent(repoRoot, "go.mod").foreach { content =>
      if (content.contains("gin-gonic")) results += ScoredInference("gin", 0.8, "go.mod: gin-gonic")
      if (content.contains("labstack/echo")) results += ScoredInference("echo", 0.8, "go.mod: echo")
      if (content.contains("gofiber/fiber")) results += ScoredInference("fiber", 0.8, "go.mod: fiber")
    }

    results.toList
  }

  // --- Candidate service detection ---

  private def detectCandidateServices(repoRoot: Path, packageJson: Option[Json]): List[CandidateService] = {
    val results = scala.collection.mutable.ListBuffer[CandidateService]()

    // Detect compose services by parsing compose YAML
    detectComposeServices(repoRoot).foreach(results += _)

    // Detect Node.js app from package.json
    packageJson.foreach { json =>
      val scripts = extractScripts(json)
      val allDeps = extractAllDependencies(json)

      if (scripts.contains("start") || scripts.contains("dev")) {
        val startCmd = scripts.get("start").orElse(scripts.get("dev")).map(s => s"npm run ${if (scripts.contains("start")) "start" else "dev"}")
        val portHint = detectPortFromScripts(scripts, allDeps)
        val isFrontend = allDeps.contains("react") || allDeps.contains("next") || allDeps.contains("vue") || allDeps.contains("@angular/core")

        results += CandidateService(
          serviceId = "node-app",
          kind = if (isFrontend) ServiceKind.Frontend else ServiceKind.Api,
          confidence = 0.8,
          provenance = "package.json with start/dev script",
          startupHint = startCmd,
          portHint = portHint,
          healthHint = portHint.map(p => s"http://localhost:$p/"),
        )
      }
    }

    results.toList
  }

  // --- Startup command detection ---

  private def detectStartupCommands(repoRoot: Path, packageJson: Option[Json]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()

    packageJson.foreach { json =>
      val scripts = extractScripts(json)
      if (scripts.contains("start"))
        results += ScoredInference("npm start", 0.8, "package.json start script")
      if (scripts.contains("dev"))
        results += ScoredInference("npm run dev", 0.7, "package.json dev script")
      if (scripts.contains("serve"))
        results += ScoredInference("npm run serve", 0.6, "package.json serve script")
    }

    composeFileNames.foreach { name =>
      if (fileExists(repoRoot, name))
        results += ScoredInference(s"docker compose -f $name up", 0.7, s"compose file $name")
    }

    // Python
    if (fileExists(repoRoot, "manage.py"))
      results += ScoredInference("python manage.py runserver", 0.8, "Django manage.py found")

    // Ruby
    if (fileExists(repoRoot, "config.ru"))
      results += ScoredInference("bundle exec rackup", 0.7, "config.ru found")

    // Go
    if (fileExists(repoRoot, "main.go"))
      results += ScoredInference("go run main.go", 0.7, "main.go found")

    results.toList
  }

  // --- Health endpoint detection ---

  private def detectHealthEndpoints(repoRoot: Path, packageJson: Option[Json]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()
    val allDeps = packageJson.map(extractAllDependencies).getOrElse(Set.empty)

    // Scan source files for route definitions with common health patterns
    val healthPatterns = List("/health", "/healthz", "/api/health", "/status", "/readiness", "/ready")
    val portHint = packageJson.flatMap(json => detectPortFromScripts(extractScripts(json), allDeps)).getOrElse(3000)

    scanSourceFilesForPatterns(repoRoot, healthPatterns).foreach { pattern =>
      results += ScoredInference(s"http://localhost:$portHint$pattern", 0.6, s"health pattern '$pattern' found in source")
    }

    // Default health endpoint if express/fastify detected
    if (results.isEmpty && (allDeps.contains("express") || allDeps.contains("fastify"))) {
      results += ScoredInference(s"http://localhost:$portHint/health", 0.4, "default health endpoint guess for express/fastify")
    }

    results.toList
  }

  // --- Database dependency detection ---

  private def detectDbDependencies(repoRoot: Path, packageJson: Option[Json]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()
    val allDeps = packageJson.map(extractAllDependencies).getOrElse(Set.empty)

    // Node.js DB packages
    if (allDeps.contains("pg") || allDeps.contains("pg-promise"))
      results += ScoredInference("postgresql", 0.9, "package.json: pg driver")
    if (allDeps.contains("mysql2") || allDeps.contains("mysql"))
      results += ScoredInference("mysql", 0.9, "package.json: mysql driver")
    if (allDeps.contains("mongodb") || allDeps.contains("mongoose"))
      results += ScoredInference("mongodb", 0.9, "package.json: mongodb/mongoose")
    if (allDeps.contains("redis") || allDeps.contains("ioredis"))
      results += ScoredInference("redis", 0.8, "package.json: redis driver")
    if (allDeps.contains("sqlite3") || allDeps.contains("better-sqlite3"))
      results += ScoredInference("sqlite", 0.8, "package.json: sqlite driver")
    if (allDeps.contains("prisma") || allDeps.contains("@prisma/client"))
      results += ScoredInference("prisma-orm", 0.7, "package.json: prisma")
    if (allDeps.contains("typeorm"))
      results += ScoredInference("typeorm", 0.7, "package.json: typeorm")
    if (allDeps.contains("sequelize"))
      results += ScoredInference("sequelize-orm", 0.7, "package.json: sequelize")
    if (allDeps.contains("drizzle-orm"))
      results += ScoredInference("drizzle-orm", 0.7, "package.json: drizzle")

    // Check compose file for DB services
    parseComposeFile(repoRoot).foreach { services =>
      services.foreach { case (name, image) =>
        if (image.contains("postgres")) results += ScoredInference("postgresql", 0.9, s"compose service: $name (postgres)")
        if (image.contains("mysql") || image.contains("mariadb")) results += ScoredInference("mysql", 0.9, s"compose service: $name")
        if (image.contains("mongo")) results += ScoredInference("mongodb", 0.9, s"compose service: $name (mongo)")
        if (image.contains("redis")) results += ScoredInference("redis", 0.8, s"compose service: $name (redis)")
      }
    }

    results.toList.distinctBy(_.value)
  }

  // --- Auth hints ---

  private def detectAuthHints(repoRoot: Path, packageJson: Option[Json]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()
    val allDeps = packageJson.map(extractAllDependencies).getOrElse(Set.empty)

    if (allDeps.contains("passport")) results += ScoredInference("passport", 0.8, "package.json: passport")
    if (allDeps.contains("jsonwebtoken") || allDeps.contains("jose"))
      results += ScoredInference("jwt", 0.8, "package.json: JWT library")
    if (allDeps.contains("next-auth") || allDeps.contains("@auth/core"))
      results += ScoredInference("next-auth", 0.8, "package.json: next-auth")
    if (allDeps.contains("bcrypt") || allDeps.contains("bcryptjs"))
      results += ScoredInference("password-hashing", 0.6, "package.json: bcrypt")

    results.toList
  }

  // --- Test framework detection ---

  private def detectTestFrameworks(packageJson: Option[Json]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()
    val allDeps = packageJson.map(extractAllDependencies).getOrElse(Set.empty)

    if (allDeps.contains("jest")) results += ScoredInference("jest", 0.9, "package.json: jest")
    if (allDeps.contains("mocha")) results += ScoredInference("mocha", 0.8, "package.json: mocha")
    if (allDeps.contains("vitest")) results += ScoredInference("vitest", 0.9, "package.json: vitest")
    if (allDeps.contains("playwright") || allDeps.contains("@playwright/test"))
      results += ScoredInference("playwright", 0.8, "package.json: playwright")
    if (allDeps.contains("cypress")) results += ScoredInference("cypress", 0.8, "package.json: cypress")

    results.toList
  }

  // --- Monorepo detection ---

  private def detectMonorepo(repoRoot: Path, packageJson: Option[Json]): Boolean = {
    // Check for workspaces field in package.json
    val hasWorkspaces = packageJson.exists { json =>
      json.hcursor.downField("workspaces").succeeded
    }

    // Check for common monorepo tools
    val hasMonorepoConfig = fileExists(repoRoot, "lerna.json") ||
      fileExists(repoRoot, "pnpm-workspace.yaml") ||
      fileExists(repoRoot, "turbo.json") ||
      fileExists(repoRoot, "nx.json")

    hasWorkspaces || hasMonorepoConfig
  }

  // --- .env file detection ---

  private def detectEnvFiles(repoRoot: Path): List[String] = {
    val envNames = List(".env", ".env.example", ".env.sample", ".env.local", ".env.development")
    envNames.filter(name => fileExists(repoRoot, name))
  }

  // --- API base path detection ---

  private def detectApiBasePaths(repoRoot: Path): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()
    val routePatterns = List("/api/", "/v1/", "/v2/")

    scanSourceFilesForPatterns(repoRoot, routePatterns).foreach { pattern =>
      results += ScoredInference(pattern, 0.5, s"route pattern '$pattern' found in source")
    }

    results.toList.distinctBy(_.value)
  }

  // --- Manifest detection ---

  private def detectManifests(repoRoot: Path): List[ManifestRef] = {
    val results = scala.collection.mutable.ListBuffer[ManifestRef]()

    // Check for demiurge.yaml
    val demiurgeYaml = repoRoot.resolve("demiurge.yaml")
    if (Files.exists(demiurgeYaml)) {
      val parseResult = ManifestParser.parseFile(demiurgeYaml)
      parseResult match {
        case ManifestParser.ParseSuccess(manifest) =>
          val validation = ManifestValidation.validate(manifest)
          results += ManifestRef(
            manifestType = "demiurge",
            relativePath = "demiurge.yaml",
            parsedSuccessfully = validation.isValid,
            parseErrors = validation.errors,
          )
        case ManifestParser.ParseFailure(errors) =>
          results += ManifestRef("demiurge", "demiurge.yaml", parsedSuccessfully = false, parseErrors = errors)
      }
    }

    // Check for compose files
    composeFileNames.foreach { name =>
      if (fileExists(repoRoot, name))
        results += ManifestRef("compose", name, parsedSuccessfully = true, parseErrors = Nil)
    }

    // Check for package.json
    if (fileExists(repoRoot, "package.json"))
      results += ManifestRef("npm", "package.json", parsedSuccessfully = true, parseErrors = Nil)

    // Check for Dockerfile
    if (fileExists(repoRoot, "Dockerfile"))
      results += ManifestRef("dockerfile", "Dockerfile", parsedSuccessfully = true, parseErrors = Nil)

    results.toList
  }

  // Phase 8: Deterministic changed-file impact analysis (Spec §3.2 ImpactMap, §13.11 infra-sensitive)
  // Uses file-extension heuristics to determine affected areas. No LLM in deterministic mode.
  private[inspector] def buildImpactMap(repoRoot: Path, changedFiles: List[String]): ImpactMap = {
    // Spec §1: Infra-sensitive file patterns — exact filename matches
    val infraExactNames = Set(
      "docker-compose.yml", "docker-compose.yaml", "compose.yaml", "compose.yml",
      "Dockerfile", "package.json", "pnpm-lock.yaml", "package-lock.json", "yarn.lock",
      "requirements.txt", "Pipfile", "Pipfile.lock", "pyproject.toml", "poetry.lock",
      "build.sbt", "build.gradle", "pom.xml", "Makefile", "demiurge.yaml",
    )
    val infraDirPatterns = Set("migrations", "migrate")

    val infraSensitive = changedFiles.filter { f =>
      val name = f.split("/").last
      infraExactNames.contains(name) ||
        f.split("/").exists(seg => infraDirPatterns.contains(seg)) ||
        name.endsWith(".dockerfile") ||
        name.startsWith("Dockerfile.") ||
        name == ".env" || name.startsWith(".env.")
    }

    // Classify by file extension/path
    val frontendExts = Set(".tsx", ".jsx", ".vue", ".svelte", ".html", ".css", ".scss")
    val apiExts = Set(".ts", ".js", ".py", ".rb", ".go", ".java", ".scala")
    val dbExts = Set(".sql", ".prisma")
    val migrationFiles = changedFiles.filter(f => f.contains("migration") || f.contains("migrate"))

    val frontendRoutes = changedFiles.filter(f => frontendExts.exists(f.endsWith)).map { f =>
      ScoredInference(f, 0.6, "file-extension heuristic")
    }

    val apiHandlers = changedFiles.filter { f =>
      apiExts.exists(f.endsWith) && (f.contains("route") || f.contains("handler") ||
        f.contains("controller") || f.contains("api") || f.contains("endpoint"))
    }.map(f => ScoredInference(f, 0.6, "file-extension + path heuristic"))

    val dbModels = changedFiles.filter { f =>
      dbExts.exists(f.endsWith) || f.contains("model") || f.contains("schema") || f.contains("entity")
    }.map(f => ScoredInference(f, 0.5, "file-extension + path heuristic"))

    val components = changedFiles.filter(f => frontendExts.exists(f.endsWith)).map { f =>
      ScoredInference(f.split("/").last.split("\\.").head, 0.5, "file-name heuristic")
    }

    val authModules = changedFiles.filter { f =>
      f.contains("auth") || f.contains("login") || f.contains("session") || f.contains("token")
    }.map(f => ScoredInference(f, 0.6, "path heuristic"))

    ImpactMap(
      changedFiles = changedFiles,
      affectedFrontendRoutes = frontendRoutes,
      affectedComponents = components,
      affectedApiHandlers = apiHandlers,
      affectedDbModels = dbModels,
      affectedMigrations = migrationFiles,
      affectedServiceIds = Nil, // would need manifest cross-reference
      affectedAuthModules = authModules,
      inferredAdjacentFlows = Nil, // would need LLM for this
      infraSensitiveChanges = infraSensitive,
      inferenceRequestId = None, // deterministic-only, no LLM
    )
  }

  // --- JSON helpers for package.json ---

  /** Extract all dependency names (dependencies + devDependencies + peerDependencies). */
  private def extractAllDependencies(json: Json): Set[String] = {
    val depFields = List("dependencies", "devDependencies", "peerDependencies")
    depFields.flatMap { field =>
      json.hcursor.downField(field).keys.getOrElse(Nil)
    }.toSet
  }

  /** Extract scripts map from package.json. */
  private def extractScripts(json: Json): Map[String, String] = {
    json.hcursor.downField("scripts").as[Map[String, String]].getOrElse(Map.empty)
  }

  /** Detect port from scripts content or framework defaults. */
  private def detectPortFromScripts(scripts: Map[String, String], deps: Set[String]): Option[Int] = {
    // Check for PORT= in scripts
    val portFromScript = scripts.values.flatMap { cmd =>
      val portPattern = """(?:PORT|port)\s*[=:]\s*(\d+)""".r
      portPattern.findFirstMatchIn(cmd).map(_.group(1).toInt)
    }.headOption

    portFromScript.orElse {
      // Framework defaults
      if (deps.contains("next")) Some(3000)
      else if (deps.contains("nuxt") || deps.contains("nuxt3")) Some(3000)
      else if (deps.contains("vue") && deps.contains("@vue/cli-service")) Some(8080)
      else if (deps.contains("@angular/core")) Some(4200)
      else if (deps.contains("express") || deps.contains("fastify") || deps.contains("koa")) Some(3000)
      else if (deps.contains("gatsby")) Some(8000)
      else None
    }
  }

  // --- Compose YAML parsing ---

  /** Parse compose file and return service name → image pairs. */
  @SuppressWarnings(Array("unchecked"))
  private def parseComposeFile(repoRoot: Path): Option[List[(String, String)]] = {
    val composePath = composeFileNames.map(repoRoot.resolve).find(Files.exists(_))
    composePath.flatMap { path =>
      try {
        val yaml = new org.yaml.snakeyaml.Yaml()
        val data = yaml.load[java.util.Map[String, Any]](Files.readString(path))
        if (data == null) return None

        val services = data.get("services")
        if (services == null || !services.isInstanceOf[java.util.Map[_, _]]) return None

        val svcMap = services.asInstanceOf[java.util.Map[String, Any]]
        val result = scala.collection.mutable.ListBuffer[(String, String)]()

        val iter = svcMap.entrySet().iterator()
        while (iter.hasNext) {
          val entry = iter.next()
          val name = entry.getKey
          val value = entry.getValue
          if (value != null && value.isInstanceOf[java.util.Map[_, _]]) {
            val svcConfig = value.asInstanceOf[java.util.Map[String, Any]]
            val image = Option(svcConfig.get("image")).map(_.toString).getOrElse("")
            result += (name -> image)
          }
        }

        Some(result.toList)
      } catch {
        case _: Exception => None
      }
    }
  }

  /** Detect compose services as CandidateService entries with port extraction. */
  @SuppressWarnings(Array("unchecked"))
  private def detectComposeServices(repoRoot: Path): List[CandidateService] = {
    val composePath = composeFileNames.map(repoRoot.resolve).find(Files.exists(_))
    composePath.map { path =>
      try {
        val yaml = new org.yaml.snakeyaml.Yaml()
        val data = yaml.load[java.util.Map[String, Any]](Files.readString(path))
        if (data == null) return Nil

        val services = data.get("services")
        if (services == null || !services.isInstanceOf[java.util.Map[_, _]]) return Nil

        val svcMap = services.asInstanceOf[java.util.Map[String, Any]]
        val result = scala.collection.mutable.ListBuffer[CandidateService]()

        val iter = svcMap.entrySet().iterator()
        while (iter.hasNext) {
          val entry = iter.next()
          val name = entry.getKey
          val value = entry.getValue
          if (value != null && value.isInstanceOf[java.util.Map[_, _]]) {
            val svcConfig = value.asInstanceOf[java.util.Map[String, Any]]
            val image = Option(svcConfig.get("image")).map(_.toString).getOrElse("")

            val kind = if (image.contains("postgres") || image.contains("mysql") || image.contains("mariadb") || image.contains("mongo")) ServiceKind.Db
              else if (image.contains("redis") || image.contains("memcached")) ServiceKind.Cache
              else if (image.contains("rabbit") || image.contains("kafka") || image.contains("nats")) ServiceKind.Queue
              else ServiceKind.Api

            // Extract first port mapping
            val portHint = extractComposePort(svcConfig)

            result += CandidateService(
              serviceId = name,
              kind = kind,
              confidence = 0.8,
              provenance = s"compose service '$name' (image: $image)",
              startupHint = Some(s"docker compose up $name"),
              portHint = portHint,
              healthHint = if (kind == ServiceKind.Db || kind == ServiceKind.Cache) None
                else portHint.map(p => s"http://localhost:$p/"),
            )
          }
        }

        result.toList
      } catch {
        case _: Exception => Nil
      }
    }.getOrElse(Nil)
  }

  /** Extract the first host port from compose ports config. */
  @SuppressWarnings(Array("unchecked"))
  private def extractComposePort(svcConfig: java.util.Map[String, Any]): Option[Int] = {
    try {
      val ports = svcConfig.get("ports")
      if (ports == null) return None
      if (ports.isInstanceOf[java.util.List[_]]) {
        val list = ports.asInstanceOf[java.util.List[Any]]
        if (!list.isEmpty) {
          val first = list.get(0).toString
          // Format: "host:container" or "host:container/proto" or just "port"
          val hostPort = first.split(":").head.split("/").head.trim
          scala.util.Try(hostPort.toInt).toOption
        } else None
      } else None
    } catch {
      case _: Exception => None
    }
  }

  // --- Source file scanning ---

  /** Scan source files (depth 3) for route/endpoint patterns. */
  private def scanSourceFilesForPatterns(repoRoot: Path, patterns: List[String]): List[String] = {
    val srcExts = Set(".ts", ".js", ".tsx", ".jsx", ".py", ".rb", ".go")
    val found = scala.collection.mutable.LinkedHashSet[String]()

    try {
      val stream = Files.walk(repoRoot, 3)
      try {
        stream.filter(p => Files.isRegularFile(p) && srcExts.exists(p.toString.endsWith))
          .forEach { path =>
            try {
              val content = Files.readString(path)
              patterns.foreach { pattern =>
                if (content.contains(pattern)) found += pattern
              }
            } catch {
              case _: Exception => // skip unreadable files
            }
          }
      } finally {
        stream.close()
      }
    } catch {
      case _: Exception =>
    }

    found.toList
  }

  // --- Filesystem utilities ---

  private def readFileContent(root: Path, name: String): Option[String] = {
    val path = root.resolve(name)
    if (Files.exists(path)) {
      try Some(new String(Files.readAllBytes(path), "UTF-8"))
      catch { case _: Exception => None }
    } else None
  }

  private def fileExists(root: Path, name: String): Boolean =
    Files.exists(root.resolve(name))

  private def hasFileWithExtension(root: Path, ext: String): Boolean = {
    try {
      val stream = Files.walk(root, 3)
      try {
        stream.anyMatch(p => p.toString.endsWith(ext) && Files.isRegularFile(p))
      } finally {
        stream.close()
      }
    } catch {
      case _: Exception => false
    }
  }
}
