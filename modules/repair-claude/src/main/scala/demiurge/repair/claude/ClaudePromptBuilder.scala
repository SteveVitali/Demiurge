package demiurge.repair.claude

import java.nio.file.{Files, Path}

import demiurge.model._
import demiurge.repair._

// Phase 5: ClaudePromptBuilder — builds system and user prompts for Claude repair.
// Includes: failure packet, repo files, requirements, verifier failures, patch history.
// Spec §2.3: Extends RepairPromptBuilder trait for use with InferenceBackedRepairBackend.
object ClaudePromptBuilder extends RepairPromptBuilder {

  private val JsonResponseFormat: String =
    """|RESPONSE FORMAT:
       |You must respond with a JSON object containing the patch proposal. The format is:
       |{
       |  "summary": "Brief description of what the patch does",
       |  "hypotheses": ["hypothesis about the root cause"],
       |  "edits": [
       |    {
       |      "relativePath": "path/to/file.ext",
       |      "oldContent": "the exact text to replace",
       |      "newContent": "the replacement text"
       |    }
       |  ],
       |  "newFiles": [
       |    {
       |      "relativePath": "path/to/new/file.ext",
       |      "content": "full file content"
       |    }
       |  ],
       |  "deletions": [
       |    {
       |      "relativePath": "path/to/delete.ext"
       |    }
       |  ]
       |}""".stripMargin

  override def buildSystemPrompt(mode: GenerationMode = GenerationMode.Repair): String = mode match {
    case GenerationMode.Repair =>
      s"""You are a code repair agent. You receive a failure packet describing verification failures
         |in a web application, along with the relevant source code and requirements.
         |
         |Your job is to propose a patch that fixes the failing verifications.
         |
         |$JsonResponseFormat
         |
         |RULES:
         |1. Only modify files in the worktree.
         |2. Keep changes minimal — fix only what is needed.
         |3. Do not add unrelated changes.
         |4. Ensure edits contain exact text matches for oldContent.
         |5. Respond ONLY with the JSON object, no other text.""".stripMargin

    case GenerationMode.InitialBuild =>
      s"""You are a code generation agent. You receive a task description, requirements,
         |and the current state of a repository. Your job is to produce code that implements
         |the requested feature from scratch.
         |
         |$JsonResponseFormat
         |
         |RULES:
         |1. Only modify files in the worktree.
         |2. Implement the feature fully — create all necessary files and modifications.
         |3. Follow existing code conventions in the repository.
         |4. Ensure edits contain exact text matches for oldContent.
         |5. Respond ONLY with the JSON object, no other text.""".stripMargin
  }

  def buildSystemPrompt(): String = buildSystemPrompt(GenerationMode.Repair)

  def buildUserPrompt(
    packet: FailurePacket,
    context: RepairContext,
  ): String = {
    val sb = new StringBuilder

    sb.append("# Task\n")
    sb.append(context.taskText).append("\n\n")

    sb.append("# Failure Summary\n")
    sb.append(packet.summary).append("\n\n")

    // Failed requirements
    sb.append("# Failed Requirements\n")
    val failedVerdicts = context.verdicts.filter(v =>
      v.status == VerdictStatus.Fail || v.status == VerdictStatus.Timeout)
    failedVerdicts.foreach { v =>
      sb.append(s"- Requirement ${v.requirementId}: ${v.status}")
      v.failureMessage.foreach(msg => sb.append(s" — $msg"))
      sb.append("\n")
    }
    sb.append("\n")

    // Requirements graph
    sb.append("# Requirements\n")
    context.graph.nodes.foreach { node =>
      sb.append(s"- ${node.requirementId}: ${node.humanDescription} (${node.category}, ${node.priority})\n")
    }
    sb.append("\n")

    // Suspected root causes
    if (packet.suspectedRootCauses.nonEmpty) {
      sb.append("# Suspected Root Causes\n")
      packet.suspectedRootCauses.foreach { cause =>
        sb.append(s"- ${cause.description} (confidence: ${cause.confidence})\n")
      }
      sb.append("\n")
    }

    // Patch history
    if (context.patchHistory.nonEmpty) {
      sb.append("# Prior Patch Attempts\n")
      context.patchHistory.foreach { patch =>
        sb.append(s"- Attempt ${patch.attemptNumber}: ${patch.summary}\n")
        sb.append(s"  Files changed: ${patch.filesChanged.mkString(", ")}\n")
      }
      sb.append("\n")
    }

    // Relevant source files from worktree
    sb.append("# Relevant Source Files\n")
    val relevantFiles = collectRelevantFiles(context.worktreePath, maxFiles = 20, maxSizeBytes = 50000)
    relevantFiles.foreach { case (relPath, content) =>
      sb.append(s"\n## $relPath\n```\n$content\n```\n")
    }

    sb.toString
  }

  private def collectRelevantFiles(
    worktreePath: Path,
    maxFiles: Int,
    maxSizeBytes: Long,
  ): List[(String, String)] = {
    if (!Files.exists(worktreePath) || !Files.isDirectory(worktreePath)) return Nil

    val sourceExtensions = Set(".scala", ".ts", ".tsx", ".js", ".jsx", ".py", ".java",
      ".html", ".css", ".json", ".yaml", ".yml", ".toml", ".md", ".sql", ".sh")

    val ignoredDirs = Set(".git", "node_modules", "target", ".demiurge", ".runs", "dist", "build")

    val result = scala.collection.mutable.ListBuffer[(String, String)]()

    def walk(dir: Path, depth: Int): Unit = {
      if (depth > 5 || result.size >= maxFiles) return
      val entries = Files.list(dir)
      try {
        entries.forEach { entry =>
          if (result.size >= maxFiles) return
          val name = entry.getFileName.toString
          if (Files.isDirectory(entry)) {
            if (!ignoredDirs.contains(name)) walk(entry, depth + 1)
          } else {
            val ext = if (name.contains(".")) name.substring(name.lastIndexOf(".")) else ""
            if (sourceExtensions.contains(ext) && Files.size(entry) <= maxSizeBytes) {
              val relPath = worktreePath.relativize(entry).toString
              val content = new String(Files.readAllBytes(entry), "UTF-8")
              result += ((relPath, content))
            }
          }
        }
      } finally {
        entries.close()
      }
    }

    walk(worktreePath, 0)
    result.toList
  }
}
