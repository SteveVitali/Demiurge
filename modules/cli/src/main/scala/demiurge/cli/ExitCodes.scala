package demiurge.cli

import demiurge.model.RunStatus

// Phase 7: Exit codes per canonical spec §14.3
// Run-lifecycle commands: 0=success, 1=exhausted, 2=cancelled, 3=errored, 4=input error, 5=concurrent run conflict, 10=resume failed
// Non-run commands: 0=success, 1=command-specific failure, 4=input/not-found error
object ExitCodes {
  val Success: Int             = 0
  val Exhausted: Int           = 1
  val Cancelled: Int           = 2
  val Errored: Int             = 3
  val InputError: Int          = 4
  val ConcurrentRunConflict: Int = 5
  val ResumeFailed: Int        = 10

  // Non-run commands
  val CommandFailure: Int      = 1
  val NotFound: Int            = 4

  def fromRunStatus(status: RunStatus): Int = status match {
    case RunStatus.Succeeded   => Success
    case RunStatus.Exhausted   => Exhausted
    case RunStatus.Cancelled   => Cancelled
    case RunStatus.Interrupted => Cancelled
    case _                     => Errored
  }
}
