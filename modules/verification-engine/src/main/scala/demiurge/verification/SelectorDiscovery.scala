package demiurge.verification

import java.util.UUID

import demiurge.model._
import demiurge.inference.InferenceService

// Phase D: Runtime selector discovery — eliminates the need for selectors.yaml.
// When a BrowserFlowVerifier has actions/assertions with missing selectors,
// this component uses page snapshots + LLM to discover appropriate selectors.
object SelectorDiscovery {

  private val Component = "verifier_generator"
  private val DefaultModel = "claude-sonnet-4-20250514"

  /**
   * Discover selectors for a browser flow spec that has missing selectors.
   * Uses the LLM to analyze a page snapshot (DOM/accessibility tree) and
   * produce CSS/test-id selectors for each action and assertion.
   *
   * @param spec the browser flow spec with potentially missing selectors
   * @param pageSnapshot DOM or accessibility tree snapshot of the target page
   * @param resolvedConfig config with inference settings
   * @param inferenceService the inference service to call
   * @return updated spec with discovered selectors filled in
   */
  def discoverSelectors(
    spec: BrowserFlowVerifierSpec,
    pageSnapshot: String,
    runId: String,
    resolvedConfig: ResolvedConfig,
    inferenceService: InferenceService,
  ): BrowserFlowVerifierSpec = {
    val missingActionSelectors = spec.actions.exists(_.selector.isEmpty)
    val missingAssertionSelectors = spec.assertions.exists(_.selector.isEmpty)

    if (!missingActionSelectors && !missingAssertionSelectors) return spec

    val requestId = s"sel-disc-$runId-${UUID.randomUUID().toString.take(8)}"
    val model = resolvedConfig.inference.models.getOrElse("verifier_generator", DefaultModel)

    val request = InferenceRequest(
      requestId = requestId,
      runId = runId,
      attemptNumber = None,
      component = Component,
      provider = resolvedConfig.inference.defaultProvider,
      model = model,
      systemPrompt = buildSystemPrompt(),
      userPrompt = buildUserPrompt(spec, pageSnapshot),
      responseFormat = Some("json"),
      jsonSchema = None,
      maxOutputTokens = 2048,
      temperature = 0.1,
      cacheable = true,
      timeoutMs = 30000,
      metadata = Map("entry_url" -> spec.entryUrl),
    )

    inferenceService.infer(request) match {
      case Right(response) =>
        parseSelectorResponse(spec, response).getOrElse(spec)
      case Left(_) =>
        spec
    }
  }

  /**
   * Check if a browser flow spec needs selector discovery.
   */
  def needsDiscovery(spec: BrowserFlowVerifierSpec): Boolean = {
    spec.actions.exists(_.selector.isEmpty) ||
      spec.assertions.exists(_.selector.isEmpty)
  }

  private def buildSystemPrompt(): String =
    """You are a browser automation selector generator.
      |Given a page snapshot (DOM or accessibility tree) and a list of actions/assertions
      |that need selectors, produce the best CSS selector or test-id for each.
      |
      |Prefer selectors in this order:
      |1. data-testid attributes (strategy: "test-id")
      |2. aria-label or role-based selectors (strategy: "css")
      |3. Semantic CSS selectors using element type + name/id attributes (strategy: "css")
      |4. Class-based selectors as last resort (strategy: "css")
      |
      |Output a JSON object with:
      |{
      |  "selectors": [
      |    {"index": 0, "type": "action", "strategy": "test-id", "value": "[data-testid='submit-btn']"},
      |    {"index": 1, "type": "assertion", "strategy": "css", "value": "input[name='email']"}
      |  ]
      |}
      |
      |Each entry's index corresponds to the position in the actions or assertions list.""".stripMargin

  private def buildUserPrompt(spec: BrowserFlowVerifierSpec, pageSnapshot: String): String = {
    val sb = new StringBuilder
    sb.append(s"Page URL: ${spec.entryUrl}\n\n")

    sb.append("Actions needing selectors:\n")
    spec.actions.zipWithIndex.foreach { case (action, idx) =>
      if (action.selector.isEmpty) {
        sb.append(s"  [$idx] ${action.actionType}: ${action.description}")
        action.value.foreach(v => sb.append(s" (value: $v)"))
        sb.append("\n")
      }
    }

    sb.append("\nAssertions needing selectors:\n")
    spec.assertions.zipWithIndex.foreach { case (assertion, idx) =>
      if (assertion.selector.isEmpty) {
        sb.append(s"  [$idx] ${assertion.assertionType}: ${assertion.description}")
        assertion.expected.foreach(e => sb.append(s" (expected: $e)"))
        sb.append("\n")
      }
    }

    sb.append(s"\nPage snapshot (truncated to 8000 chars):\n${pageSnapshot.take(8000)}\n")
    sb.toString()
  }

  // Parse LLM response and fill in missing selectors
  private[verification] def parseSelectorResponse(
    spec: BrowserFlowVerifierSpec,
    response: InferenceResponse,
  ): Option[BrowserFlowVerifierSpec] = {
    try {
      val json = response.parsedJson.getOrElse(response.responseText)

      // Extract selector entries
      val indexPattern = """"index"\s*:\s*(\d+)""".r
      val typePattern = """"type"\s*:\s*"([^"]+)"""".r
      val strategyPattern = """"strategy"\s*:\s*"([^"]+)"""".r
      val valuePattern = """"value"\s*:\s*"([^"]+)"""".r

      // Parse individual selector blocks
      val blocks = extractSelectorBlocks(json)
      val actionSelectors = scala.collection.mutable.Map[Int, SelectorRef]()
      val assertionSelectors = scala.collection.mutable.Map[Int, SelectorRef]()

      blocks.foreach { block =>
        for {
          idx <- indexPattern.findFirstMatchIn(block).map(_.group(1).toInt)
          entryType <- typePattern.findFirstMatchIn(block).map(_.group(1))
          strategy <- strategyPattern.findFirstMatchIn(block).map(_.group(1))
          value <- valuePattern.findFirstMatchIn(block).map(_.group(1))
        } {
          val ref = SelectorRef(strategy = strategy, value = value, roleName = None)
          if (entryType == "action") actionSelectors(idx) = ref
          else if (entryType == "assertion") assertionSelectors(idx) = ref
        }
      }

      val updatedActions = spec.actions.zipWithIndex.map { case (action, idx) =>
        if (action.selector.isEmpty) {
          actionSelectors.get(idx).map(sel => action.copy(selector = Some(sel))).getOrElse(action)
        } else action
      }

      val updatedAssertions = spec.assertions.zipWithIndex.map { case (assertion, idx) =>
        if (assertion.selector.isEmpty) {
          assertionSelectors.get(idx).map(sel => assertion.copy(selector = Some(sel))).getOrElse(assertion)
        } else assertion
      }

      Some(spec.copy(actions = updatedActions, assertions = updatedAssertions))
    } catch {
      case _: Exception => None
    }
  }

  private def extractSelectorBlocks(json: String): List[String] = {
    val blocks = scala.collection.mutable.ListBuffer[String]()
    var depth = 0
    var start = -1
    var inSelectors = false

    for (i <- json.indices) {
      json(i) match {
        case '[' if !inSelectors && json.substring(math.max(0, i - 20), i).contains("selectors") =>
          inSelectors = true
        case '{' if inSelectors =>
          if (depth == 0) start = i
          depth += 1
        case '}' if inSelectors =>
          depth -= 1
          if (depth == 0 && start >= 0) {
            blocks += json.substring(start, i + 1)
            start = -1
          }
        case ']' if inSelectors && depth == 0 =>
          inSelectors = false
        case _ =>
      }
    }
    blocks.toList
  }
}
