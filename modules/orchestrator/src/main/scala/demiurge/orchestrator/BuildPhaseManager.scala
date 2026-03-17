package demiurge.orchestrator

import java.sql.Connection
import java.time.Instant
import java.util.UUID

import demiurge.model._
import demiurge.persistence._
import demiurge.repair._
import demiurge.inference.InferenceService

// Phase B+E: BuildPhaseManager handles the PlanningFeature → GeneratingCode states
// in Build mode. Uses the RepairBackend for initial code generation (same interface,
// different prompt context via GenerationMode.InitialBuild).
object BuildPhaseManager {

  private val Component = "feature_planner"
  private val DefaultModel = "claude-sonnet-4-20250514"

  /**
   * Plan the feature implementation.
   * When an InferenceService is available, uses LLM for richer planning.
   * Falls back to deterministic heuristics when LLM is unavailable.
   */
  def planFeature(
    runId: String,
    taskText: String,
    inspection: RepoInspectionReport,
    graph: RequirementGraph,
    inferenceService: Option[InferenceService] = None,
    resolvedConfig: Option[ResolvedConfig] = None,
  )(implicit conn: Connection): FeaturePlan = {
    val plan = inferenceService.flatMap { svc =>
      tryLlmPlanning(runId, taskText, inspection, graph, svc, resolvedConfig)
    }.getOrElse {
      buildDeterministicPlan(runId, taskText, inspection, graph)
    }

    FeaturePlanRepo.insert(plan)
    plan
  }

  /** LLM-backed planning: asks the LLM to produce a structured feature plan. */
  private def tryLlmPlanning(
    runId: String,
    taskText: String,
    inspection: RepoInspectionReport,
    graph: RequirementGraph,
    inferenceService: InferenceService,
    resolvedConfig: Option[ResolvedConfig],
  ): Option[FeaturePlan] = {
    val requestId = s"fplan-$runId-${UUID.randomUUID().toString.take(8)}"
    val model = resolvedConfig.flatMap(_.inference.models.get("feature_planner")).getOrElse(DefaultModel)
    val provider = resolvedConfig.map(_.inference.defaultProvider).getOrElse(InferenceProvider.Mock)

    val request = InferenceRequest(
      requestId = requestId,
      runId = runId,
      attemptNumber = None,
      component = Component,
      provider = provider,
      model = model,
      systemPrompt = buildPlanningSystemPrompt(),
      userPrompt = buildPlanningUserPrompt(taskText, inspection, graph),
      responseFormat = Some("json"),
      jsonSchema = None,
      maxOutputTokens = 4096,
      temperature = 0.2,
      cacheable = true,
      timeoutMs = 60000,
      metadata = Map("task" -> taskText),
    )

    inferenceService.infer(request) match {
      case Right(response) => parsePlanResponse(runId, taskText, response)
      case Left(_) => None
    }
  }

  /** Parse the LLM response JSON into a FeaturePlan. */
  private[orchestrator] def parsePlanResponse(
    runId: String,
    taskText: String,
    response: InferenceResponse,
  ): Option[FeaturePlan] = {
    try {
      val json = response.parsedJson.getOrElse(response.responseText)

      val summaryPattern = """"summary"\s*:\s*"([^"]+)"""".r
      val complexityPattern = """"estimated_complexity"\s*:\s*"([^"]+)"""".r
      val migrationPattern = """"requires_migration"\s*:\s*(true|false)""".r

      val summary = summaryPattern.findFirstMatchIn(json).map(_.group(1)).getOrElse(s"Plan for: $taskText")
      val complexity = complexityPattern.findFirstMatchIn(json).map(_.group(1)).getOrElse("medium")
      val requiresMigration = migrationPattern.findFirstMatchIn(json).exists(_.group(1) == "true")

      val filesToCreate = extractPlannedFiles(json, "files_to_create")
      val filesToModify = extractPlannedModifications(json, "files_to_modify")

      val depPattern = """"new_dependencies"\s*:\s*\[([^\]]*)\]""".r
      val deps = depPattern.findFirstMatchIn(json).map { m =>
        """"([^"]+)"""".r.findAllMatchIn(m.group(1)).map(_.group(1)).toList
      }.getOrElse(Nil)

      Some(FeaturePlan(
        planId = s"fplan-$runId-${UUID.randomUUID().toString.take(8)}",
        runId = runId,
        taskText = taskText,
        summary = summary,
        filesToCreate = filesToCreate,
        filesToModify = filesToModify,
        filesToDelete = Nil,
        requiresNewDeps = deps,
        requiresMigration = requiresMigration,
        estimatedComplexity = complexity,
        createdAt = Instant.now(),
      ))
    } catch {
      case _: Exception => None
    }
  }

  /** Extract JSON object blocks from a named array in the JSON string. */
  private def extractArrayBlocks(json: String, arrayKey: String): List[String] = {
    val blockStart = json.indexOf(s""""$arrayKey"""")
    if (blockStart < 0) return Nil

    val arrayStart = json.indexOf('[', blockStart)
    val arrayEnd = json.indexOf(']', arrayStart)
    if (arrayStart < 0 || arrayEnd < 0) return Nil

    extractJsonBlocks(json.substring(arrayStart, arrayEnd + 1))
  }

  private val pathPattern = """"(?:relative_)?path"\s*:\s*"([^"]+)"""".r
  private val descPattern = """"description"\s*:\s*"([^"]+)"""".r

  private def extractPlannedFiles(json: String, arrayKey: String): List[PlannedFile] = {
    val catPattern = """"category"\s*:\s*"([^"]+)"""".r
    extractArrayBlocks(json, arrayKey).flatMap { block =>
      for {
        path <- pathPattern.findFirstMatchIn(block).map(_.group(1))
        desc <- descPattern.findFirstMatchIn(block).map(_.group(1))
      } yield PlannedFile(
        relativePath = path,
        description = desc,
        category = catPattern.findFirstMatchIn(block).map(_.group(1)).getOrElse("unknown"),
      )
    }
  }

  private def extractPlannedModifications(json: String, arrayKey: String): List[PlannedModification] = {
    val changePattern = """"change_type"\s*:\s*"([^"]+)"""".r
    extractArrayBlocks(json, arrayKey).flatMap { block =>
      for {
        path <- pathPattern.findFirstMatchIn(block).map(_.group(1))
        desc <- descPattern.findFirstMatchIn(block).map(_.group(1))
      } yield PlannedModification(
        relativePath = path,
        description = desc,
        changeType = changePattern.findFirstMatchIn(block).map(_.group(1)).getOrElse("modify"),
      )
    }
  }

  private def extractJsonBlocks(json: String): List[String] = {
    val blocks = scala.collection.mutable.ListBuffer[String]()
    var depth = 0
    var start = -1
    for (i <- json.indices) {
      json(i) match {
        case '{' =>
          if (depth == 0) start = i
          depth += 1
        case '}' =>
          depth -= 1
          if (depth == 0 && start >= 0) {
            blocks += json.substring(start, i + 1)
            start = -1
          }
        case _ =>
      }
    }
    blocks.toList
  }

  /** Deterministic fallback: infer plan from RequirementGraph categories. */
  private def buildDeterministicPlan(
    runId: String,
    taskText: String,
    inspection: RepoInspectionReport,
    graph: RequirementGraph,
  ): FeaturePlan = {
    FeaturePlan(
      planId = s"fplan-$runId-${UUID.randomUUID().toString.take(8)}",
      runId = runId,
      taskText = taskText,
      summary = s"Implementation plan for: $taskText",
      filesToCreate = graph.nodes.flatMap { node =>
        node.category match {
          case RequirementCategory.ApiContract =>
            List(PlannedFile(s"src/api/${node.requirementId}.ts", node.humanDescription, "route"))
          case RequirementCategory.UiFlow =>
            List(PlannedFile(s"src/pages/${node.requirementId}.tsx", node.humanDescription, "component"))
          case _ => Nil
        }
      },
      filesToModify = Nil,
      filesToDelete = Nil,
      requiresNewDeps = Nil,
      requiresMigration = graph.nodes.exists(_.category == RequirementCategory.PersistenceState),
      estimatedComplexity = if (graph.nodes.size <= 3) "small" else if (graph.nodes.size <= 6) "medium" else "large",
      createdAt = Instant.now(),
    )
  }

  private def buildPlanningSystemPrompt(): String =
    """You are a feature implementation planner for web applications.
      |Given a task description, repository analysis, and verification requirements,
      |produce a structured implementation plan.
      |
      |Output a JSON object with:
      |{
      |  "summary": "brief description of the plan",
      |  "files_to_create": [{"path": "src/...", "description": "...", "category": "component|route|migration|test|config"}],
      |  "files_to_modify": [{"path": "src/...", "description": "...", "change_type": "add_import|add_route|modify_function"}],
      |  "new_dependencies": ["package-name"],
      |  "requires_migration": true/false,
      |  "estimated_complexity": "small|medium|large"
      |}""".stripMargin

  private def buildPlanningUserPrompt(
    taskText: String,
    inspection: RepoInspectionReport,
    graph: RequirementGraph,
  ): String = {
    val sb = new StringBuilder
    sb.append(s"Task: $taskText\n\n")
    sb.append("Repository:\n")
    sb.append(s"  Languages: ${inspection.languages.map(_.value).mkString(", ")}\n")
    sb.append(s"  Frameworks: ${inspection.frameworks.map(_.value).mkString(", ")}\n\n")
    sb.append("Requirements to satisfy:\n")
    graph.nodes.foreach { node =>
      sb.append(s"  - [${node.category}] ${node.humanDescription}\n")
    }
    sb.toString()
  }

  /**
   * Generate initial code for the feature via the repair backend.
   * Uses GenerationMode.InitialBuild to select the appropriate prompt.
   * Returns the RepairExecutor outcome (patch applied or rejected).
   */
  def generateCode(
    ctx: RunContext,
    backend: RepairBackend,
    taskText: String,
    featurePlan: FeaturePlan,
    inspection: RepoInspectionReport,
    graph: RequirementGraph,
    runtimePlan: Option[RuntimePlan],
  ): RepairExecutor.RepairOutcome = {
    // Build a FailurePacketInput that describes what needs to be built
    val buildInput = FailurePacketBuilder.FailurePacketInput(
      runId = ctx.run.runId,
      attemptNumber = 0,
      taskText = taskText,
      verdicts = Nil,
      graph = graph,
      inspectionReport = Some(inspection),
      runtimePlan = runtimePlan,
      patchHistory = Nil,
      logs = None,
    )

    // Build repair context with GenerationMode.InitialBuild
    val repairContext = RepairContext(
      runId = ctx.run.runId,
      attemptNumber = 0,
      taskText = taskText,
      worktreePath = ctx.worktreePath,
      graph = graph,
      verdicts = Nil,
      inspectionReport = Some(inspection),
      runtimePlan = runtimePlan,
      patchHistory = Nil,
      generationMode = GenerationMode.InitialBuild,
      featureSpec = Some(taskText),
      featurePlan = Some(featurePlan),
    )

    RepairExecutor.executeRepair(backend, ctx.worktreePath, buildInput, repairContext)
  }
}
