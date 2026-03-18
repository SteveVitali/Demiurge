package demiurge.repair

import java.time.Instant
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import demiurge.model._
import demiurge.inference.{AnthropicInferenceBackend, InferenceService}

// Spec §10.1: RepairBackend backed by InferenceService.
// Routes all LLM calls through InferenceService for budget/cache/retry/audit.
// Implements full session-based interface per spec §10.1.
class InferenceBackedRepairBackend(
  inferenceService: InferenceService,
  promptBuilder: RepairPromptBuilder,
  model: Option[String] = None,
) extends RepairBackend {

  override val backendId: String = "inference"

  // Spec §10.1: Session state tracked in-memory per handle
  private val sessions = scala.collection.concurrent.TrieMap.empty[String, SessionInfo]

  private case class SessionInfo(
    config: RepairSessionConfig,
    handle: RepairSessionHandle,
    startedAt: Instant,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var durationMs: Long = 0,
    var toolCallCount: Int = 0,
    var cancelled: Boolean = false,
  )

  override def prepareSession(config: RepairSessionConfig): Either[RepairBackendError, RepairSessionHandle] = {
    val handle = RepairSessionHandle(
      sessionId = UUID.randomUUID().toString,
      backendId = backendId,
      createdAt = Instant.now(),
    )
    sessions.put(handle.sessionId, SessionInfo(config, handle, Instant.now()))
    Right(handle)
  }

  override def submitRepairTask(handle: RepairSessionHandle, request: RepairRequest): Either[RepairBackendError, RepairResult] = {
    sessions.get(handle.sessionId) match {
      case None => Left(RepairBackendError.SessionCreationFailed(s"Session ${handle.sessionId} not found"))
      case Some(info) if info.cancelled => Left(RepairBackendError.TaskSubmissionFailed("Session was cancelled"))
      case Some(info) =>
        val selectedModel = model.getOrElse(AnthropicInferenceBackend.DefaultModel)
        val systemPrompt = promptBuilder.buildSystemPrompt(request.generationMode)
        val userPrompt = promptBuilder.buildRepairRequestPrompt(request)

        val inferReq = InferenceRequest(
          requestId = UUID.randomUUID().toString,
          runId = request.failurePacket.runId,
          attemptNumber = Some(request.failurePacket.attemptNumber),
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
          timeoutMs = info.config.maxRuntimeMs,
          metadata = Map.empty,
        )

        val startMs = System.currentTimeMillis()
        Await.result(inferenceService.infer(inferReq), Duration.Inf) match {
          case Right(response) =>
            info.inputTokens += response.inputTokens
            info.outputTokens += response.outputTokens
            info.durationMs += (System.currentTimeMillis() - startMs)
            info.toolCallCount += 1

            RepairResponseParser.parseRepairResult(response.responseText)

          case Left(error) =>
            info.durationMs += (System.currentTimeMillis() - startMs)
            val msg = RepairResponseParser.inferenceErrorMessage(error)
            Left(RepairBackendError.TaskSubmissionFailed(s"Inference error: $msg"))
        }
    }
  }

  override def cancel(handle: RepairSessionHandle): Unit = {
    sessions.get(handle.sessionId).foreach(_.cancelled = true)
  }

  override def getUsage(handle: RepairSessionHandle): RepairUsageSummary = {
    sessions.get(handle.sessionId) match {
      case Some(info) => RepairUsageSummary(
        inputTokens = info.inputTokens,
        outputTokens = info.outputTokens,
        totalTokens = info.inputTokens + info.outputTokens,
        durationMs = info.durationMs,
        toolCallCount = info.toolCallCount,
        estimatedCostUsd = None,
      )
      case None => RepairUsageSummary(0, 0, 0, 0, 0, None)
    }
  }

  override def closeSession(handle: RepairSessionHandle): Unit = {
    sessions.remove(handle.sessionId)
  }

  // Convenience: wraps full session lifecycle into single call
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

    Await.result(inferenceService.infer(request), Duration.Inf) match {
      case Right(response) =>
        RepairResponseParser.parsePatchProposal(response.responseText, context.runId, context.attemptNumber, backendId) match {
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
        RepairResponse.Failed(s"Inference error: ${RepairResponseParser.inferenceErrorMessage(error)}")
    }
  }

}
