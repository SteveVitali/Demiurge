package lastmile.repair.claude

import java.time.Instant
import java.util.UUID

import lastmile.model._
import lastmile.repair._

// Phase 5: ClaudeRepairBackend — implements RepairBackend using Claude API.
// Builds a prompt from the failure packet and context, sends it to Claude,
// parses the response into a PatchProposal.
class ClaudeRepairBackend(model: Option[String] = None) extends RepairBackend {

  override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
    val systemPrompt = ClaudePromptBuilder.buildSystemPrompt()
    val userPrompt = ClaudePromptBuilder.buildUserPrompt(packet, context)

    ClaudeClient.sendMessage(systemPrompt, userPrompt, model) match {
      case Right(response) =>
        parseProposal(response.content, context) match {
          case Right(proposal) =>
            if (proposal.isEmpty) {
              RepairResponse.InvalidPatch("Claude returned empty patch — no edits, new files, or deletions")
            } else {
              RepairResponse.Success(proposal)
            }
          case Left(error) =>
            RepairResponse.InvalidPatch(s"Failed to parse Claude response: $error")
        }

      case Left(error) =>
        RepairResponse.Failed(s"Claude API error: ${error.message} (HTTP ${error.statusCode})")
    }
  }

  private def parseProposal(
    rawContent: String,
    context: RepairContext,
  ): Either[String, PatchProposal] = {
    try {
      // Extract JSON from response (may be wrapped in markdown code blocks)
      val json = extractJson(rawContent)

      val summary = extractString(json, "summary").getOrElse("Repair patch from Claude")
      val hypotheses = extractStringArray(json, "hypotheses")

      val edits = extractEdits(json)
      val newFiles = extractNewFiles(json)
      val deletions = extractDeletions(json)

      Right(PatchProposal(
        patchId = UUID.randomUUID().toString,
        runId = context.runId,
        attemptNumber = context.attemptNumber,
        backendId = "claude",
        edits = edits,
        newFiles = newFiles,
        deletions = deletions,
        summary = summary,
        hypotheses = hypotheses,
        createdAt = Instant.now(),
      ))
    } catch {
      case e: Exception =>
        Left(s"Parse error: ${e.getMessage}")
    }
  }

  private def extractJson(content: String): String = {
    // Strip markdown code blocks if present
    val stripped = content.trim
    if (stripped.startsWith("```")) {
      val lines = stripped.split("\n").toList
      // Skip the opening ``` line (possibly with language tag like ```json)
      val start = 1
      val end = lines.lastIndexWhere(_.trim.startsWith("```"))
      if (end > start) lines.slice(start, end).mkString("\n")
      else stripped
    } else {
      stripped
    }
  }

  private def extractString(json: String, key: String): Option[String] = {
    val pattern = s""""$key"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1)
      .replace("\\n", "\n")
      .replace("\\t", "\t")
      .replace("\\\"", "\"")
      .replace("\\\\", "\\"))
  }

  private def extractStringArray(json: String, key: String): List[String] = {
    val pattern = s""""$key"\\s*:\\s*\\[(.*?)\\]""".r
    pattern.findFirstMatchIn(json) match {
      case Some(m) =>
        val arrayContent = m.group(1)
        """"((?:[^"\\]|\\.)*)"""".r.findAllMatchIn(arrayContent)
          .map(_.group(1)).toList
      case None => Nil
    }
  }

  private def extractEdits(json: String): List[FileEdit] = {
    val editsBlock = extractArrayBlock(json, "edits")
    if (editsBlock.isEmpty) return Nil

    val objectPattern = """\{[^{}]*\}""".r
    objectPattern.findAllIn(editsBlock).flatMap { obj =>
      for {
        path <- extractString(obj, "relativePath")
        oldContent <- extractString(obj, "oldContent")
        newContent <- extractString(obj, "newContent")
      } yield FileEdit(path, oldContent, newContent)
    }.toList
  }

  private def extractNewFiles(json: String): List[NewFile] = {
    val block = extractArrayBlock(json, "newFiles")
    if (block.isEmpty) return Nil

    val objectPattern = """\{[^{}]*\}""".r
    objectPattern.findAllIn(block).flatMap { obj =>
      for {
        path <- extractString(obj, "relativePath")
        content <- extractString(obj, "content")
      } yield NewFile(path, content)
    }.toList
  }

  private def extractDeletions(json: String): List[FileDeletion] = {
    val block = extractArrayBlock(json, "deletions")
    if (block.isEmpty) return Nil

    val objectPattern = """\{[^{}]*\}""".r
    objectPattern.findAllIn(block).flatMap { obj =>
      extractString(obj, "relativePath").map(FileDeletion)
    }.toList
  }

  private def extractArrayBlock(json: String, key: String): String = {
    val idx = json.indexOf(s""""$key"""")
    if (idx < 0) return ""

    val bracketStart = json.indexOf('[', idx)
    if (bracketStart < 0) return ""

    var depth = 0
    var i = bracketStart
    while (i < json.length) {
      json.charAt(i) match {
        case '[' => depth += 1
        case ']' =>
          depth -= 1
          if (depth == 0) return json.substring(bracketStart, i + 1)
        case _ =>
      }
      i += 1
    }
    ""
  }
}
