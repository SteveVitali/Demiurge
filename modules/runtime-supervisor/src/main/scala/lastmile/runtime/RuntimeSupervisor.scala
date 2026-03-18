package lastmile.runtime

import java.nio.file.Path

import lastmile.model._

// Spec §8: RuntimeSupervisor for Phase 3+5.
// Starts services in dependency order, runs readiness checks,
// executes fixture steps, builds RuntimeSnapshot.
// Phase 5: Added restartEnvironment for post-patch environment reboot.
// Does not implement: auth bootstrap, degrade recovery loops, reset strategies.
trait RuntimeSupervisor {
  def bootEnvironment(plan: RuntimePlan, repoRoot: Path): RuntimeSupervisor.BootResult
  def teardown(plan: RuntimePlan, repoRoot: Path): Unit
  // Phase 5: Restart environment after patch — teardown then boot
  def restartEnvironment(plan: RuntimePlan, repoRoot: Path): RuntimeSupervisor.BootResult = {
    teardown(plan, repoRoot)
    bootEnvironment(plan, repoRoot)
  }
}

object RuntimeSupervisor {
  sealed trait BootResult
  case class BootSuccess(snapshot: RuntimeSnapshot) extends BootResult
  case class BootFailure(reason: String, partialSnapshot: Option[RuntimeSnapshot]) extends BootResult
}
