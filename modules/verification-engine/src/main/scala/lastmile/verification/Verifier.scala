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

// Phase 4: Verifier execution result
sealed trait VerifierOutcome
object VerifierOutcome {
  case object Passed extends VerifierOutcome
  case class Failed(message: String) extends VerifierOutcome
  case class Error(message: String) extends VerifierOutcome
  case object TimedOut extends VerifierOutcome
}
