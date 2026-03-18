package demiurge.config

import java.nio.file.{Files, Path}
import java.time.Instant

import demiurge.model._
import demiurge.manifest.{ManifestParser, DemiurgeManifest, AppConfig => ManifestAppConfig,
  ServiceConfig => ManifestServiceConfig, FixturesConfig => ManifestFixturesConfig,
  AuthConfig => ManifestAuthConfig, VerificationConfig => ManifestVerificationConfig,
  InferenceConfig => ManifestInferenceConfig, PoliciesConfig => ManifestPoliciesConfig,
  ObservabilityConfig => ManifestObservabilityConfig}
import demiurge.requirements.{RequirementsParser, RequirementsFile}
import demiurge.selectors.{SelectorsParser, SelectorsFile}
import demiurge.inference.InferenceService

// Phase A: Layered configuration resolver implementation.
// Layer 1: Explicit YAML (demiurge.yaml, requirements.yaml, selectors.yaml)
// Layer 2: Cached inference (.demiurge/inferred/*.yaml)
// Layer 3: Live inference (RepoInspector data + LLM)
// Each layer fills gaps left by previous layers.
object ConfigResolverImpl extends ConfigResolver {

  private val DefaultVerificationConfig = ResolvedVerificationConfig(
    defaultVerifierTimeoutMs = 30000,
    defaultBrowserActionTimeoutMs = 15000,
    maxRetries = 1,
    retryDelayMs = 1000,
    screenshotOnFailure = false,
    screenshotOnComplete = false,
    traceEnabled = false,
  )

  private val DefaultPoliciesConfig = ResolvedPoliciesConfig(
    maxAttempts = 5,
    runTimeoutMs = 3600000L,
    attemptTimeoutMs = 900000L,
    maxPatchLines = 2000,
    maxArtifactDiskBytes = 536870912L,
    allowedHosts = List("localhost", "127.0.0.1"),
    browserAllowedOrigins = List("http://localhost:*"),
    allowGitPush = false,
    allowDbDrop = false,
  )

  private val DefaultInferenceConfig = ResolvedInferenceConfig(
    defaultProvider = InferenceProvider.Mock,
    models = Map.empty,
  )

  override def resolve(
    repoPath: Path,
    taskText: String,
    changedFiles: Option[List[String]],
    inspection: RepoInspectionReport,
    inferenceService: Option[InferenceService],
  ): ResolvedConfig = {

    // Layer 1: Try explicit YAML
    val explicitManifest = loadExplicitManifest(repoPath)
    val explicitRequirements = loadExplicitRequirements(repoPath)

    // Layer 2: Try cached inference
    val cachedManifest = if (explicitManifest.isEmpty) loadCachedManifest(repoPath) else None
    val cachedRequirements = if (explicitRequirements.isEmpty) loadCachedRequirements(repoPath) else None

    // Determine manifest source
    val (manifest, manifestSource) = explicitManifest.map(_ -> ConfigSource.Explicit)
      .orElse(cachedManifest.map(_ -> ConfigSource.Cached))
      .getOrElse(null, null) match {
      case (m: DemiurgeManifest, s: ConfigSource) => (Some(m), s)
      case _ => (None, ConfigSource.Inferred)
    }

    // Resolve app config
    val (app, appSource) = manifest.map(m => resolveAppFromManifest(m) -> manifestSource)
      .getOrElse(inferAppFromInspection(inspection) -> ConfigSource.Inferred)

    // Resolve services
    val (services, servicesSources) = manifest.filter(_.services.nonEmpty) match {
      case Some(m) =>
        val svcs = resolveServicesFromManifest(m)
        val sources = svcs.map(s => s.serviceId -> manifestSource).toMap
        (svcs, sources)
      case None =>
        val svcs = inferServicesFromInspection(inspection, app)
        val sources = svcs.map(s => s.serviceId -> ConfigSource.Inferred).toMap
        (svcs, sources)
    }

    // Resolve fixtures
    val fixtures = manifest.flatMap(_.fixtures).map(resolveFixturesFromManifest)
      .orElse(inferFixturesFromInspection(inspection))

    // Resolve auth
    val auth = manifest.flatMap(_.auth).map(resolveAuthFromManifest)
      .orElse(inferAuthFromInspection(inspection))

    // Resolve verification config
    val verification = manifest.flatMap(_.verification)
      .map(resolveVerificationFromManifest)
      .getOrElse(DefaultVerificationConfig)

    // Resolve inference config
    val inference = manifest.flatMap(_.inference)
      .map(resolveInferenceFromManifest)
      .getOrElse(inferInferenceConfig(inferenceService))

    // Resolve policies
    val policies = manifest.flatMap(_.policies)
      .map(resolvePoliciesFromManifest)
      .getOrElse(DefaultPoliciesConfig)

    // Resolve observability
    val observability = manifest.flatMap(_.observability).map(resolveObservabilityFromManifest)

    // Build provenance
    val reqSources = explicitRequirements.map(_ => Map("yaml" -> ConfigSource.Explicit))
      .orElse(cachedRequirements.map(_ => Map("yaml" -> ConfigSource.Cached)))
      .getOrElse(Map("inferred" -> ConfigSource.Inferred))
      .asInstanceOf[Map[String, ConfigSource]]

    val provenance = ConfigProvenance(
      manifestSource = manifestSource,
      requirementSources = reqSources,
      serviceSources = servicesSources,
      resolvedAt = Instant.now(),
    )

    ResolvedConfig(
      app = app,
      services = services,
      fixtures = fixtures,
      auth = auth,
      verification = verification,
      inference = inference,
      policies = policies,
      observability = observability,
      provenance = provenance,
    )
  }

  // --- Layer 1: Explicit YAML loading ---

  private def loadExplicitManifest(repoPath: Path): Option[DemiurgeManifest] = {
    val path = repoPath.resolve("demiurge.yaml")
    if (!Files.exists(path)) return None
    ManifestParser.parseFile(path) match {
      case ManifestParser.ParseSuccess(m) => Some(m)
      case _ => None
    }
  }

  private def loadExplicitRequirements(repoPath: Path): Option[RequirementsFile] = {
    val path = repoPath.resolve("requirements.yaml")
    if (!Files.exists(path)) return None
    RequirementsParser.parse(path).toOption
  }

  // --- Layer 2: Cached inference loading ---

  private def loadCachedManifest(repoPath: Path): Option[DemiurgeManifest] = {
    val path = repoPath.resolve(".demiurge").resolve("inferred").resolve("demiurge.yaml")
    if (!Files.exists(path)) return None
    ManifestParser.parseFile(path) match {
      case ManifestParser.ParseSuccess(m) => Some(m)
      case _ => None
    }
  }

  private def loadCachedRequirements(repoPath: Path): Option[RequirementsFile] = {
    val path = repoPath.resolve(".demiurge").resolve("inferred").resolve("requirements.yaml")
    if (!Files.exists(path)) return None
    RequirementsParser.parse(path).toOption
  }

  // --- Manifest → ResolvedConfig mapping ---

  private[config] def resolveAppFromManifest(m: DemiurgeManifest): ResolvedAppConfig =
    ResolvedAppConfig(
      appType = m.app.appType,
      rootUrl = m.app.rootUrl,
      apiUrl = m.app.apiUrl,
    )

  private[config] def resolveServicesFromManifest(m: DemiurgeManifest): List[ResolvedServiceConfig] =
    m.services.toList.map { case (id, sc) =>
      ResolvedServiceConfig(
        serviceId = id,
        kind = sc.kind,
        startupMode = sc.startupMode,
        startupCommand = sc.startupCommand,
        composeTarget = sc.composeTarget,
        cwd = sc.cwd,
        env = sc.env.getOrElse(Map.empty),
        ports = sc.ports.getOrElse(Nil).map(p => ResolvedPortConfig(p.host, p.container)),
        dependsOn = sc.dependsOn.getOrElse(Nil),
        readiness = sc.readiness.map(r => ResolvedReadinessConfig(
          probeType = r.probeType,
          target = r.target,
          intervalMs = r.intervalMs.getOrElse(1000),
          timeoutMs = r.timeoutMs.getOrElse(3000),
          maxFailures = r.maxFailures.getOrElse(10),
        )),
        required = sc.required.getOrElse(false),
      )
    }

  private[config] def resolveFixturesFromManifest(fc: ManifestFixturesConfig): ResolvedFixturesConfig = {
    val strategy = fc.resetStrategy match {
      case Some("hard") => ResetStrategy.HardReset
      case Some("full_rebuild") => ResetStrategy.FullRebuild
      case _ => ResetStrategy.SoftReset
    }
    ResolvedFixturesConfig(
      resetStrategy = strategy,
      seedSteps = fc.seedSteps.getOrElse(Nil).map(s => ResolvedSeedStep(
        stepId = s.stepId,
        command = s.command,
        cwd = s.cwd,
        timeoutMs = s.timeoutMs.getOrElse(30000),
        runOnReset = s.runOnReset.getOrElse(false),
        runOnInitOnly = s.runOnInitOnly.getOrElse(false),
      )),
    )
  }

  private[config] def resolveAuthFromManifest(ac: ManifestAuthConfig): ResolvedAuthConfig = {
    val mode = ac.mode.toLowerCase match {
      case "browser_form_login" => AuthMode.BrowserFormLogin
      case "api_login" => AuthMode.ApiLogin
      case "static_test_token" => AuthMode.StaticTestToken
      case "seeded_local_session" => AuthMode.SeededLocalSession
      case "dev_bypass_header" => AuthMode.DevBypassHeader
      case _ => AuthMode.StaticTestToken
    }
    ResolvedAuthConfig(
      mode = mode,
      loginUrl = ac.loginUrl,
      credentials = ac.credentials.getOrElse(Map.empty),
      staticToken = ac.staticToken,
      storageStateOutput = ac.storageStateOutput,
    )
  }

  private[config] def resolveVerificationFromManifest(vc: ManifestVerificationConfig): ResolvedVerificationConfig =
    ResolvedVerificationConfig(
      defaultVerifierTimeoutMs = vc.defaultVerifierTimeoutMs.getOrElse(30000),
      defaultBrowserActionTimeoutMs = vc.defaultBrowserActionTimeoutMs.getOrElse(15000),
      maxRetries = vc.maxRetries.getOrElse(1),
      retryDelayMs = vc.retryDelayMs.getOrElse(1000),
      screenshotOnFailure = vc.screenshotOnFailure.getOrElse(false),
      screenshotOnComplete = vc.screenshotOnComplete.getOrElse(false),
      traceEnabled = vc.traceEnabled.getOrElse(false),
    )

  private[config] def resolveInferenceFromManifest(ic: ManifestInferenceConfig): ResolvedInferenceConfig = {
    val provider = ic.defaultProvider.map(_.toLowerCase) match {
      case Some("anthropic") => InferenceProvider.Anthropic
      case Some("openai") => InferenceProvider.OpenAI
      case Some("local") => InferenceProvider.Local
      case _ => InferenceProvider.Mock
    }
    val models = ic.models.map { mc =>
      val m = scala.collection.mutable.Map[String, String]()
      mc.requirementCompiler.foreach(v => m += "requirement_compiler" -> v)
      mc.verifierGenerator.foreach(v => m += "verifier_generator" -> v)
      mc.failureAnalyzer.foreach(v => m += "failure_analyzer" -> v)
      mc.impactAnalysis.foreach(v => m += "impact_analysis" -> v)
      mc.exploratoryVerifier.foreach(v => m += "exploratory_verifier" -> v)
      m.toMap
    }.getOrElse(Map.empty)
    ResolvedInferenceConfig(defaultProvider = provider, models = models)
  }

  private[config] def resolvePoliciesFromManifest(pc: ManifestPoliciesConfig): ResolvedPoliciesConfig =
    ResolvedPoliciesConfig(
      maxAttempts = pc.maxAttempts.getOrElse(DefaultPoliciesConfig.maxAttempts),
      runTimeoutMs = pc.runTimeoutMs.getOrElse(DefaultPoliciesConfig.runTimeoutMs),
      attemptTimeoutMs = pc.attemptTimeoutMs.getOrElse(DefaultPoliciesConfig.attemptTimeoutMs),
      maxPatchLines = pc.maxPatchLines.getOrElse(DefaultPoliciesConfig.maxPatchLines),
      maxArtifactDiskBytes = pc.maxArtifactDiskBytes.getOrElse(DefaultPoliciesConfig.maxArtifactDiskBytes),
      allowedHosts = pc.allowedHosts.getOrElse(DefaultPoliciesConfig.allowedHosts),
      browserAllowedOrigins = pc.browserAllowedOrigins.getOrElse(DefaultPoliciesConfig.browserAllowedOrigins),
      allowGitPush = pc.allowGitPush.getOrElse(DefaultPoliciesConfig.allowGitPush),
      allowDbDrop = pc.allowDbDrop.getOrElse(DefaultPoliciesConfig.allowDbDrop),
    )

  private[config] def resolveObservabilityFromManifest(oc: ManifestObservabilityConfig): ResolvedObservabilityConfig =
    ResolvedObservabilityConfig(
      taps = oc.taps.getOrElse(Nil).map(t => ResolvedTapConfig(t.tapId, t.serviceId, t.tapType)),
      logQueries = oc.logQueries.getOrElse(Nil).map(q =>
        ResolvedLogQueryConfig(q.id, q.serviceId, q.query, q.description)),
    )

  // --- Layer 3: Inference from RepoInspectionReport ---

  private[config] def inferAppFromInspection(inspection: RepoInspectionReport): ResolvedAppConfig = {
    val hasReact = inspection.frameworks.exists(f => f.value == "react" || f.value == "nextjs" || f.value == "vue" || f.value == "angular")
    val hasExpress = inspection.frameworks.exists(f => f.value == "express" || f.value == "fastify")

    val appType = if (hasReact && hasExpress) "fullstack"
      else if (hasReact) "frontend"
      else if (hasExpress) "api"
      else "api"

    // Infer root URL from candidate services
    val portHint = inspection.candidateServices
      .flatMap(_.portHint)
      .headOption
      .getOrElse(3000)

    ResolvedAppConfig(
      appType = appType,
      rootUrl = s"http://localhost:$portHint",
      apiUrl = if (appType == "fullstack") Some(s"http://localhost:$portHint/api") else None,
    )
  }

  private[config] def inferServicesFromInspection(
    inspection: RepoInspectionReport,
    app: ResolvedAppConfig,
  ): List[ResolvedServiceConfig] = {
    if (inspection.candidateServices.nonEmpty) {
      inspection.candidateServices.map { cs =>
        val portHint = cs.portHint.getOrElse(3000)
        ResolvedServiceConfig(
          serviceId = cs.serviceId,
          kind = cs.kind.toString.toLowerCase,
          startupMode = if (cs.startupHint.exists(_.contains("compose"))) "compose" else "script",
          startupCommand = cs.startupHint.filterNot(_.contains("compose")),
          composeTarget = if (cs.startupHint.exists(_.contains("compose"))) Some(cs.serviceId) else None,
          cwd = None,
          env = Map.empty,
          ports = List(ResolvedPortConfig(Some(portHint), portHint)),
          dependsOn = Nil,
          readiness = Some(ResolvedReadinessConfig(
            probeType = if (cs.kind == ServiceKind.Db) "tcp" else "http",
            target = cs.healthHint.getOrElse(s"http://localhost:$portHint/"),
            intervalMs = 1000,
            timeoutMs = 3000,
            maxFailures = 10,
          )),
          required = true,
        )
      }
    } else {
      // Fallback: create a single service from the app config
      val port = try {
        new java.net.URI(app.rootUrl).getPort match { case -1 => 3000; case p => p }
      } catch { case _: Exception => 3000 }

      List(ResolvedServiceConfig(
        serviceId = "app",
        kind = app.appType,
        startupMode = "script",
        startupCommand = Some("npm start"),
        composeTarget = None,
        cwd = None,
        env = Map.empty,
        ports = List(ResolvedPortConfig(Some(port), port)),
        dependsOn = Nil,
        readiness = Some(ResolvedReadinessConfig(
          probeType = "http",
          target = app.rootUrl,
          intervalMs = 1000,
          timeoutMs = 3000,
          maxFailures = 10,
        )),
        required = true,
      ))
    }
  }

  private[config] def inferFixturesFromInspection(inspection: RepoInspectionReport): Option[ResolvedFixturesConfig] = {
    val startupCommands = inspection.startupCommands.map(_.value)
    val hasMigrate = startupCommands.exists(c => c.contains("migrate"))
    val hasSeed = startupCommands.exists(c => c.contains("seed"))

    if (!hasMigrate && !hasSeed) return None

    val steps = scala.collection.mutable.ListBuffer[ResolvedSeedStep]()
    if (hasMigrate) {
      steps += ResolvedSeedStep(
        stepId = "migrate",
        command = "npm run migrate",
        cwd = None,
        timeoutMs = 30000,
        runOnReset = false,
        runOnInitOnly = true,
      )
    }
    if (hasSeed) {
      steps += ResolvedSeedStep(
        stepId = "seed",
        command = "npm run seed",
        cwd = None,
        timeoutMs = 30000,
        runOnReset = true,
        runOnInitOnly = false,
      )
    }

    Some(ResolvedFixturesConfig(
      resetStrategy = ResetStrategy.SoftReset,
      seedSteps = steps.toList,
    ))
  }

  private[config] def inferAuthFromInspection(inspection: RepoInspectionReport): Option[ResolvedAuthConfig] = {
    if (inspection.authHints.nonEmpty) {
      Some(ResolvedAuthConfig(
        mode = AuthMode.StaticTestToken,
        loginUrl = None,
        credentials = Map.empty,
        staticToken = Some("test-token"),
        storageStateOutput = None,
      ))
    } else None
  }

  private[config] def inferInferenceConfig(inferenceService: Option[InferenceService]): ResolvedInferenceConfig = {
    // If an inference service is available, default to Anthropic; otherwise Mock
    val provider = if (inferenceService.isDefined) InferenceProvider.Anthropic
      else InferenceProvider.Mock
    ResolvedInferenceConfig(defaultProvider = provider, models = Map.empty)
  }

  // --- Cache writing ---

  /** Write the resolved config to .demiurge/inferred/ for future reuse. */
  def cacheResolvedConfig(repoPath: Path, config: ResolvedConfig): Unit = {
    val inferredDir = repoPath.resolve(".demiurge").resolve("inferred")
    try {
      Files.createDirectories(inferredDir)
      val yaml = InferredConfigWriter.toManifestYaml(config)
      Files.writeString(inferredDir.resolve("demiurge.yaml"), yaml)
    } catch {
      case _: Exception => // Best-effort caching, don't fail the run
    }
  }
}
