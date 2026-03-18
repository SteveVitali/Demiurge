package demiurge.compiler

import java.time.{Duration, Instant}

import demiurge.model._

// Utility for building readiness-based requirement graphs from resolved config.
// Used by `demiurge init` to generate minimal requirements.yaml scaffolds.
// Runtime requirement compilation uses RequirementCompilerImpl with explicit YAML only.
object LlmRequirementGenerator {

  /**
   * Build a minimal requirement graph from environment readiness probes in the config.
   * Used by `demiurge init` (non-smart path) to scaffold requirements.yaml.
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
}
