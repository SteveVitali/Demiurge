package lastmile.inspector

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

import lastmile.model._
import lastmile.manifest.{ManifestParser, ManifestValidation}

// Spec §5: Deterministic filesystem-based repo inspector for Phase 3.
// Detects compose files, package.json, language/framework hints, manifest files.
// No LLM or inference — purely filesystem scanning.
object RepoInspectorImpl extends RepoInspector {

  private val composeFileNames = Set(
    "compose.yaml", "compose.yml",
    "docker-compose.yaml", "docker-compose.yml",
  )

  override def inspect(runId: String, repoRoot: Path, changedFiles: Option[List[String]]): RepoInspectionReport = {
    val warnings = scala.collection.mutable.ListBuffer[String]()

    // Read package.json once and share across detection methods
    val packageJsonContent = readFileContent(repoRoot, "package.json")

    val languages = detectLanguages(repoRoot, packageJsonContent)
    val frameworks = detectFrameworks(repoRoot, packageJsonContent)
    val candidateServices = detectCandidateServices(repoRoot, packageJsonContent)
    val startupCommands = detectStartupCommands(repoRoot, packageJsonContent)
    val healthEndpointHints = detectHealthEndpoints(packageJsonContent)
    val manifestsFound = detectManifests(repoRoot)

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
      dbDependencies = Nil,
      queueDependencies = Nil,
      frontendEntrypoints = Nil,
      apiBasePaths = Nil,
      testFrameworkHints = Nil,
      authHints = Nil,
      changedSurfaceMap = None,
      manifestsFound = manifestsFound,
      warnings = warnings.toList,
    )
  }

  private def detectLanguages(repoRoot: Path, packageJson: Option[String]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()

    if (packageJson.isDefined)
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

    results.toList
  }

  private def detectFrameworks(repoRoot: Path, packageJson: Option[String]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()

    packageJson.foreach { content =>
      if (content.contains("\"next\"")) results += ScoredInference("nextjs", 0.8, "package.json contains next")
      if (content.contains("\"react\"")) results += ScoredInference("react", 0.8, "package.json contains react")
      if (content.contains("\"express\"")) results += ScoredInference("express", 0.8, "package.json contains express")
      if (content.contains("\"vue\"")) results += ScoredInference("vue", 0.7, "package.json contains vue")
      if (content.contains("\"angular\"")) results += ScoredInference("angular", 0.7, "package.json contains angular")
    }

    if (fileExists(repoRoot, "requirements.txt") || fileExists(repoRoot, "Pipfile")) {
      results += ScoredInference("python-web", 0.5, "Python dependency file found")
    }

    results.toList
  }

  private def detectCandidateServices(repoRoot: Path, packageJson: Option[String]): List[CandidateService] = {
    val results = scala.collection.mutable.ListBuffer[CandidateService]()

    // Detect compose services
    composeFileNames.foreach { name =>
      if (fileExists(repoRoot, name)) {
        results += CandidateService(
          serviceId = s"compose-$name",
          kind = ServiceKind.Api,
          confidence = 0.7,
          provenance = s"compose file $name found",
          startupHint = Some(s"docker compose -f $name up"),
          portHint = None,
          healthHint = None,
        )
      }
    }

    // Detect Node.js app
    packageJson.foreach { content =>
      if (content.contains("\"start\"")) {
        val portHint = if (content.contains("\"express\"")) Some(3000) else None
        results += CandidateService(
          serviceId = "node-app",
          kind = if (content.contains("\"react\"") || content.contains("\"next\"")) ServiceKind.Frontend else ServiceKind.Api,
          confidence = 0.7,
          provenance = "package.json with start script",
          startupHint = Some("npm start"),
          portHint = portHint,
          healthHint = portHint.map(p => s"http://localhost:$p/"),
        )
      }
    }

    results.toList
  }

  private def detectStartupCommands(repoRoot: Path, packageJson: Option[String]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()

    packageJson.foreach { content =>
      if (content.contains("\"start\""))
        results += ScoredInference("npm start", 0.8, "package.json start script")
      if (content.contains("\"dev\""))
        results += ScoredInference("npm run dev", 0.7, "package.json dev script")
    }

    composeFileNames.foreach { name =>
      if (fileExists(repoRoot, name))
        results += ScoredInference(s"docker compose -f $name up", 0.7, s"compose file $name")
    }

    results.toList
  }

  private def detectHealthEndpoints(packageJson: Option[String]): List[ScoredInference[String]] = {
    val results = scala.collection.mutable.ListBuffer[ScoredInference[String]]()

    packageJson.foreach { content =>
      if (content.contains("\"express\""))
        results += ScoredInference("http://localhost:3000/health", 0.5, "express app default health endpoint")
    }

    results.toList
  }

  private def detectManifests(repoRoot: Path): List[ManifestRef] = {
    val results = scala.collection.mutable.ListBuffer[ManifestRef]()

    // Check for lastmile.yaml
    val lastmileYaml = repoRoot.resolve("lastmile.yaml")
    if (Files.exists(lastmileYaml)) {
      val parseResult = ManifestParser.parseFile(lastmileYaml)
      parseResult match {
        case ManifestParser.ParseSuccess(manifest) =>
          val validation = ManifestValidation.validate(manifest)
          results += ManifestRef(
            manifestType = "lastmile",
            relativePath = "lastmile.yaml",
            parsedSuccessfully = validation.isValid,
            parseErrors = validation.errors,
          )
        case ManifestParser.ParseFailure(errors) =>
          results += ManifestRef("lastmile", "lastmile.yaml", parsedSuccessfully = false, parseErrors = errors)
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

    results.toList
  }

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
      val stream = Files.walk(root, 3) // limit depth to 3
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
