package demiurge.repair

import java.time.Instant
import java.util.UUID

import io.circe.{Json, HCursor}
import io.circe.parser.{parse => parseJson}

import demiurge.model._
import demiurge.inference.{AnthropicInferenceBackend, InferenceService}

// Spec §2.2: RepairBackend backed by InferenceService.
// Routes all LLM calls through InferenceService for budget/cache/retry/audit.
class InferenceBackedRepairBackend(
  inferenceService: InferenceService,
  promptBuilder: RepairPromptBuilder,
  model: Option[String] = None,
) extends RepairBackend {

  override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
    val selectedModel = model.getOrElse(AnthropicInferenceBackend.DefaultModel)
    val systemPrompt = promptBuilder.buildSystemPrompt(context.generationMode)
    val userPrompt = promptBuilder.buildUserPrompt(packet, context)

    val request = InferenceRequest(
      requestId = UUID.randomUUID().toString,
      runId = context.runId,
      attemptNumber = Some(context.attemptNumber),
      component = "repair_backend",
      provider = InferenceProvider.Anthropic,
      model = selectedModel,
      systemPrompt = systemPrompt,
      userPrompt = userPrompt,
      responseFormat = Some("json"),
      jsonSchema = None,
      maxOutputTokens = 8192,
      temperature = 0.2,
      cacheable = false,
      timeoutMs = 120000L,
      metadata = Map.empty,
    )

    inferenceService.infer(request) match {
      case Right(response) =>
        parseProposal(response.responseText, context) match {
          case Right(proposal) =>
            if (proposal.isEmpty) {
              RepairResponse.InvalidPatch("LLM returned empty patch — no edits, new files, or deletions")
            } else {
              RepairResponse.Success(proposal)
            }
          case Left(error) =>
            RepairResponse.InvalidPatch(s"Failed to parse LLM response: $error")
        }

      case Left(error) =>
        val msg = error match {
          case InferenceError.Timeout(_, elapsed) => s"Inference timeout after ${elapsed}ms"
          case InferenceError.BudgetExceeded(_, comp, remaining, _) => s"Budget exceeded for $comp (remaining: $remaining tokens)"
          case InferenceError.RateLimited(_, retryAfter) => s"Rate limited (retry after ${retryAfter}ms)"
          case InferenceError.MalformedResponse(_, _, parseErr) => s"Malformed response: $parseErr"
          case InferenceError.ProviderError(_, code, message) => s"Provider error HTTP $code: $message"
          case InferenceError.SchemaValidationFailed(_, _, errors) => s"Schema validation: ${errors.mkString(", ")}"
        }
        RepairResponse.Failed(s"Inference error: $msg")
    }
  }

  private def parseProposal(
    rawContent: String,
    context: RepairContext,
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
            runId = context.runId,
            attemptNumber = context.attemptNumber,
            backendId = "inference",
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

  private def extractJson(content: String): String = {
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
}
