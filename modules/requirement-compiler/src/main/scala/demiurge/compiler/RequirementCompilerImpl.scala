package demiurge.compiler

import java.time.Instant
import java.time.Duration

import demiurge.model._
import demiurge.requirements.{RequirementsFile, RequirementEntry}
import demiurge.selectors.{SelectorsFile, SelectorEntry}

// Phase 4: Real RequirementCompiler implementation.
// Compiles parsed requirements.yaml + selectors.yaml into a RequirementGraph.
// Deterministic — no inference, no LLM.
class RequirementCompilerImpl(
  requirementsFile: RequirementsFile,
  selectorsFile: SelectorsFile,
) extends RequirementCompiler {

  private val selectorMap: Map[String, SelectorEntry] =
    selectorsFile.selectors.map(s => s.id -> s).toMap

  override def compile(
    runId: String,
    inspection: RepoInspectionReport,
    taskText: String,
  ): RequirementGraph = {
    val nodes = requirementsFile.requirements.map(entryToNode)
    RequirementGraph(
      graphId = s"graph-$runId",
      runId = runId,
      nodes = nodes,
      edges = Nil,
      generatedAt = Instant.now(),
      inferenceRequestId = None,
      warnings = Nil,
    )
  }

  private def entryToNode(entry: RequirementEntry): RequirementNode = {
    val priority = entry.severity match {
      case Some("required")     => RequirementPriority.Required
      case Some("important")    => RequirementPriority.Important
      case Some("nice_to_have") => RequirementPriority.NiceToHave
      case _                    => RequirementPriority.Required
    }

    val category = entry.`type` match {
      case "http"            => RequirementCategory.ApiContract
      case "tcp"             => RequirementCategory.EnvironmentReadiness
      case "process"         => RequirementCategory.IntegrationInvariant
      case "state"           => RequirementCategory.PersistenceState
      case "log"             => RequirementCategory.IntegrationInvariant
      case "browser"         => RequirementCategory.UiFlow
      case "env_readiness"   => RequirementCategory.EnvironmentReadiness
      case _                 => RequirementCategory.IntegrationInvariant
    }

    val verifierType = entry.`type` match {
      case "http"            => VerifierType.HttpApiContract
      case "tcp"             => VerifierType.EnvironmentReadiness
      case "process"         => VerifierType.StateAssertion
      case "state"           => VerifierType.StateAssertion
      case "log"             => VerifierType.ConsoleLogSanity
      case "browser"         => VerifierType.BrowserFlow
      case "env_readiness"   => VerifierType.EnvironmentReadiness
      case _                 => VerifierType.StateAssertion
    }

    val timeoutDuration = Duration.ofMillis(entry.timeoutMs.getOrElse(30000L))
    val maxRetries = entry.retry.getOrElse(0)

    val apiSpec = if (entry.`type` == "http") {
      Some(ApiContractVerifierSpec(
        method = "GET",
        urlTemplate = entry.expected.getOrElse("http://localhost:3000"),
        headers = Map.empty,
        bodyTemplate = None,
        expectedStatus = 200,
        responseAssertions = Nil,
        artifactPlan = Nil,
      ))
    } else None

    val envSpec = if (entry.`type` == "tcp" || entry.`type` == "env_readiness") {
      Some(EnvReadinessVerifierSpec(
        serviceId = entry.id,
        probeOverride = None,
        requiredLogPatterns = Nil,
      ))
    } else None

    val consoleLogSpec = if (entry.`type` == "log") {
      Some(ConsoleLogVerifierSpec(
        targetUrl = entry.expected.getOrElse(""),
        forbiddenPatterns = entry.expected.map(List(_)).getOrElse(Nil),
        allowedPatterns = Nil,
        maxErrors = 0,
        captureLevel = "error",
      ))
    } else None

    val verifierSpec = VerifierSpec(
      verifierId = s"v-${entry.id}",
      verifierType = verifierType,
      displayName = entry.description,
      requirementId = entry.id,
      executionLayer = 0,
      parallelSafe = true,
      timeout = timeoutDuration,
      maxRetries = maxRetries,
      retryDelayMs = 1000,
      browserFlowSpec = None,
      apiContractSpec = apiSpec,
      stateAssertionSpec = None,
      envReadinessSpec = envSpec,
      consoleLogSpec = consoleLogSpec,
      networkSpec = None,
      queueJobSpec = None,
      persistenceSpec = None,
      regressionSpec = None,
    )

    RequirementNode(
      requirementId = entry.id,
      humanDescription = entry.description,
      machineDescription = entry.description,
      priority = priority,
      category = category,
      dependencies = Set.empty,
      verifiers = List(verifierSpec),
      evidenceRequired = Nil,
      destructiveRiskLevel = 0,
      inferredFrom = Nil,
      confidence = 1.0,
      stopOnFailure = priority == RequirementPriority.Required,
    )
  }
}
