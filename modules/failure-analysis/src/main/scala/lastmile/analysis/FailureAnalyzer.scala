package lastmile.analysis

import lastmile.model._
import lastmile.inference.InferenceService

// Spec §5.2: Failure Analyzer — produces FailurePacket with root cause hypotheses.
// Uses InferenceService for LLM-backed analysis, with rule-based fallback (confidence 0.3).
trait FailureAnalyzer {
  /**
   * Analyze verification failures and produce a FailurePacket.
   * Spec §5.2: On inference failure, produce rule-based FailurePacket with confidence 0.3
   * and failureClass UnknownFailure.
   */
  def analyze(
    runId: String,
    attemptNumber: Int,
    verdicts: List[RequirementVerdict],
    graph: RequirementGraph,
    taskText: String,
    changedFiles: Option[List[String]],
  ): FailurePacket
}
