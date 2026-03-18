package demiurge.repair.claude

import java.time.Instant
import java.util.UUID

import demiurge.model._
import demiurge.repair._

// Spec §10.1: ClaudeRepairBackend — implements RepairBackend using Claude API.
// Implements full session-based interface per spec §10.1.
// Uses circe for structured JSON parsing (spec §10.11).
class ClaudeRepairBackend(model: Option[String] = None) extends RepairBackend {

  override val backendId: String = "claude"

  // Spec §10.1: Session state tracked in-memory per handle
  private val sessions = scala.collection.concurrent.TrieMap.empty[String, SessionInfo]

  private case class SessionInfo(
    config: RepairSessionConfig,
    handle: RepairSessionHandle,
    startedAt: Instant,
    var durationMs: Long = 0,
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
        val systemPrompt = ClaudePromptBuilder.buildSystemPrompt(request.generationMode)
        val userPrompt = ClaudePromptBuilder.buildRepairRequestPrompt(request)

        val startMs = System.currentTimeMillis()
        val result = ClaudeClient.sendMessage(systemPrompt, userPrompt, model) match {
          case Right(response) =>
            info.durationMs += (System.currentTimeMillis() - startMs)
            RepairResponseParser.parseRepairResult(response.content)
          case Left(error) =>
            info.durationMs += (System.currentTimeMillis() - startMs)
            Left(RepairBackendError.TaskSubmissionFailed(s"Claude API error: ${error.message} (HTTP ${error.statusCode})"))
        }
        result
    }
  }

  override def cancel(handle: RepairSessionHandle): Unit = {
    sessions.get(handle.sessionId).foreach(_.cancelled = true)
  }

  override def getUsage(handle: RepairSessionHandle): RepairUsageSummary = {
    sessions.get(handle.sessionId) match {
      case Some(info) => RepairUsageSummary(
        inputTokens = 0, outputTokens = 0, totalTokens = 0,
        durationMs = info.durationMs, toolCallCount = 1, estimatedCostUsd = None,
      )
      case None => RepairUsageSummary(0, 0, 0, 0, 0, None)
    }
  }

  override def closeSession(handle: RepairSessionHandle): Unit = {
    sessions.remove(handle.sessionId)
  }

  // Convenience: wraps full session lifecycle into single call
  override def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse = {
    val systemPrompt = ClaudePromptBuilder.buildSystemPrompt()
    val userPrompt = ClaudePromptBuilder.buildUserPrompt(packet, context)

    ClaudeClient.sendMessage(systemPrompt, userPrompt, model) match {
      case Right(response) =>
        RepairResponseParser.parsePatchProposal(response.content, context.runId, context.attemptNumber, backendId) match {
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

}
