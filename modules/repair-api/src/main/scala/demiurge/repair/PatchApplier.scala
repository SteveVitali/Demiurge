package demiurge.repair

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
          // Try whitespace-normalized matching: strip trailing whitespace per line
          val normalizedContent = normalizeWhitespace(currentContent)
          val normalizedOld = normalizeWhitespace(edit.oldContent)
          if (normalizedOld.nonEmpty && normalizedContent.contains(normalizedOld)) {
            // Find the original substring that matches after normalization
            val updatedContent = fuzzyReplace(currentContent, edit.oldContent, edit.newContent)
            Files.write(filePath, updatedContent.getBytes("UTF-8"))
          } else {
            return ApplyFailure(
              s"Edit target not found in ${edit.relativePath}: oldContent not present in file")
          }
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

  /** Normalize whitespace: strip trailing whitespace per line and normalize line endings. */
  private def normalizeWhitespace(s: String): String =
    s.split("\n", -1).map(_.replaceAll("\\s+$", "")).mkString("\n")

  /** Replace oldContent in source using whitespace-normalized matching. */
  private def fuzzyReplace(source: String, oldContent: String, newContent: String): String = {
    val normSource = normalizeWhitespace(source)
    val normOld = normalizeWhitespace(oldContent)
    val idx = normSource.indexOf(normOld)
    if (idx < 0) return source

    // Map normalized index back to original source position
    // Walk both strings counting characters, skipping trailing whitespace differences
    val sourceLines = source.split("\n", -1)
    val normLines = normSource.split("\n", -1)

    // Find the start line in normalized text
    var charCount = 0
    var startLine = 0
    for (i <- normLines.indices if charCount <= idx) {
      if (charCount + normLines(i).length >= idx) {
        startLine = i
      }
      charCount += normLines(i).length + 1 // +1 for \n
    }

    // Count lines in normalized oldContent
    val oldLines = normOld.split("\n", -1).length

    // Replace those lines in the original source
    val before = sourceLines.take(startLine).mkString("\n")
    val after = sourceLines.drop(startLine + oldLines).mkString("\n")
    val prefix = if (before.nonEmpty) before + "\n" else ""
    val suffix = if (after.nonEmpty) "\n" + after else ""
    prefix + newContent + suffix
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
