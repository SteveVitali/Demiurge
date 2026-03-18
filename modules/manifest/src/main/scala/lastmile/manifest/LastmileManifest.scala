package lastmile.manifest

// Spec §5: LastmileManifest — parsed representation of lastmile.yaml
// Phase 3 subset: version, app, services, fixtures, observability (parsed but no behavior)

case class LastmileManifest(
  version:        Int,
  app:            AppConfig,
  services:       Map[String, ServiceConfig],
  fixtures:       Option[FixturesConfig],
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

case class ObservabilityConfig(
  taps: Option[List[ObservabilityTapConfig]],
)

case class ObservabilityTapConfig(
  tapId:     String,
  serviceId: String,
  tapType:   String,
  config:    Option[Map[String, String]],
)
