package lastmile.verification

import java.time.Duration

// Phase 4: Verifier types for in-process execution
// Maps from the spec VerifierSpec to executable verifiers

sealed trait Verifier {
  def id: String
  def requirementId: String
  def timeout: Duration
  def maxRetries: Int
}

case class HttpVerifier(
  id:            String,
  requirementId: String,
  method:        String,
  url:           String,
  headers:       Map[String, String],
  expectedStatus: Int,
  timeout:       Duration,
  maxRetries:    Int,
) extends Verifier

case class TcpVerifier(
  id:            String,
  requirementId: String,
  host:          String,
  port:          Int,
  timeout:       Duration,
  maxRetries:    Int,
) extends Verifier

case class ExecVerifier(
  id:            String,
  requirementId: String,
  command:       List[String],
  expectedExit:  Int,
  timeout:       Duration,
  maxRetries:    Int,
) extends Verifier

case class LogContainsVerifier(
  id:            String,
  requirementId: String,
  logPath:       String,
  pattern:       String,
  forbidden:     Boolean,
  timeout:       Duration,
  maxRetries:    Int,
) extends Verifier

case class StateVerifier(
  id:            String,
  requirementId: String,
  timeout:       Duration,
  maxRetries:    Int,
) extends Verifier

// Phase 6: BrowserFlowVerifier — dispatches to worker process via WorkerProcessManager
// Phase 8: Added selectorFallbacks for orchestrator-side fallback (Spec §11.5)
case class BrowserFlowVerifier(
  id:               String,
  requirementId:    String,
  entryUrl:         String,
  actions:          List[lastmile.model.BrowserAction],
  assertions:       List[lastmile.model.Assertion],
  artifactPlan:     List[lastmile.model.ArtifactCapture],
  storageStatePath: Option[String],
  timeout:          Duration,
  maxRetries:       Int,
  // Phase 8: Selector fallback map — key is primary selector value, value is fallback SelectorRef.
  // Spec §11.5: If worker returns SELECTOR_NOT_FOUND (-32011), orchestrator re-dispatches with fallback.
  selectorFallbacks: Map[String, lastmile.model.SelectorRef] = Map.empty,
) extends Verifier

// Phase 6: Extended verifier outcome with artifact refs and observations
case class BrowserVerifierResult(
  outcome:      VerifierOutcome,
  observations: List[lastmile.model.Observation],
  artifactRefs: List[String],
)

// Phase 4: Verifier execution result
sealed trait VerifierOutcome
object VerifierOutcome {
  case object Passed extends VerifierOutcome
  case class Failed(message: String) extends VerifierOutcome
  case class Error(message: String) extends VerifierOutcome
  case object TimedOut extends VerifierOutcome
}
