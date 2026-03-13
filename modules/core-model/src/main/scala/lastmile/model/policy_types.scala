package lastmile.model

import java.time.Instant

// Spec §3.2: PolicySnapshot
case class PolicySnapshot(
  policySnapshotId:   String,
  runId:              String,
  capturedAt:         Instant,
  filesystemPolicy:   FilesystemPolicy,
  networkPolicy:      NetworkPolicy,
  browserPolicy:      BrowserPolicy,
  toolPolicy:         ToolPolicy,
  destructiveActionPolicy: DestructiveActionPolicy,
  executionBudget:    ExecutionBudget,
)

// Spec §3.2: FilesystemPolicy
case class FilesystemPolicy(
  allowedWritePaths:  List[String],
  forbiddenWritePaths: List[String],
  allowDeletePaths:   List[String],
)

// Spec §3.2: NetworkPolicy
case class NetworkPolicy(
  allowedHosts:       List[String],
  allowedPorts:       List[Int],
  allowExternalEgress: Boolean,
  externalAllowlist:  List[String],
)

// Spec §3.2: BrowserPolicy
case class BrowserPolicy(
  allowedOrigins:     List[String],
  forbiddenOrigins:   List[String],
  maxConcurrentContexts: Int,
)

// Spec §3.2: ToolPolicy
case class ToolPolicy(
  allowedTools:       List[String],
  forbiddenTools:     List[String],
  requireApprovalTools: List[String],
)

// Spec §3.2: DestructiveActionPolicy
case class DestructiveActionPolicy(
  allowGitCommit:     Boolean,
  allowGitPush:       Boolean,
  allowGitBranch:     Boolean,
  allowDbWrite:       Boolean,
  allowDbDrop:        Boolean,
  allowDockerVolumeRemove: Boolean,
  allowExternalSubmission: Boolean,
)
