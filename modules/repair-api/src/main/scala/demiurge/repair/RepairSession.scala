package demiurge.repair

import java.time.Instant
import java.util.UUID

import demiurge.model._

// Spec §10.1–10.10: Repair session lifecycle management.
// Wraps RepairBackend with session semantics: pre/post commit SHA capture,
// transcript accumulation, usage tracking, and tool validation.
object RepairSession {

  case class SessionState(
    sessionId:          String,
    runId:              String,
    attemptNumber:      Int,
    startedAt:          Instant,
    preCommitSha:       Option[String],
    postCommitSha:      Option[String] = None,
    transcript:         List[TranscriptEntry] = Nil,
    tokensUsed:         Long = 0,
    closedAt:           Option[Instant] = None,
  )

  case class TranscriptEntry(
    timestamp:  Instant,
    role:       String, // "system", "user", "assistant", "tool_call", "tool_result"
    content:    String,
  )

  case class RepairWithSession(
    outcome:    RepairExecutor.RepairOutcome,
    session:    SessionState,
  )

  /** Execute a full repair within a managed session. */
  def executeWithSession(
    backend:      RepairBackend,
    worktreePath: java.nio.file.Path,
    input:        FailurePacketBuilder.FailurePacketInput,
    context:      RepairContext,
    policySnapshot: Option[PolicySnapshot] = None,
  ): RepairWithSession = {
    // Step 1: Open session — capture pre-apply commit SHA
    val preCommitSha = resolveCurrentCommitSha(worktreePath)
    var session = SessionState(
      sessionId = UUID.randomUUID().toString,
      runId = context.runId,
      attemptNumber = context.attemptNumber,
      startedAt = Instant.now(),
      preCommitSha = preCommitSha,
    )

    // Step 2: Record the system and user prompts in transcript
    session = addTranscript(session, "system", s"Repair session opened for run=${context.runId} attempt=${context.attemptNumber}")

    // Step 3: Build failure packet
    val packet = FailurePacketBuilder.build(input)
    session = addTranscript(session, "user", s"FailurePacket: ${packet.summary}")

    // Step 4: Validate filesystem policy for worktree path
    policySnapshot.foreach { ps =>
      val fsPolicy = ps.filesystemPolicy
      session = addTranscript(session, "system",
        s"Policy: allowedWritePaths=${fsPolicy.allowedWritePaths.mkString(",")}, forbiddenWritePaths=${fsPolicy.forbiddenWritePaths.mkString(",")}")
    }

    // Step 5: Call repair backend
    val startMs = System.currentTimeMillis()
    val response = try {
      backend.proposePatch(packet, context)
    } catch {
      case e: Exception =>
        session = addTranscript(session, "system", s"Backend error: ${e.getMessage}")
        RepairResponse.Failed(s"Backend exception: ${e.getMessage}")
    }
    val elapsedMs = System.currentTimeMillis() - startMs
    session = addTranscript(session, "assistant", s"Backend response in ${elapsedMs}ms: ${responseLabel(response)}")

    // Step 6: Process response
    val outcome = response match {
      case RepairResponse.Success(proposal) =>
        if (proposal.isEmpty) {
          session = addTranscript(session, "system", "Empty patch rejected")
          RepairExecutor.RepairRejected(packet, "Repair backend returned empty patch")
        } else {
          // Validate patch files against filesystem policy
          val violations = policySnapshot.map { ps =>
            val wt = worktreePath.toString
            val allPaths = proposal.edits.map(_.relativePath) ++ proposal.newFiles.map(_.relativePath) ++ proposal.deletions.map(_.relativePath)
            allPaths.flatMap { p =>
              demiurge.policy.PolicyEnforcer.validateFilesystemWrite(p, ps.filesystemPolicy, wt)
            }
          }.getOrElse(Nil)

          if (violations.nonEmpty) {
            val msgs = violations.map(_.message).mkString("; ")
            session = addTranscript(session, "system", s"Policy violations: $msgs")
            RepairExecutor.RepairRejected(packet, s"Patch violates filesystem policy: $msgs")
          } else {
            // Apply patch
            PatchApplier.apply(proposal, worktreePath) match {
              case PatchApplier.ApplySuccess(filesChanged) =>
                session = addTranscript(session, "system", s"Patch applied: ${filesChanged.size} files changed")
                RepairExecutor.RepairApplied(packet, proposal, filesChanged)
              case PatchApplier.ApplyFailure(reason) =>
                session = addTranscript(session, "system", s"Patch apply failed: $reason")
                RepairExecutor.RepairRejected(packet, s"Patch application failed: $reason")
            }
          }
        }

      case RepairResponse.Failed(reason) =>
        session = addTranscript(session, "system", s"Backend failed: $reason")
        RepairExecutor.RepairRejected(packet, s"Repair backend failed: $reason")

      case RepairResponse.InvalidPatch(reason) =>
        session = addTranscript(session, "system", s"Invalid patch: $reason")
        RepairExecutor.RepairRejected(packet, s"Invalid patch from repair backend: $reason")
    }

    // Step 7: Close session — capture post-apply commit SHA
    val postCommitSha = resolveCurrentCommitSha(worktreePath)
    session = session.copy(
      postCommitSha = postCommitSha,
      closedAt = Some(Instant.now()),
    )
    session = addTranscript(session, "system",
      s"Session closed. Pre=${preCommitSha.getOrElse("none")} Post=${postCommitSha.getOrElse("none")}")

    RepairWithSession(outcome, session)
  }

  /** Serialize session transcript to JSON string for artifact storage. */
  def serializeTranscript(session: SessionState): String = {
    val entries = session.transcript.map { entry =>
      s"""{"timestamp":"${entry.timestamp}","role":"${entry.role}","content":${escapeJson(entry.content)}}"""
    }.mkString("[", ",", "]")

    s"""{"sessionId":"${session.sessionId}","runId":"${session.runId}","attemptNumber":${session.attemptNumber},""" +
      s""""startedAt":"${session.startedAt}","closedAt":"${session.closedAt.getOrElse("")}",""" +
      s""""preCommitSha":"${session.preCommitSha.getOrElse("")}","postCommitSha":"${session.postCommitSha.getOrElse("")}",""" +
      s""""transcript":$entries}"""
  }

  private def addTranscript(session: SessionState, role: String, content: String): SessionState = {
    session.copy(transcript = session.transcript :+ TranscriptEntry(Instant.now(), role, content))
  }

  private def responseLabel(response: RepairResponse): String = response match {
    case RepairResponse.Success(p) => s"Success(${p.edits.size} edits, ${p.newFiles.size} new, ${p.deletions.size} del)"
    case RepairResponse.Failed(r)  => s"Failed($r)"
    case RepairResponse.InvalidPatch(r) => s"InvalidPatch($r)"
  }

  private def resolveCurrentCommitSha(worktreePath: java.nio.file.Path): Option[String] = {
    try {
      val process = new ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(worktreePath.toFile)
        .redirectErrorStream(true)
        .start()
      val output = scala.io.Source.fromInputStream(process.getInputStream).mkString.trim
      process.waitFor()
      if (process.exitValue() == 0 && output.nonEmpty) Some(output) else None
    } catch {
      case _: Exception => None
    }
  }

  private def escapeJson(s: String): String = {
    val escaped = s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s""""$escaped""""
  }
}
