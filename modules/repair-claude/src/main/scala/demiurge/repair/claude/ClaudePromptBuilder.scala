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
         |4. For edits, oldContent MUST be copied character-for-character from the source files provided below. NEVER paraphrase or guess file contents. If you cannot find the exact text, use newFiles instead.
         |5. Prefer "newFiles" when adding new functionality (e.g. new route handlers, new modules). Only use "edits" when you must modify existing lines and can copy oldContent exactly from the provided source.
         |6. Keep oldContent as short as possible — just the minimal unique snippet needed to locate the edit point.
         |7. Respond ONLY with the JSON object, no other text.""".stripMargin

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
         |4. For edits, oldContent MUST be copied character-for-character from the source files provided below. NEVER paraphrase or guess file contents.
         |5. Prefer "newFiles" for new functionality. Only use "edits" when modifying existing lines and you can copy oldContent exactly.
         |6. Keep oldContent as short as possible — just the minimal unique snippet needed to locate the edit point.
         |7. Respond ONLY with the JSON object, no other text.""".stripMargin
  }

  def buildSystemPrompt(): String = buildSystemPrompt(GenerationMode.Repair)

  def buildUserPrompt(
    packet: FailurePacket,
    context: RepairContext,
  ): String = {
    val sb = new StringBuilder

    sb.append("# Task\n")
    sb.append(context.taskText).append("\n\n")

    val failedVerdicts = context.verdicts.filter(v =>
      v.status == VerdictStatus.Fail || v.status == VerdictStatus.Timeout)

    // Build mode: show feature plan instead of failure context
    if (context.generationMode == GenerationMode.InitialBuild) {
      context.featurePlan.foreach { plan =>
        sb.append("# Feature Plan\n")
        sb.append(s"Summary: ${plan.summary}\n")
        if (plan.filesToCreate.nonEmpty) {
          sb.append("Files to create:\n")
          plan.filesToCreate.foreach(f => sb.append(s"  - ${f.relativePath}: ${f.description}\n"))
        }
        if (plan.filesToModify.nonEmpty) {
          sb.append("Files to modify:\n")
          plan.filesToModify.foreach(f => sb.append(s"  - ${f.relativePath}: ${f.description}\n"))
        }
        if (plan.requiresNewDeps.nonEmpty) {
          sb.append(s"New dependencies: ${plan.requiresNewDeps.mkString(", ")}\n")
        }
        sb.append("\n")
      }
      context.featureSpec.foreach { spec =>
        sb.append("# Feature Specification\n")
        sb.append(spec).append("\n\n")
      }
    } else {
      sb.append("# Failure Summary\n")
      sb.append(packet.summary).append("\n\n")

      if (failedVerdicts.nonEmpty) {
        sb.append("# Failed Requirements\n")
        failedVerdicts.foreach { v =>
          sb.append(s"- Requirement ${v.requirementId}: ${v.status}")
          v.failureMessage.foreach(msg => sb.append(s" — $msg"))
          sb.append("\n")
        }
        sb.append("\n")
      }

      // Suspected root causes
      if (packet.suspectedRootCauses.nonEmpty) {
        sb.append("# Suspected Root Causes\n")
        packet.suspectedRootCauses.foreach { cause =>
          sb.append(s"- ${cause.description} (confidence: ${cause.confidence})\n")
        }
        sb.append("\n")
      }
    }

    // Requirements graph (shown for both modes)
    sb.append("# Requirements\n")
    context.graph.nodes.foreach { node =>
      sb.append(s"- ${node.requirementId}: ${node.humanDescription} (${node.category}, ${node.priority})\n")
    }
    sb.append("\n")

    // Patch history
    if (context.patchHistory.nonEmpty) {
      sb.append("# Prior Patch Attempts\n")
      context.patchHistory.foreach { patch =>
        sb.append(s"- Attempt ${patch.attemptNumber}: ${patch.summary}\n")
        sb.append(s"  Files changed: ${patch.filesChanged.mkString(", ")}\n")
      }
      sb.append("\n")
    }

    // Service logs (Gap 5: observability taps)
    context.logs.foreach { logText =>
      sb.append("# Service Logs\n")
      sb.append(logText).append("\n\n")
    }

    // Relevant source files from worktree (with line numbers for exact reference)
    sb.append("# Relevant Source Files\n")
    sb.append("IMPORTANT: You may ONLY edit files listed below. Copy oldContent EXACTLY from these files. Do NOT guess or paraphrase.\n")
    // Extract keywords from task and failure context for file relevance scoring
    val failureKeywords = extractFailureKeywords(context.taskText, failedVerdicts)
    val relevantFiles = collectRelevantFiles(context.worktreePath, maxFiles = 8, maxSizeBytes = 5000, failureKeywords)
    System.err.println(s"[prompt] Failure keywords: ${failureKeywords.take(10).mkString(", ")}")
    System.err.println(s"[prompt] Collected ${relevantFiles.size} files: ${relevantFiles.map { case (p, c) => s"$p (${c.length}b)" }.mkString(", ")}")
    relevantFiles.foreach { case (relPath, content) =>
      val numbered = content.split("\n").zipWithIndex.map { case (line, i) =>
        f"${i + 1}%4d| $line"
      }.mkString("\n")
      sb.append(s"\n## $relPath\n```\n$numbered\n```\n")
    }

    sb.toString
  }

  // Spec §10.1: Build prompt from a RepairRequest (session-based interface)
  override def buildRepairRequestPrompt(request: RepairRequest): String = {
    val sb = new StringBuilder

    sb.append("# Task\n")
    sb.append(request.taskObjective).append("\n\n")

    sb.append("# Repository Summary\n")
    sb.append(request.repoSummary).append("\n\n")

    sb.append("# Failure Summary\n")
    sb.append(request.failurePacket.summary).append("\n\n")

    // Failed requirements
    sb.append("# Failed Requirements\n")
    request.requirementSubset.filter(r =>
      r.verdictStatus == VerdictStatus.Fail || r.verdictStatus == VerdictStatus.Timeout
    ).foreach { r =>
      sb.append(s"- ${r.requirementId}: ${r.humanDescription} (${r.category}, ${r.verdictStatus})")
      r.failureMessage.foreach(msg => sb.append(s" — $msg"))
      sb.append("\n")
    }
    sb.append("\n")

    // Suspected root causes
    if (request.failurePacket.suspectedRootCauses.nonEmpty) {
      sb.append("# Suspected Root Causes\n")
      request.failurePacket.suspectedRootCauses.foreach { cause =>
        sb.append(s"- ${cause.description} (confidence: ${cause.confidence})\n")
      }
      sb.append("\n")
    }

    // Prior attempt summaries
    if (request.priorAttemptSummaries.nonEmpty) {
      sb.append("# Prior Attempts\n")
      request.priorAttemptSummaries.foreach { prior =>
        sb.append(s"- Attempt ${prior.attemptNumber}: ${prior.outcome}")
        prior.patchSummary.foreach(s => sb.append(s" — $s"))
        sb.append(s"\n  Files: ${prior.filesChanged.mkString(", ")}\n")
      }
      sb.append("\n")
    }

    // Relevant changed files
    if (request.relevantChangedFiles.nonEmpty) {
      sb.append("# Changed Files\n")
      request.relevantChangedFiles.foreach(f => sb.append(s"- $f\n"))
      sb.append("\n")
    }

    // Scoped artifacts
    if (request.scopedArtifacts.nonEmpty) {
      sb.append("# Available Artifacts\n")
      request.scopedArtifacts.foreach { ref =>
        sb.append(s"- [${ref.artifactType}] ${ref.description}")
        ref.contentPreview.foreach(p => sb.append(s"\n  Preview: ${p.take(512)}"))
        sb.append("\n")
      }
      sb.append("\n")
    }

    // Rules of engagement
    if (request.rulesOfEngagement.nonEmpty) {
      sb.append("# Rules of Engagement\n")
      sb.append(request.rulesOfEngagement).append("\n\n")
    }

    sb.toString
  }

  // Common English words that are not useful for file relevance scoring
  private val StopWords = Set(
    "that", "this", "with", "from", "have", "will", "should", "would", "could",
    "been", "being", "were", "each", "which", "their", "than", "then", "them",
    "does", "doing", "done", "also", "into", "just", "only", "some", "such",
    "when", "what", "where", "make", "like", "very", "after", "before", "about",
    "other", "most", "more", "over", "your", "need", "want", "must", "instead",
    "returns", "expected", "because", "using", "used",
  )

  /** Extract keywords from task text and failure verdicts for file relevance scoring. */
  private def extractFailureKeywords(taskText: String, failedVerdicts: Seq[RequirementVerdict]): Set[String] = {
    val words = scala.collection.mutable.Set[String]()
    // Extract path segments from URLs in task text (e.g. "/api/health" → "health")
    val urlPattern = """/([\w-]+)""".r
    urlPattern.findAllMatchIn(taskText).foreach(m => words += m.group(1).toLowerCase)
    // Extract significant words from task text (filter stop words and short tokens)
    taskText.split("""[\s/.,;:(){}\[\]"']+""").filter(_.length > 3).foreach { w =>
      val lower = w.toLowerCase
      if (!StopWords.contains(lower)) words += lower
    }
    // Extract from failure messages
    failedVerdicts.flatMap(_.failureMessage).foreach { msg =>
      msg.split("""[\s/.,;:(){}\[\]"']+""").filter(_.length > 3).foreach { w =>
        val lower = w.toLowerCase
        if (!StopWords.contains(lower)) words += lower
      }
    }
    words.toSet
  }

  private def collectRelevantFiles(
    worktreePath: Path,
    maxFiles: Int,
    maxSizeBytes: Long,
    failureKeywords: Set[String] = Set.empty,
  ): List[(String, String)] = {
    if (!Files.exists(worktreePath) || !Files.isDirectory(worktreePath)) return Nil

    val sourceExtensions = Set(".scala", ".ts", ".tsx", ".js", ".jsx", ".py", ".java",
      ".html", ".css", ".json", ".yaml", ".yml", ".toml", ".md", ".sql", ".sh")

    val ignoredDirs = Set(".git", "node_modules", "target", ".demiurge", ".runs", "dist", "build")

    // Collect ALL eligible files first, then sort and take top N.
    // Cap at 200 files to bound memory usage in large repos.
    val MaxScanFiles = 200
    val result = scala.collection.mutable.ListBuffer[(String, String)]()

    def walk(dir: Path, depth: Int): Unit = {
      if (depth > 5 || result.size >= MaxScanFiles) return
      val entries = Files.list(dir)
      try {
        entries.forEach { entry =>
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
    // Score ALL files by failure-keyword matches, then take top N
    result.toList.sortBy { case (path, content) =>
      val lower = path.toLowerCase
      val contentLower = content.toLowerCase
      // Count keyword matches in file content and path
      val keywordHits = if (failureKeywords.nonEmpty) {
        failureKeywords.count(kw => contentLower.contains(kw) || lower.contains(kw))
      } else 0
      // Filename-based priority (fallback)
      val namePriority = if (lower.contains("route") || lower.contains("router")) 1
        else if (lower.contains("index.") || lower.contains("main.") || lower.contains("app.")) 2
        else if (lower.contains("config") || lower.contains("server")) 3
        else if (lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml")) 4
        else 5
      // Sort: most keyword hits first (negate for ascending), then by name priority
      (-keywordHits, namePriority, path)
    }.take(maxFiles)
  }
}
