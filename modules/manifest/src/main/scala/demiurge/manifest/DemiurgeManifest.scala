package demiurge.manifest

// Spec §11.3: DemiurgeManifest — parsed representation of demiurge.yaml
// Phase 8: All MVP sections: version, app, services, fixtures, auth, verification, inference, policies, observability

case class DemiurgeManifest(
  version:        Int,
  app:            AppConfig,
  services:       Map[String, ServiceConfig],
  fixtures:       Option[FixturesConfig],
  auth:           Option[AuthConfig],
  verification:   Option[VerificationConfig],
  inference:      Option[InferenceConfig],
  policies:       Option[PoliciesConfig],
  observability:  Option[ObservabilityConfig],
)

case class AppConfig(
  appType:   String,
  rootUrl:   String,
  apiUrl:    Option[String],
)

case class ServiceConfig(
  kind:              String,
  startupMode:       String,
  startupCommand:    Option[String],
  composeTarget:     Option[String],
  cwd:               Option[String],
  env:               Option[Map[String, String]],
  envFile:           Option[String],
  ports:             Option[List[PortConfig]],
  dependsOn:         Option[List[String]],
  readiness:         Option[ReadinessConfig],
  shutdownMethod:    Option[String],
  shutdownTimeoutMs: Option[Int],
  restart:           Option[RestartConfig],
  logs:              Option[String],
  required:          Option[Boolean],
  startup:           Option[StartupConfig],
)

case class PortConfig(
  host:      Option[Int],
  container: Int,
  protocol:  Option[String],
)

case class ReadinessConfig(
  probeType:     String,
  target:        String,
  intervalMs:    Option[Int],
  timeoutMs:     Option[Int],
  maxFailures:   Option[Int],
  initialDelayMs: Option[Int],
)

case class RestartConfig(
  maxRestarts:       Option[Int],
  backoffBaseMs:     Option[Int],
  backoffMaxMs:      Option[Int],
  backoffMultiplier: Option[Double],
)

case class StartupConfig(
  order: Option[Int],
)

case class FixturesConfig(
  seedSteps:     Option[List[SeedStepConfig]],
  resetStrategy: Option[String],
)

case class SeedStepConfig(
  stepId:            String,
  description:       Option[String],
  command:           String,
  cwd:               Option[String],
  env:               Option[Map[String, String]],
  timeoutMs:         Option[Int],
  dependsOnServices: Option[List[String]],
  runOnReset:        Option[Boolean],
  runOnInitOnly:     Option[Boolean],
  order:             Option[Int],
)

// Spec §11.3: auth section
case class AuthConfig(
  mode:                String,
  loginUrl:            Option[String],
  credentials:         Option[Map[String, String]],
  tokenEndpoint:       Option[String],
  staticToken:         Option[String],
  devBypassHeader:     Option[Map[String, String]],
  storageStateOutput:  Option[String],
)

// Spec §11.3: verification section
case class VerificationConfig(
  defaultVerifierTimeoutMs:       Option[Int],
  defaultBrowserActionTimeoutMs:  Option[Int],
  maxRetries:                     Option[Int],
  retryDelayMs:                   Option[Int],
  screenshotOnFailure:            Option[Boolean],
  screenshotOnComplete:           Option[Boolean],
  traceEnabled:                   Option[Boolean],
)

// Spec §11.3: inference section
case class InferenceConfig(
  defaultProvider: Option[String],
  models:          Option[InferenceModelsConfig],
)

case class InferenceModelsConfig(
  requirementCompiler:  Option[String],
  verifierGenerator:    Option[String],
  failureAnalyzer:      Option[String],
  impactAnalysis:       Option[String],
  exploratoryVerifier:  Option[String],
)

// Spec §11.3: policies section
case class PoliciesConfig(
  maxAttempts:           Option[Int],
  runTimeoutMs:          Option[Long],
  attemptTimeoutMs:      Option[Long],
  maxPatchLines:         Option[Int],
  maxArtifactDiskBytes:  Option[Long],
  allowedHosts:          Option[List[String]],
  browserAllowedOrigins: Option[List[String]],
  allowGitPush:          Option[Boolean],
  allowDbDrop:           Option[Boolean],
)

case class ObservabilityConfig(
  taps:       Option[List[ObservabilityTapConfig]],
  logQueries: Option[List[LogQueryConfig]],
)

case class ObservabilityTapConfig(
  tapId:     String,
  serviceId: String,
  tapType:   String,
  config:    Option[Map[String, String]],
)

// Spec §11.3: observability.log_queries
case class LogQueryConfig(
  id:          String,
  serviceId:   String,
  query:       String,
  description: Option[String],
)
