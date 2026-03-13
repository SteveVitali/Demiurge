package lastmile.repair

import java.nio.file.{Files, Path}

// Phase 5: PatchApplier — applies a PatchProposal to a worktree.
// Writes files, creates new files, deletes files.
// Stages changes via git add. No commit required.
// Never modifies the original repo — worktree only.
object PatchApplier {

  sealed trait ApplyResult
  case class ApplySuccess(filesChanged: List[String]) extends ApplyResult
  case class ApplyFailure(reason: String) extends ApplyResult

  def apply(proposal: PatchProposal, worktreePath: Path): ApplyResult = {
    if (proposal.isEmpty) {
      return ApplyFailure("Patch proposal is empty — nothing to apply")
    }

    try {
      // Apply file edits
      proposal.edits.foreach { edit =>
        val filePath = worktreePath.resolve(edit.relativePath)
        if (!Files.exists(filePath)) {
          return ApplyFailure(s"File not found for edit: ${edit.relativePath}")
        }
        val currentContent = new String(Files.readAllBytes(filePath), "UTF-8")
        if (edit.oldContent.isEmpty) {
          // Empty oldContent means full file replacement
          Files.write(filePath, edit.newContent.getBytes("UTF-8"))
        } else if (!currentContent.contains(edit.oldContent)) {
          return ApplyFailure(
            s"Edit target not found in ${edit.relativePath}: oldContent not present in file")
        } else {
          val updatedContent = currentContent.replace(edit.oldContent, edit.newContent)
          Files.write(filePath, updatedContent.getBytes("UTF-8"))
        }
      }

      // Create new files
      proposal.newFiles.foreach { newFile =>
        val filePath = worktreePath.resolve(newFile.relativePath)
        Files.createDirectories(filePath.getParent)
        Files.write(filePath, newFile.content.getBytes("UTF-8"))
      }

      // Delete files
      proposal.deletions.foreach { deletion =>
        val filePath = worktreePath.resolve(deletion.relativePath)
        Files.deleteIfExists(filePath)
      }

      // Stage changes via git add
      stageChanges(worktreePath, proposal)

      ApplySuccess(proposal.filesChanged)
    } catch {
      case e: Exception =>
        ApplyFailure(s"Failed to apply patch: ${e.getMessage}")
    }
  }

  private def stageChanges(worktreePath: Path, proposal: PatchProposal): Unit = {
    import scala.sys.process._
    val allFiles = proposal.filesChanged
    if (allFiles.nonEmpty) {
      val cmd = Seq("git", "add") ++ allFiles
      Process(cmd, worktreePath.toFile).!
    }
  }
}
