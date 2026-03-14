package demiurge.model

import java.time.Instant

// Spec §3.2: RuntimeSnapshot
case class RuntimeSnapshot(
  snapshotId:         String,
  runId:              String,
  capturedAt:         Instant,
  environmentStatus:  EnvironmentStatus,
  services:           List[ServiceSnapshot],
  activePortMappings: Map[String, List[PortMapping]],
  resolvedUrls:       Map[String, String],
  uptimeMs:           Long,
)

// Spec §3.2: ServiceSnapshot
case class ServiceSnapshot(
  serviceId:          String,
  status:             ServiceStatus,
  pid:                Option[Long],
  containerId:        Option[String],
  healthyAt:          Option[Instant],
  lastProbeResult:    Option[String],
  restartCount:       Int,
  logTailLines:       List[String],
)
