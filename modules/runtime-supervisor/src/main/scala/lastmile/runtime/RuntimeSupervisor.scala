package lastmile.runtime

import java.nio.file.Path

import lastmile.model._

// Spec §8: RuntimeSupervisor for Phase 3.
// Starts services in dependency order, runs readiness checks,
// executes fixture steps, builds RuntimeSnapshot.
// Does not implement: auth bootstrap, degrade recovery loops, reset strategies.
trait RuntimeSupervisor {
  def bootEnvironment(plan: RuntimePlan, repoRoot: Path): RuntimeSupervisor.BootResult
  def teardown(plan: RuntimePlan, repoRoot: Path): Unit
}

object RuntimeSupervisor {
  sealed trait BootResult
  case class BootSuccess(snapshot: RuntimeSnapshot) extends BootResult
  case class BootFailure(reason: String, partialSnapshot: Option[RuntimeSnapshot]) extends BootResult
}
