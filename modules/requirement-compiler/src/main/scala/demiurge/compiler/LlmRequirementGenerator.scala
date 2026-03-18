package demiurge.compiler

import java.time.{Duration, Instant}
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.{Duration => SDuration}

import demiurge.model._
import demiurge.inference.InferenceService

// Phase A: LLM-backed requirement generation from task string + repo context.
// When no explicit requirements.yaml is provided, this generates a RequirementGraph
// from the task description and repo inspection report via LLM inference.
object LlmRequirementGenerator {

  private val Component = "requirement_generator"
  private val DefaultModel = "claude-sonnet-4-20250514"

  /**
   * Generate a RequirementGraph from a task string using LLM inference.
   * Falls back to a minimal environment-readiness graph if inference fails.
   */
  def generate(
    runId: String,
    taskText: String,
    inspection: RepoInspectionReport,
    resolvedConfig: ResolvedConfig,
    inferenceService: InferenceService,
  ): RequirementGraph = {
    val requestId = s"req-gen-$runId-${UUID.randomUUID().toString.take(8)}"

    val systemPrompt = buildSystemPrompt()
    val userPrompt = buildUserPrompt(taskText, inspection, resolvedConfig)

    val model = resolvedConfig.inference.models.getOrElse(
      "requirement_compiler", DefaultModel)

    val request = InferenceRequest(
      requestId = requestId,
      runId = runId,
      attemptNumber = None,
      component = Component,
      provider = resolvedConfig.inference.defaultProvider,
      model = model,
      systemPrompt = systemPrompt,
      userPrompt = userPrompt,
      responseFormat = Some("json"),
      jsonSchema = None,
      maxOutputTokens = 4096,
      temperature = 0.2,
      cacheable = true,
      timeoutMs = 60000,
      metadata = Map("task" -> taskText),
    )

    Await.result(inferenceService.infer(request), SDuration.Inf) match {
      case Right(response) =>
        parseRequirementGraph(runId, response, requestId)
          .getOrElse(buildFallbackGraph(runId, inspection, resolvedConfig, Some(requestId)))
      case Left(_) =>
        buildFallbackGraph(runId, inspection, resolvedConfig, None)
    }
  }

  /**
   * Build a minimal fallback graph from environment readiness probes.
   * Used when LLM inference is unavailable or fails.
   */
  def buildFallbackGraph(
    runId: String,
    inspection: RepoInspectionReport,
    resolvedConfig: ResolvedConfig,
    inferenceRequestId: Option[String],
  ): RequirementGraph = {
    val nodes = resolvedConfig.services.filter(_.required).flatMap { svc =>
      svc.readiness.map { r =>
        val verifierType = r.probeType.toLowerCase match {
          case "http" => VerifierType.HttpApiContract
          case "tcp" => VerifierType.EnvironmentReadiness
          case _ => VerifierType.EnvironmentReadiness
        }

        val category = r.probeType.toLowerCase match {
          case "http" => RequirementCategory.ApiContract
          case _ => RequirementCategory.EnvironmentReadiness
        }

        val apiSpec = if (r.probeType.toLowerCase == "http") {
          Some(ApiContractVerifierSpec(
            method = "GET",
            path = r.target,
            requestBody = None,
            expectedStatus = 200,
          ))
        } else None

        val envSpec = if (r.probeType.toLowerCase != "http") {
          Some(EnvReadinessVerifierSpec(
            serviceId = svc.serviceId,
            probeOverride = None,
            requiredLogPatterns = Nil,
          ))
        } else None

        val verifierSpec = VerifierSpec(
          verifierId = s"v-${svc.serviceId}-readiness",
          verifierType = verifierType,
          displayName = s"${svc.serviceId} readiness check",
          requirementId = s"${svc.serviceId}-readiness",
          executionLayer = 0,
          parallelSafe = true,
          timeout = Duration.ofMillis(r.timeoutMs.toLong),
          maxRetries = 2,
          retryDelayMs = 1000,
          browserFlowSpec = None,
          apiContractSpec = apiSpec,
          stateAssertionSpec = None,
          envReadinessSpec = envSpec,
          consoleLogSpec = None,
          networkSpec = None,
          queueJobSpec = None,
          persistenceSpec = None,
          regressionSpec = None,
        )

        RequirementNode(
          requirementId = s"${svc.serviceId}-readiness",
          humanDescription = s"${svc.serviceId} is healthy and responding",
          machineDescription = s"${r.probeType} probe to ${r.target} returns success",
          priority = RequirementPriority.Required,
          category = category,
          dependencies = Set.empty,
          verifiers = List(verifierSpec),
          evidenceRequired = Nil,
          destructiveRiskLevel = 0,
          inferredFrom = List("config-resolver-fallback"),
          confidence = 0.9,
          stopOnFailure = true,
        )
      }
    }

    RequirementGraph(
      graphId = s"graph-$runId",
      runId = runId,
      nodes = nodes,
      edges = Nil,
      generatedAt = Instant.now(),
      inferenceRequestId = inferenceRequestId,
      warnings = if (nodes.isEmpty) List(GraphWarning(
        code = "NO_REQUIREMENTS",
        message = "No requirements could be generated — no required services with readiness probes found",
        affectedNodeIds = Nil,
      )) else Nil,
    )
  }

  private def buildSystemPrompt(): String =
    """You are a verification requirement generator for web applications.
      |Given a task description and repository analysis, generate executable
      |verification requirements that define "done" for the task.
      |
      |Each requirement must have:
      |  - id: unique kebab-case identifier
      |  - type: one of "http", "tcp", "exec", "browser_flow", "state"
      |  - description: human-readable description
      |  - severity: "required", "important", or "nice_to_have"
      |  - For http: method, url, expected_status
      |  - For tcp: host_port
      |  - For browser_flow: entry_url, actions (list of {action_type, selector, value}), assertions
      |  - For state: query description
      |
      |Output a JSON object with a "requirements" array.
      |Focus on verifiable, concrete requirements. Prefer HTTP and browser_flow types.
      |Generate 3-8 requirements depending on task complexity.""".stripMargin

  private def buildUserPrompt(
    taskText: String,
    inspection: RepoInspectionReport,
    resolvedConfig: ResolvedConfig,
  ): String = {
    val sb = new StringBuilder
    sb.append(s"Task: $taskText\n\n")

    sb.append("Repository analysis:\n")
    sb.append(s"  Languages: ${inspection.languages.map(_.value).mkString(", ")}\n")
    sb.append(s"  Frameworks: ${inspection.frameworks.map(_.value).mkString(", ")}\n")

    sb.append("\nServices:\n")
    resolvedConfig.services.foreach { svc =>
      sb.append(s"  - ${svc.serviceId} (${svc.kind})")
      svc.readiness.foreach(r => sb.append(s" → ${r.target}"))
      sb.append("\n")
    }

    sb.append(s"\nApp URL: ${resolvedConfig.app.rootUrl}\n")
    resolvedConfig.app.apiUrl.foreach(u => sb.append(s"API URL: $u\n"))

    if (inspection.healthEndpointHints.nonEmpty) {
      sb.append(s"\nHealth endpoints: ${inspection.healthEndpointHints.map(_.value).mkString(", ")}\n")
    }

    sb.toString()
  }

  // Parse LLM response JSON into a RequirementGraph.
  // Expected format: {"requirements": [{id, type, description, severity, ...}, ...]}
  private[compiler] def parseRequirementGraph(
    runId: String,
    response: InferenceResponse,
    requestId: String,
  ): Option[RequirementGraph] = {
    try {
      val json = response.parsedJson.getOrElse(response.responseText)
      // Simple JSON parsing — extract requirements array
      // Uses basic string parsing to avoid adding a JSON dependency to this module.
      // The LLM response is structured, so this is sufficient.
      val nodes = parseRequirementsFromJson(json)
      if (nodes.isEmpty) return None

      Some(RequirementGraph(
        graphId = s"graph-$runId",
        runId = runId,
        nodes = nodes,
        edges = Nil,
        generatedAt = Instant.now(),
        inferenceRequestId = Some(requestId),
        warnings = Nil,
      ))
    } catch {
      case _: Exception => None
    }
  }

  // Minimal JSON parser for requirement arrays — extracts id, type, description, severity
  // and creates appropriate RequirementNodes. This avoids pulling in circe as a dependency.
  private[compiler] def parseRequirementsFromJson(json: String): List[RequirementNode] = {
    // Look for "requirements" array entries by finding id/type/description patterns
    val idPattern = """"id"\s*:\s*"([^"]+)"""".r
    val typePattern = """"type"\s*:\s*"([^"]+)"""".r
    val descPattern = """"description"\s*:\s*"([^"]+)"""".r
    val severityPattern = """"severity"\s*:\s*"([^"]+)"""".r
    val urlPattern = """"url"\s*:\s*"([^"]+)"""".r
    val expectedStatusPattern = """"expected_status"\s*:\s*(\d+)""".r
    val hostPortPattern = """"host_port"\s*:\s*"([^"]+)"""".r
    val entryUrlPattern = """"entry_url"\s*:\s*"([^"]+)"""".r

    // Split into individual requirement blocks (between { })
    // Simple approach: find all {...} blocks within the requirements array
    val blocks = extractJsonBlocks(json)

    blocks.flatMap { block =>
      for {
        id <- idPattern.findFirstMatchIn(block).map(_.group(1))
        reqType <- typePattern.findFirstMatchIn(block).map(_.group(1))
        desc <- descPattern.findFirstMatchIn(block).map(_.group(1))
      } yield {
        val severity = severityPattern.findFirstMatchIn(block).map(_.group(1)).getOrElse("required")
        val url = urlPattern.findFirstMatchIn(block).map(_.group(1))
        val expectedStatus = expectedStatusPattern.findFirstMatchIn(block).map(_.group(1).toInt).getOrElse(200)
        val hostPort = hostPortPattern.findFirstMatchIn(block).map(_.group(1))
        val entryUrl = entryUrlPattern.findFirstMatchIn(block).map(_.group(1))

        val priority = severity match {
          case "required" => RequirementPriority.Required
          case "important" => RequirementPriority.Important
          case "nice_to_have" => RequirementPriority.NiceToHave
          case _ => RequirementPriority.Required
        }

        val (verifierType, category) = reqType match {
          case "http" => (VerifierType.HttpApiContract, RequirementCategory.ApiContract)
          case "tcp" => (VerifierType.EnvironmentReadiness, RequirementCategory.EnvironmentReadiness)
          case "browser_flow" => (VerifierType.BrowserFlow, RequirementCategory.UiFlow)
          case "state" => (VerifierType.StateAssertion, RequirementCategory.PersistenceState)
          case "exec" => (VerifierType.StateAssertion, RequirementCategory.IntegrationInvariant)
          case _ => (VerifierType.StateAssertion, RequirementCategory.IntegrationInvariant)
        }

        val apiSpec = if (reqType == "http") {
          Some(ApiContractVerifierSpec(
            method = "GET",
            path = url.getOrElse("http://localhost:3000"),
            requestBody = None,
            expectedStatus = expectedStatus,
          ))
        } else None

        val envSpec = if (reqType == "tcp") {
          Some(EnvReadinessVerifierSpec(
            serviceId = id,
            probeOverride = None,
            requiredLogPatterns = Nil,
          ))
        } else None

        val browserSpec = if (reqType == "browser_flow") {
          Some(BrowserFlowVerifierSpec(
            entryUrl = entryUrl.getOrElse("http://localhost:3000"),
            selectorMapRef = None,
            entryConditions = Nil,
            actions = Nil,
            assertions = Nil,
            artifactPlan = Nil,
            cleanup = Nil,
          ))
        } else None

        val verifierSpec = VerifierSpec(
          verifierId = s"v-$id",
          verifierType = verifierType,
          displayName = desc,
          requirementId = id,
          executionLayer = 0,
          parallelSafe = true,
          timeout = Duration.ofMillis(30000),
          maxRetries = 1,
          retryDelayMs = 1000,
          browserFlowSpec = browserSpec,
          apiContractSpec = apiSpec,
          stateAssertionSpec = None,
          envReadinessSpec = envSpec,
          consoleLogSpec = None,
          networkSpec = None,
          queueJobSpec = None,
          persistenceSpec = None,
          regressionSpec = None,
        )

        RequirementNode(
          requirementId = id,
          humanDescription = desc,
          machineDescription = desc,
          priority = priority,
          category = category,
          dependencies = Set.empty,
          verifiers = List(verifierSpec),
          evidenceRequired = Nil,
          destructiveRiskLevel = 0,
          inferredFrom = List("llm-requirement-generator"),
          confidence = 0.7,
          stopOnFailure = priority == RequirementPriority.Required,
        )
      }
    }
  }

  // Extract JSON object blocks from within a requirements array
  private def extractJsonBlocks(json: String): List[String] = {
    val blocks = scala.collection.mutable.ListBuffer[String]()
    var depth = 0
    var start = -1
    var inRequirements = false

    for (i <- json.indices) {
      json(i) match {
        case '[' if !inRequirements && json.substring(math.max(0, i - 30), i).contains("requirements") =>
          inRequirements = true
        case '{' if inRequirements =>
          if (depth == 0) start = i
          depth += 1
        case '}' if inRequirements =>
          depth -= 1
          if (depth == 0 && start >= 0) {
            blocks += json.substring(start, i + 1)
            start = -1
          }
        case ']' if inRequirements && depth == 0 =>
          inRequirements = false
        case _ =>
      }
    }

    blocks.toList
  }
}
