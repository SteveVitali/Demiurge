package lastmile.model

import java.nio.file.Path
import java.time.Instant

// Spec §3.2: RepairSessionConfig
case class RepairSessionConfig(
  runId:              String,
  attemptNumber:      Int,
  worktreePath:       Path,
  toolPolicy:         ToolPolicy,
  destructivePolicy:  DestructiveActionPolicy,
  filesystemPolicy:   FilesystemPolicy,
  maxRuntimeMs:       Long,
  maxTokens:          Long,
  model:              Option[String],
)

// Spec §3.2: RepairSessionHandle
case class RepairSessionHandle(
  sessionId:          String,
  backendId:          String,
  createdAt:          Instant,
)

// Spec §3.2: RepairRequest
case class RepairRequest(
  taskObjective:      String,
  repoSummary:        String,
  relevantChangedFiles: List[String],
  requirementSubset:  List[RequirementSummary],
  failurePacket:      FailurePacket,
  scopedArtifacts:    List[ScopedArtifactRef],
  rulesOfEngagement:  String,
  outputContract:     RepairOutputContract,
  priorAttemptSummaries: List[PriorAttemptSummary],
)

// Spec §3.2: RequirementSummary
case class RequirementSummary(
  requirementId:      String,
  humanDescription:   String,
  category:           RequirementCategory,
  verdictStatus:      VerdictStatus,
  failureMessage:     Option[String],
)

// Spec §3.2: ScopedArtifactRef
case class ScopedArtifactRef(
  artifactType:       ArtifactType,
  description:        String,
  contentPreview:     Option[String],
  relativePath:       String,
)

// Spec §3.2: PriorAttemptSummary
case class PriorAttemptSummary(
  attemptNumber:      Int,
  patchSummary:       Option[String],
  filesChanged:       List[String],
  failureClasses:     List[FailureClass],
  outcome:            String,
)

// Spec §3.2: RepairOutputContract
case class RepairOutputContract(
  requireFixSummary:          Boolean,
  requireFilesChanged:        Boolean,
  requireHypothesisLink:      Boolean,
  requireEnvRebuildFlag:      Boolean,
  maxResponseTokens:          Int,
)

// Spec §3.2: RepairResult
case class RepairResult(
  status:             RepairResultStatus,
  fixSummary:         String,
  filesChanged:       List[String],
  hypotheses:         List[String],
  requiresEnvRebuild: Boolean,
  notes:              List[String],
  rawTranscriptRef:   Option[String],
  usage:              RepairUsageSummary,
)

// Spec §3.2: RepairUsageSummary
case class RepairUsageSummary(
  inputTokens:        Long,
  outputTokens:       Long,
  totalTokens:        Long,
  durationMs:         Long,
  toolCallCount:      Int,
  estimatedCostUsd:   Option[Double],
)
