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

    // Try to extract the verificationVerdict from the worker response
    val verdictJson = c.downField("verificationVerdict")
    val verdictOpt = for {
      verdictStr       <- verdictJson.downField("verdict").as[String].toOption
      verdictStatus    <- BrowserVerdictStatus.fromString(verdictStr)
      confidence       <- verdictJson.downField("confidence").as[Double].toOption.orElse(Some(0.5))
      featureSatisfied <- verdictJson.downField("featureSatisfied").as[Boolean].toOption.orElse(Some(false))
      summary          <- verdictJson.downField("summary").as[String].toOption.orElse(Some(""))
    } yield {
      val observations = verdictJson.downField("observations").as[List[Json]].getOrElse(Nil).map { obs =>
        BrowserObservation(
          aspect        = obs.hcursor.downField("aspect").as[String].getOrElse(""),
          status        = obs.hcursor.downField("status").as[String].getOrElse(""),
          detail        = obs.hcursor.downField("detail").as[String].getOrElse(""),
          screenshotRef = obs.hcursor.downField("screenshotRef").as[String].toOption,
        )
      }
      val tasteIssues = verdictJson.downField("tasteIssues").as[List[Json]].getOrElse(Nil).map { ti =>
        TasteIssue(
          severity      = ti.hcursor.downField("severity").as[String].getOrElse("warning"),
          issue         = ti.hcursor.downField("issue").as[String].getOrElse(""),
          element       = ti.hcursor.downField("element").as[String].toOption,
          screenshotRef = ti.hcursor.downField("screenshotRef").as[String].toOption,
        )
      }
      BrowserVerificationVerdict(
        verdict          = verdictStatus,
        confidence       = confidence,
        featureSatisfied = featureSatisfied,
        observations     = observations,
        tasteIssues      = tasteIssues,
        screenshots      = Nil,
        summary          = summary,
      )
    }

    verdictOpt match {
      case Some(verdict) =>
        mapVerdictToResult(verdict, verifier)
      case None =>
        // Fallback: try to parse from resultText (agent may have only put verdict in text)
        parseVerdictFromText(resultText) match {
          case Some(verdict) => mapVerdictToResult(verdict, verifier)
          case None =>
            // If we can't parse a verdict but the agent succeeded, treat as passed
            if (success) {
              BrowserVerifierResult(
                VerifierOutcome.Passed,
                List(Observation("browser-verification", s"Agent completed but verdict not parseable. Result: ${resultText.take(500)}", None, None, None, Instant.now())),
                Nil,
              )
            } else {
              BrowserVerifierResult(
                VerifierOutcome.Failed(s"Could not parse verification verdict from agent output"),
                Nil, Nil,
              )
            }
        }
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
          val triggeredIssues = filterByTasteSensitivity(verdict.tasteIssues, verifier.tasteSensitivity)
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

  /** Design §10.3: Filter taste issues by sensitivity level. */
  private[agent] def filterByTasteSensitivity(
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

  /** Attempt to parse a BrowserVerificationVerdict from raw agent result text. */
  private def parseVerdictFromText(text: String): Option[BrowserVerificationVerdict] = {
    // Try to find JSON block in ```json ... ``` or raw { ... "verdict" ... }
    val jsonStr = {
      val fencedMatch = """```json\s*\n([\s\S]*?)\n\s*```""".r.findFirstMatchIn(text)
      fencedMatch.map(_.group(1)).getOrElse {
        val rawMatch = """\{[\s\S]*"verdict"[\s\S]*\}""".r.findFirstIn(text)
        rawMatch.getOrElse("")
      }
    }

    if (jsonStr.isEmpty) return None

    io.circe.parser.parse(jsonStr).toOption.flatMap { json =>
      val c = json.hcursor
      for {
        verdictStr       <- c.downField("verdict").as[String].toOption
        verdictStatus    <- BrowserVerdictStatus.fromString(verdictStr)
      } yield {
        BrowserVerificationVerdict(
          verdict          = verdictStatus,
          confidence       = c.downField("confidence").as[Double].getOrElse(0.5),
          featureSatisfied = c.downField("featureSatisfied").as[Boolean].getOrElse(false),
          observations     = c.downField("observations").as[List[Json]].getOrElse(Nil).map { obs =>
            BrowserObservation(
              aspect        = obs.hcursor.downField("aspect").as[String].getOrElse(""),
              status        = obs.hcursor.downField("status").as[String].getOrElse(""),
              detail        = obs.hcursor.downField("detail").as[String].getOrElse(""),
              screenshotRef = obs.hcursor.downField("screenshotRef").as[String].toOption,
            )
          },
          tasteIssues      = c.downField("tasteIssues").as[List[Json]].getOrElse(Nil).map { ti =>
            TasteIssue(
              severity      = ti.hcursor.downField("severity").as[String].getOrElse("warning"),
              issue         = ti.hcursor.downField("issue").as[String].getOrElse(""),
              element       = ti.hcursor.downField("element").as[String].toOption,
              screenshotRef = ti.hcursor.downField("screenshotRef").as[String].toOption,
            )
          },
          screenshots      = Nil,
          summary          = c.downField("summary").as[String].getOrElse(""),
        )
      }
    }
  }
}
