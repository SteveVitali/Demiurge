package lastmile.model

import java.time.Instant

// Spec §3.2: ArtifactRecord
case class ArtifactRecord(
  artifactId:         String,
  runId:              String,
  attemptNumber:      Option[Int],
  artifactType:       ArtifactType,
  producerComponent:  String,
  logicalScope:       Option[String],
  relativePath:       String,
  contentType:        String,
  sizeBytes:          Long,
  checksumSha256:     String,
  compressed:         Boolean,
  compressionFormat:  Option[String],
  createdAt:          Instant,
  metadata:           Map[String, String],
)

// Spec §3.2: PatchRecord
case class PatchRecord(
  patchRecordId:      String,
  runId:              String,
  attemptNumber:      Int,
  diffArtifactId:     String,
  filesChanged:       List[String],
  totalLinesAdded:    Int,
  totalLinesRemoved:  Int,
  repairBackend:      String,
  repairSummary:      String,
  hypotheses:         List[String],
  requiresEnvRebuild: Boolean,
  infraSensitiveFiles: List[String],
  transcriptArtifactId: Option[String],
  usageRecordId:      String,
  appliedAt:          Instant,
  patchApplicationMethod: String,
  preApplyCommitSha:  String,
  postApplyCommitSha: Option[String],
)
