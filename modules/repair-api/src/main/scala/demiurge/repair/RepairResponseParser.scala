package demiurge.repair

import java.time.Instant
import java.util.UUID

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import demiurge.model._

// Shared utility for parsing LLM repair responses into RepairResult and PatchProposal.
// Eliminates duplication between InferenceBackedRepairBackend and ClaudeRepairBackend.
object RepairResponseParser {

  /** Extract JSON from a response that may be wrapped in markdown code fences. */
  def extractJson(content: String): String = {
    val stripped = content.trim
    if (stripped.startsWith("```")) {
      val lines = stripped.split("\n").toList
      val start = 1
      val end = lines.lastIndexWhere(_.trim.startsWith("```"))
      if (end > start) lines.slice(start, end).mkString("\n")
      else stripped
    } else {
      stripped
    }
  }

  /** Parse LLM response into RepairResult (session-based interface). */
  def parseRepairResult(rawContent: String): Either[RepairBackendError, RepairResult] = {
    try {
      val jsonStr = extractJson(rawContent)
      parseJson(jsonStr) match {
        case Right(json) =>
          val cursor = json.hcursor
          val summary = cursor.downField("summary").as[String].getOrElse("Repair patch")
          val hypotheses = cursor.downField("hypotheses").as[List[String]].getOrElse(Nil)
          val editPaths = cursor.downField("edits").as[List[Json]].getOrElse(Nil).flatMap { obj =>
            obj.hcursor.downField("relativePath").as[String].toOption
          }
          val newFilePaths = cursor.downField("newFiles").as[List[Json]].getOrElse(Nil).flatMap { obj =>
            obj.hcursor.downField("relativePath").as[String].toOption
          }
          val deletionPaths = cursor.downField("deletions").as[List[Json]].getOrElse(Nil).flatMap { obj =>
            obj.hcursor.downField("relativePath").as[String].toOption
          }
          val allFiles = editPaths ++ newFilePaths ++ deletionPaths
          val requiresRebuild = cursor.downField("requiresEnvRebuild").as[Boolean].getOrElse(false)
          val status = if (allFiles.isEmpty) RepairResultStatus.NoChangeNeeded else RepairResultStatus.Success

          Right(RepairResult(
            status = status,
            fixSummary = summary,
            filesChanged = allFiles,
            hypotheses = hypotheses,
            requiresEnvRebuild = requiresRebuild,
            notes = Nil,
            rawTranscriptRef = None,
            usage = RepairUsageSummary(0, 0, 0, 0, 1, None),
          ))
        case Left(err) =>
          Left(RepairBackendError.MalformedOutput(rawContent, s"JSON parse error: ${err.getMessage}"))
      }
    } catch {
      case e: Exception =>
        Left(RepairBackendError.MalformedOutput(rawContent, s"Parse error: ${e.getMessage}"))
    }
  }

  /** Parse LLM response into PatchProposal (convenience interface). */
  def parsePatchProposal(
    rawContent: String,
    runId: String,
    attemptNumber: Int,
    backendId: String,
  ): Either[String, PatchProposal] = {
    try {
      val jsonStr = extractJson(rawContent)
      parseJson(jsonStr) match {
        case Right(json) =>
          val cursor = json.hcursor
          val summary = cursor.downField("summary").as[String].getOrElse("Repair patch")
          val hypotheses = cursor.downField("hypotheses").as[List[String]].getOrElse(Nil)

          val edits = cursor.downField("edits").as[List[Json]].getOrElse(Nil).flatMap { obj =>
            val c = obj.hcursor
            for {
              path <- c.downField("relativePath").as[String].toOption
              oldContent <- c.downField("oldContent").as[String].toOption
              newContent <- c.downField("newContent").as[String].toOption
            } yield FileEdit(path, oldContent, newContent)
          }

          val newFiles = cursor.downField("newFiles").as[List[Json]].getOrElse(Nil).flatMap { obj =>
            val c = obj.hcursor
            for {
              path <- c.downField("relativePath").as[String].toOption
              content <- c.downField("content").as[String].toOption
            } yield NewFile(path, content)
          }

          val deletions = cursor.downField("deletions").as[List[Json]].getOrElse(Nil).flatMap { obj =>
            obj.hcursor.downField("relativePath").as[String].toOption.map(FileDeletion)
          }

          Right(PatchProposal(
            patchId = UUID.randomUUID().toString,
            runId = runId,
            attemptNumber = attemptNumber,
            backendId = backendId,
            edits = edits,
            newFiles = newFiles,
            deletions = deletions,
            summary = summary,
            hypotheses = hypotheses,
            createdAt = Instant.now(),
          ))

        case Left(err) =>
          Left(s"JSON parse error: ${err.getMessage}")
      }
    } catch {
      case e: Exception =>
        Left(s"Parse error: ${e.getMessage}")
    }
  }

  /** Format an InferenceError into a human-readable message. */
  def inferenceErrorMessage(error: InferenceError): String = error match {
    case InferenceError.Timeout(_, elapsed) => s"Inference timeout after ${elapsed}ms"
    case InferenceError.BudgetExceeded(_, comp, remaining, _) => s"Budget exceeded for $comp (remaining: $remaining tokens)"
    case InferenceError.RateLimited(_, retryAfter) => s"Rate limited (retry after ${retryAfter}ms)"
    case InferenceError.MalformedResponse(_, _, parseErr) => s"Malformed response: $parseErr"
    case InferenceError.ProviderError(_, code, message) => s"Provider error HTTP $code: $message"
    case InferenceError.SchemaValidationFailed(_, _, errors) => s"Schema validation: ${errors.mkString(", ")}"
  }
}
