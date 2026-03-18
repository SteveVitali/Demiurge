package lastmile.orchestrator

import java.nio.file.Path
import java.sql.Connection
import lastmile.model.TaskRun

// Spec §4.1: Immutable snapshot of run state for a single orchestrator step.
// The orchestrator loop holds a var pointing to successive RunContext instances.
case class RunContext(
  run:          TaskRun,
  repoRoot:     Path,
  worktreePath: Path,
  conn:         Connection,
)
