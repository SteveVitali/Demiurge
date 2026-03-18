package lastmile.repair

import java.time.Instant

// Phase 5: PatchProposal — represents a set of file changes proposed by a repair backend.
// Supports file edits, new files, and file deletions.
// Applied to worktree only — never modifies the original repo.
case class PatchProposal(
  patchId:        String,
  runId:          String,
  attemptNumber:  Int,
  backendId:      String,
  edits:          List[FileEdit],
  newFiles:       List[NewFile],
  deletions:      List[FileDeletion],
  summary:        String,
  hypotheses:     List[String],
  createdAt:      Instant,
) {
  def isEmpty: Boolean = edits.isEmpty && newFiles.isEmpty && deletions.isEmpty
  def filesChanged: List[String] = {
    edits.map(_.relativePath) ++ newFiles.map(_.relativePath) ++ deletions.map(_.relativePath)
  }.distinct
  def totalLinesAdded: Int = edits.map(_.newContent.count(_ == '\n')).sum + newFiles.map(_.content.count(_ == '\n')).sum
  def totalLinesRemoved: Int = edits.map(_.oldContent.count(_ == '\n')).sum + deletions.size
}

// A replacement edit to an existing file
case class FileEdit(
  relativePath:   String,
  oldContent:     String,
  newContent:     String,
)

// A new file to create
case class NewFile(
  relativePath:   String,
  content:        String,
)

// A file to delete
case class FileDeletion(
  relativePath:   String,
)
