package demiurge.agent

import java.nio.file.Path

import io.circe._
import io.circe.syntax._

import java.time.Instant
import demiurge.model.{Observation, TasteSensitivity}
import demiurge.verification.{AgentBrowserVerifier, BrowserVerifierResult, VerifierOutcome, VerificationEngine}
import demiurge.worker.WorkerProcessManager

// Design: Agentic Browser UI Verification §15.1
// Implements AgentBrowserExecutor by delegating to the TypeScript worker's agent/execute method
// with mode="verification" and Playwright MCP only (no Demiurge MCP tools).
class AgentBrowserExecutorImpl(
  workerManager: WorkerProcessManager,
  repoRoot:      Path,
  agentConfig:   AgentConfig,
) extends VerificationEngine.AgentBrowserExecutor {

  import AgentBrowserExecutorImpl._

  override def execute(verifier: AgentBrowserVerifier): BrowserVerifierResult = {
    val beforeScreenshotRefs = verifier.beforeScreenshots.zipWithIndex.map { case (path, i) =>
      ScreenshotRef(
        ref = path,
        description = s"Before-implementation screenshot ${i + 1}",
        phase = "before",
      )
    }

    val systemPrompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = verifier.featureDescription,
      entryUrl = verifier.entryUrl,
      viewports = verifier.viewports,
      beforeScreenshots = beforeScreenshotRefs,
      tasteSensitivity = verifier.tasteSensitivity,
    )
    val userPrompt = BrowserVerificationPromptBuilder.buildUserPrompt(verifier.beforeScreenshots)

    // Design §7.1: Build JSON-RPC params with mode="verification"
    val params = Json.obj(
      "runId"              -> verifier.requirementId.asJson,
      "mode"               -> "verification".asJson,
      "systemPrompt"       -> systemPrompt.asJson,
      "userPrompt"         -> userPrompt.asJson,
      "worktreePath"       -> repoRoot.toAbsolutePath.toString.asJson,
      "repoRoot"           -> repoRoot.toAbsolutePath.toString.asJson,
      "serviceIds"         -> Json.arr(),
      "beforeScreenshots"  -> verifier.beforeScreenshots.asJson,
      "agentConfig"        -> Json.obj(
        "model"              -> agentConfig.model.asJson,
        "maxTurns"           -> Some(verifier.maxTurns).asJson,
        "maxBudgetUsd"       -> Some(verifier.maxBudgetUsd).asJson,
        "timeoutMs"          -> verifier.timeout.toMillis.asJson,
        "enableMcpTools"     -> false.asJson,       // no Demiurge MCP for verification
        "enableBrowserTools" -> true.asJson,
        "headedBrowser"      -> agentConfig.headedBrowser.asJson,
        "pathToClaudeCodeExecutable" -> agentConfig.pathToClaudeCodeExecutable.asJson,
      ),
    )

    val rpcTimeoutMs = verifier.timeout.toMillis + 30000

    if (!workerManager.isAlive || !workerManager.isInitialized) {
      return BrowserVerifierResult(
        VerifierOutcome.Error("Worker not available for browser verification"),
        Nil, Nil,
      )
    }

    val rpcResult = try {
      workerManager.sendRawRequest("agent/execute", params, rpcTimeoutMs)
    } catch {
      case e: Exception =>
        return BrowserVerifierResult(
          VerifierOutcome.Error(s"agent/execute RPC failed: ${e.getMessage}"),
          Nil, Nil,
        )
    }

    rpcResult match {
      case Left(err) =>
        BrowserVerifierResult(VerifierOutcome.Error(s"agent/execute failed: $err"), Nil, Nil)
      case Right(json) =>
        parseVerificationResult(json, verifier)
    }
  }

  /**
   * Parse the worker's response, extract the verification verdict,
   * and map it to a BrowserVerifierResult with VerifierOutcome.
   */
  private[agent] def parseVerificationResult(json: Json, verifier: AgentBrowserVerifier): BrowserVerifierResult = {
    val c = json.hcursor

    val success    = c.downField("success").as[Boolean].getOrElse(false)
    val resultText = c.downField("resultText").as[String].getOrElse("")
    val isInterrupted = c.downField("isInterrupted").as[Boolean].getOrElse(false)

    if (isInterrupted) {
      return BrowserVerifierResult(
        VerifierOutcome.TimedOut,
        Nil,
        Nil,
      )
    }

    if (!success) {
      return BrowserVerifierResult(
        VerifierOutcome.Failed(s"Verification agent failed: $resultText"),
        Nil,
        Nil,
      )
    }

    // Try to extract the verificationVerdict from the worker response JSON
    val verdictOpt = parseVerdictJson(c.downField("verificationVerdict"))
      .orElse(parseVerdictFromText(resultText))

    verdictOpt match {
      case Some(verdict) =>
        mapVerdictToResult(verdict, verifier)
      case None =>
        // Agent succeeded but verdict not parseable — treat as passed with observation
        BrowserVerifierResult(
          VerifierOutcome.Passed,
          List(Observation("browser-verification", s"Agent completed but verdict not parseable. Result: ${resultText.take(500)}", None, None, None, Instant.now())),
          Nil,
        )
    }
  }

  /** Map a parsed BrowserVerificationVerdict to a BrowserVerifierResult with taste sensitivity logic. */
  private def mapVerdictToResult(
    verdict: BrowserVerificationVerdict,
    verifier: AgentBrowserVerifier,
  ): BrowserVerifierResult = {
    val observations = verdict.observations.map { obs =>
      Observation(obs.aspect, s"[${obs.status}] ${obs.detail}", None, None, None, Instant.now())
    }
    val artifactRefs = verdict.screenshots.map(_.ref)

    val outcome = verdict.verdict match {
      case BrowserVerdictStatus.Pass =>
        VerifierOutcome.Passed

      case BrowserVerdictStatus.Fail =>
        VerifierOutcome.Failed(verdict.summary)

      case BrowserVerdictStatus.TasteIssue =>
        if (verifier.tasteTriggersRepair) {
          val triggeredIssues = AgentBrowserExecutorImpl.filterByTasteSensitivity(verdict.tasteIssues, verifier.tasteSensitivity)
          if (triggeredIssues.nonEmpty)
            VerifierOutcome.Failed(s"Taste issues: ${triggeredIssues.map(_.issue).mkString("; ")}")
          else
            VerifierOutcome.Passed  // issues below sensitivity threshold
        } else {
          VerifierOutcome.Passed  // taste issues don't trigger repair
        }
    }

    BrowserVerifierResult(outcome, observations, artifactRefs)
  }

}

object AgentBrowserExecutorImpl {

  /** Design §10.3: Filter taste issues by sensitivity level. */
  def filterByTasteSensitivity(
    issues: List[TasteIssue],
    sensitivity: TasteSensitivity,
  ): List[TasteIssue] = {
    sensitivity match {
      case TasteSensitivity.Strict  => issues // all issues
      case TasteSensitivity.Normal  => issues.filter(i => i.severity == "error" || i.severity == "warning")
      case TasteSensitivity.Lenient => issues.filter(_.severity == "error")
      case TasteSensitivity.Off     => Nil
    }
  }

  /**
   * Parse a BrowserVerificationVerdict from a circe HCursor pointing at a verdict JSON object.
   * Shared between structured-field parsing and raw-text fallback.
   */
  private[agent] def parseVerdictJson(cursor: io.circe.ACursor): Option[BrowserVerificationVerdict] = {
    for {
      verdictStr    <- cursor.downField("verdict").as[String].toOption
      verdictStatus <- BrowserVerdictStatus.fromString(verdictStr)
    } yield {
      val observations = cursor.downField("observations").as[List[Json]].getOrElse(Nil).map { obs =>
        BrowserObservation(
          aspect        = obs.hcursor.downField("aspect").as[String].getOrElse(""),
          status        = obs.hcursor.downField("status").as[String].getOrElse(""),
          detail        = obs.hcursor.downField("detail").as[String].getOrElse(""),
          screenshotRef = obs.hcursor.downField("screenshotRef").as[String].toOption,
        )
      }
      val tasteIssues = cursor.downField("tasteIssues").as[List[Json]].getOrElse(Nil).map { ti =>
        TasteIssue(
          severity      = ti.hcursor.downField("severity").as[String].getOrElse("warning"),
          issue         = ti.hcursor.downField("issue").as[String].getOrElse(""),
          element       = ti.hcursor.downField("element").as[String].toOption,
          screenshotRef = ti.hcursor.downField("screenshotRef").as[String].toOption,
        )
      }
      BrowserVerificationVerdict(
        verdict          = verdictStatus,
        confidence       = cursor.downField("confidence").as[Double].getOrElse(0.5),
        featureSatisfied = cursor.downField("featureSatisfied").as[Boolean].getOrElse(false),
        observations     = observations,
        tasteIssues      = tasteIssues,
        screenshots      = Nil,
        summary          = cursor.downField("summary").as[String].getOrElse(""),
      )
    }
  }

  /** Attempt to parse a BrowserVerificationVerdict from raw agent result text. */
  private[agent] def parseVerdictFromText(text: String): Option[BrowserVerificationVerdict] = {
    if (text == null || text.isEmpty) return None

    // Try fenced JSON block first, then balanced-brace extraction as fallback
    val jsonStr = {
      val fencedMatch = """(?s)```json\s*\n(.*?)\n\s*```""".r.findFirstMatchIn(text)
      fencedMatch.map(_.group(1)).getOrElse {
        extractBalancedJsonContaining(text, "\"verdict\"")
      }
    }

    if (jsonStr.isEmpty) return None

    io.circe.parser.parse(jsonStr).toOption.flatMap { json =>
      parseVerdictJson(json.hcursor)
    }
  }

  /**
   * Extract a balanced JSON object from text that contains the given keyword.
   * Uses brace counting (respecting string literals) to handle nested objects.
   */
  private def extractBalancedJsonContaining(text: String, keyword: String): String = {
    val keyIndex = text.indexOf(keyword)
    if (keyIndex < 0) return ""

    // Find the last '{' before the keyword
    var startIndex = -1
    var i = keyIndex
    while (i >= 0 && startIndex < 0) {
      if (text.charAt(i) == '{') startIndex = i
      i -= 1
    }
    if (startIndex < 0) return ""

    // Count balanced braces, respecting string literals
    var depth = 0
    var inString = false
    var escape = false
    i = startIndex
    while (i < text.length) {
      val ch = text.charAt(i)
      if (escape) { escape = false }
      else if (ch == '\\' && inString) { escape = true }
      else if (ch == '"') { inString = !inString }
      else if (!inString) {
        if (ch == '{') depth += 1
        else if (ch == '}') {
          depth -= 1
          if (depth == 0) return text.substring(startIndex, i + 1)
        }
      }
      i += 1
    }
    ""
  }
}
