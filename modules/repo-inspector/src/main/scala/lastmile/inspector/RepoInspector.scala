package lastmile.inspector

import java.nio.file.Path
import lastmile.model.RepoInspectionReport

// Spec §5: Repo Inspector trait — compile-only placeholder for Phase 2
trait RepoInspector {
  def inspect(runId: String, repoRoot: Path, changedFiles: Option[List[String]]): RepoInspectionReport
}
