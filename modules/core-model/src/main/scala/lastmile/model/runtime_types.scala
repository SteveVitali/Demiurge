package lastmile.model

import java.time.Instant

// Spec §3.2: RuntimePlan
case class RuntimePlan(
  planId:             String,
  runId:              String,
  services:           List[ServiceSpec],
  fixtureSteps:       List[FixtureStep],
  authBootstrapPlan:  Option[AuthBootstrapPlan],
  resetStrategy:      ResetStrategy,
  teardownOrder:      List[String],
  observabilityTaps:  List[ObservabilityTap],
  generatedAt:        Instant,
  warnings:           List[String],
)

// Spec §3.2: ServiceSpec
case class ServiceSpec(
  serviceId:          String,
  kind:               ServiceKind,
  startupMode:        StartupMode,
  startupCommand:     Option[String],
  composeTarget:      Option[String],
  cwd:                String,
  env:                Map[String, String],
  envFile:            Option[String],
  ports:              List[PortMapping],
  dependencyServices: List[String],
  readinessProbe:     ReadinessProbe,
  shutdownMethod:     String,
  shutdownTimeoutMs:  Int,
  restartPolicy:      RestartPolicy,
  logsSource:         String,
  required:           Boolean,
)

// Spec §3.2: PortMapping
case class PortMapping(
  hostPort:           Option[Int],
  containerPort:      Int,
  protocol:           String,
)

// Spec §3.2: ReadinessProbe
case class ReadinessProbe(
  probeType:          String,
  target:             String,
  intervalMs:         Int,
  timeoutMs:          Int,
  maxFailures:        Int,
  initialDelayMs:     Int,
)

// Spec §3.2: RestartPolicy
case class RestartPolicy(
  maxRestarts:        Int,
  backoffBaseMs:      Int,
  backoffMaxMs:       Int,
  backoffMultiplier:  Double,
)

// Spec §3.2: FixtureStep
case class FixtureStep(
  stepId:             String,
  description:        String,
  command:            String,
  cwd:                String,
  env:                Map[String, String],
  timeoutMs:          Int,
  dependsOnServices:  List[String],
  runOnReset:         Boolean,
  runOnInitOnly:      Boolean,
  order:              Int,
)

// Spec §3.2: AuthBootstrapPlan
case class AuthBootstrapPlan(
  mode:               AuthMode,
  loginUrl:           Option[String],
  credentials:        Map[String, String],
  tokenEndpoint:      Option[String],
  staticToken:        Option[String],
  devBypassHeader:    Option[Map[String, String]],
  storageStateOutput: String,
)

// Spec §3.2: AuthContext
case class AuthContext(
  mode:               AuthMode,
  storageStatePath:   Option[String],
  apiHeaders:         Map[String, String],
  staticToken:        Option[String],
  devBypassHeaders:   Map[String, String],
  expiresAt:          Option[Instant],
)

// Spec §3.2: ObservabilityTap
case class ObservabilityTap(
  tapId:              String,
  serviceId:          String,
  tapType:            String,
  config:             Map[String, String],
)
