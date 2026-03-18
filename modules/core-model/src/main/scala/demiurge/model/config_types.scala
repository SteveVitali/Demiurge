package demiurge.model

import java.time.Instant

// Phase A: Configuration resolution types for auto-config pipeline.
// ResolvedConfig is the fully-resolved, ready-to-use configuration
// produced by the layered inference pipeline (explicit YAML → cached → inferred).

case class ResolvedConfig(
  app:            ResolvedAppConfig,
  services:       List[ResolvedServiceConfig],
  fixtures:       Option[ResolvedFixturesConfig],
  auth:           Option[ResolvedAuthConfig],
  verification:   ResolvedVerificationConfig,
  inference:      ResolvedInferenceConfig,
  policies:       ResolvedPoliciesConfig,
  observability:  Option[ResolvedObservabilityConfig],
  provenance:     ConfigProvenance,
)

case class ResolvedAppConfig(
  appType:   String,
  rootUrl:   String,
  apiUrl:    Option[String],
)

case class ResolvedServiceConfig(
  serviceId:         String,
  kind:              String,
  startupMode:       String,
  startupCommand:    Option[String],
  composeTarget:     Option[String],
  cwd:               Option[String],
  env:               Map[String, String],
  ports:             List[ResolvedPortConfig],
  dependsOn:         List[String],
  readiness:         Option[ResolvedReadinessConfig],
  required:          Boolean,
)

case class ResolvedPortConfig(
  host:      Option[Int],
  container: Int,
)

case class ResolvedReadinessConfig(
  probeType:     String,
  target:        String,
  intervalMs:    Int,
  timeoutMs:     Int,
  maxFailures:   Int,
)

case class ResolvedFixturesConfig(
  resetStrategy: ResetStrategy,
  seedSteps:     List[ResolvedSeedStep],
)

case class ResolvedSeedStep(
  stepId:        String,
  command:       String,
  cwd:           Option[String],
  timeoutMs:     Int,
  runOnReset:    Boolean,
  runOnInitOnly: Boolean,
)

case class ResolvedAuthConfig(
  mode:               AuthMode,
  loginUrl:           Option[String],
  credentials:        Map[String, String],
  staticToken:        Option[String],
  storageStateOutput: Option[String],
)

case class ResolvedVerificationConfig(
  defaultVerifierTimeoutMs:      Int,
  defaultBrowserActionTimeoutMs: Int,
  maxRetries:                    Int,
  retryDelayMs:                  Int,
  screenshotOnFailure:           Boolean,
  screenshotOnComplete:          Boolean,
  traceEnabled:                  Boolean,
)

case class ResolvedInferenceConfig(
  defaultProvider: InferenceProvider,
  models:          Map[String, String],
)

case class ResolvedPoliciesConfig(
  maxAttempts:           Int,
  runTimeoutMs:          Long,
  attemptTimeoutMs:      Long,
  maxPatchLines:         Int,
  maxArtifactDiskBytes:  Long,
  allowedHosts:          List[String],
  browserAllowedOrigins: List[String],
  allowGitPush:          Boolean,
  allowDbDrop:           Boolean,
)

case class ResolvedObservabilityConfig(
  taps:       List[ResolvedTapConfig],
  logQueries: List[ResolvedLogQueryConfig],
)

case class ResolvedTapConfig(
  tapId:     String,
  serviceId: String,
  tapType:   String,
)

case class ResolvedLogQueryConfig(
  id:          String,
  serviceId:   String,
  query:       String,
  description: Option[String],
)

// Tracks where each config value came from
case class ConfigProvenance(
  manifestSource:     ConfigSource,
  requirementSources: Map[String, ConfigSource],
  serviceSources:     Map[String, ConfigSource],
  resolvedAt:         Instant,
)

sealed trait ConfigSource
object ConfigSource {
  case object Explicit extends ConfigSource
  case object Cached extends ConfigSource
  case object Inferred extends ConfigSource
  case object Default extends ConfigSource

  val values: List[ConfigSource] = List(Explicit, Cached, Inferred, Default)
}
