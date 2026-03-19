package demiurge.agent

// Design: Agentic Browser UI Verification §7.3
// Parsed from the agent's structured JSON output after browser verification.

case class BrowserVerificationVerdict(
  verdict:          BrowserVerdictStatus,
  confidence:       Double,
  featureSatisfied: Boolean,
  observations:     List[BrowserObservation],
  tasteIssues:      List[TasteIssue],
  screenshots:      List[ScreenshotRef],
  summary:          String,
)

sealed trait BrowserVerdictStatus
object BrowserVerdictStatus {
  case object Pass       extends BrowserVerdictStatus
  case object Fail       extends BrowserVerdictStatus
  case object TasteIssue extends BrowserVerdictStatus

  def fromString(s: String): Option[BrowserVerdictStatus] = s.toUpperCase match {
    case "PASS"        => Some(Pass)
    case "FAIL"        => Some(Fail)
    case "TASTE_ISSUE" => Some(TasteIssue)
    case _             => None
  }
}

case class BrowserObservation(
  aspect:        String,
  status:        String,  // "pass" | "fail" | "warning"
  detail:        String,
  screenshotRef: Option[String],
)

case class TasteIssue(
  severity:      String,  // "error" | "warning" | "info"
  issue:         String,
  element:       Option[String],
  screenshotRef: Option[String],
)

case class ScreenshotRef(
  ref:         String,
  description: String,
  phase:       String,  // "before" | "during" | "after"
)
