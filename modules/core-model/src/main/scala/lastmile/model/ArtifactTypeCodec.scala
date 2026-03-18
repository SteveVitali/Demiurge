package lastmile.model

// Phase 6: Shared ArtifactType ↔ String codec to avoid duplication across modules.
object ArtifactTypeCodec {

  def toString(at: ArtifactType): String = at match {
    case ArtifactType.Plan                   => "Plan"
    case ArtifactType.ServiceLog             => "ServiceLog"
    case ArtifactType.StartupTimeline        => "StartupTimeline"
    case ArtifactType.StdoutExcerpt          => "StdoutExcerpt"
    case ArtifactType.StderrExcerpt          => "StderrExcerpt"
    case ArtifactType.BrowserTrace           => "BrowserTrace"
    case ArtifactType.Screenshot             => "Screenshot"
    case ArtifactType.DomSnapshot            => "DomSnapshot"
    case ArtifactType.AccessibilitySnapshot  => "AccessibilitySnapshot"
    case ArtifactType.ConsoleLog             => "ConsoleLog"
    case ArtifactType.NetworkSummary         => "NetworkSummary"
    case ArtifactType.ApiRequestResponse     => "ApiRequestResponse"
    case ArtifactType.DbQueryResult          => "DbQueryResult"
    case ArtifactType.QueueObservation       => "QueueObservation"
    case ArtifactType.PatchDiff              => "PatchDiff"
    case ArtifactType.StructuredVerdict      => "StructuredVerdict"
    case ArtifactType.FailurePacketArtifact  => "FailurePacketArtifact"
    case ArtifactType.FinalReport            => "FinalReport"
    case ArtifactType.RepairTranscript       => "RepairTranscript"
    case ArtifactType.InferenceLog           => "InferenceLog"
    case ArtifactType.RepoInspectionArtifact => "RepoInspectionArtifact"
    case ArtifactType.AuthStorageState       => "AuthStorageState"
    case ArtifactType.PromptPackage          => "PromptPackage"
    case ArtifactType.AttemptReport          => "AttemptReport"
  }

  def fromString(s: String): ArtifactType = s match {
    case "Plan"                   => ArtifactType.Plan
    case "ServiceLog"             => ArtifactType.ServiceLog
    case "StartupTimeline"        => ArtifactType.StartupTimeline
    case "StdoutExcerpt"          => ArtifactType.StdoutExcerpt
    case "StderrExcerpt"          => ArtifactType.StderrExcerpt
    case "BrowserTrace"           => ArtifactType.BrowserTrace
    case "Screenshot"             => ArtifactType.Screenshot
    case "DomSnapshot"            => ArtifactType.DomSnapshot
    case "AccessibilitySnapshot"  => ArtifactType.AccessibilitySnapshot
    case "ConsoleLog"             => ArtifactType.ConsoleLog
    case "NetworkSummary"         => ArtifactType.NetworkSummary
    case "ApiRequestResponse"     => ArtifactType.ApiRequestResponse
    case "DbQueryResult"          => ArtifactType.DbQueryResult
    case "QueueObservation"       => ArtifactType.QueueObservation
    case "PatchDiff"              => ArtifactType.PatchDiff
    case "StructuredVerdict"      => ArtifactType.StructuredVerdict
    case "FailurePacketArtifact"  => ArtifactType.FailurePacketArtifact
    case "FinalReport"            => ArtifactType.FinalReport
    case "RepairTranscript"       => ArtifactType.RepairTranscript
    case "InferenceLog"           => ArtifactType.InferenceLog
    case "RepoInspectionArtifact" => ArtifactType.RepoInspectionArtifact
    case "AuthStorageState"       => ArtifactType.AuthStorageState
    case "PromptPackage"          => ArtifactType.PromptPackage
    case "AttemptReport"          => ArtifactType.AttemptReport
    case other                    => throw new IllegalArgumentException(s"Unknown ArtifactType: $other")
  }
}
