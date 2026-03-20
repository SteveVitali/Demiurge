package demiurge.verification

import demiurge.model._

// Phase 4: Deterministic verifier generation from RequirementGraph
object VerifierGenerator {

  def generate(graph: RequirementGraph): List[Verifier] = {
    graph.nodes.flatMap(nodeToVerifiers)
  }

  private def nodeToVerifiers(node: RequirementNode): List[Verifier] = {
    node.verifiers.map(specToVerifier)
  }

  private def specToVerifier(spec: VerifierSpec): Verifier = {
    spec.verifierType match {
      case VerifierType.HttpApiContract =>
        val api = spec.apiContractSpec.getOrElse(
          throw new IllegalStateException(s"HttpApiContract verifier ${spec.verifierId} missing apiContractSpec")
        )
        HttpVerifier(
          id = spec.verifierId,
          requirementId = spec.requirementId,
          method = api.method,
          url = api.path,
          headers = api.headers,
          expectedStatus = api.expectedStatus,
          timeout = spec.timeout,
          maxRetries = spec.maxRetries,
        )

      case VerifierType.EnvironmentReadiness =>
        // Phase 4: EnvironmentReadiness falls back to StateVerifier stub
        // since we don't yet have runtime port resolution to create a real TcpVerifier.
        StateVerifier(
          id = spec.verifierId,
          requirementId = spec.requirementId,
          timeout = spec.timeout,
          maxRetries = spec.maxRetries,
        )

      case VerifierType.ConsoleLogSanity =>
        val log = spec.consoleLogSpec.getOrElse(
          ConsoleLogVerifierSpec(url = "")
        )
        LogContainsVerifier(
          id = spec.verifierId,
          requirementId = spec.requirementId,
          logPath = log.url,
          pattern = log.forbiddenPatterns.headOption.getOrElse(""),
          forbidden = true,
          timeout = spec.timeout,
          maxRetries = spec.maxRetries,
        )

      case VerifierType.StateAssertion =>
        StateVerifier(
          id = spec.verifierId,
          requirementId = spec.requirementId,
          timeout = spec.timeout,
          maxRetries = spec.maxRetries,
        )

      // Phase 6: BrowserFlow verifier — dispatches to worker
      case VerifierType.BrowserFlow =>
        val browser = spec.browserFlowSpec.getOrElse(
          throw new IllegalStateException(s"BrowserFlow verifier ${spec.verifierId} missing browserFlowSpec")
        )
        BrowserFlowVerifier(
          id = spec.verifierId,
          requirementId = spec.requirementId,
          entryUrl = browser.entryUrl,
          actions = browser.actions,
          assertions = browser.assertions,
          artifactPlan = browser.artifactPlan,
          storageStatePath = None, // Set by orchestrator at execution time via auth context
          timeout = spec.timeout,
          maxRetries = spec.maxRetries,
        )

      // Design: Agentic Browser UI Verification §15.1
      case VerifierType.AgentBrowser =>
        val browser = spec.agentBrowserSpec.getOrElse(
          throw new IllegalStateException(s"AgentBrowser verifier ${spec.verifierId} missing agentBrowserSpec")
        )
        AgentBrowserVerifier(
          id = spec.verifierId,
          requirementId = spec.requirementId,
          entryUrl = browser.entryUrl,
          featureDescription = browser.featureDescription,
          timeout = spec.timeout,
          maxRetries = spec.maxRetries,
          maxBudgetUsd = browser.maxBudgetUsd,
          viewports = browser.viewports,
          tasteSensitivity = browser.tasteSensitivity,
          tasteTriggersRepair = browser.tasteTriggersRepair,
        )

      case _ =>
        // For unsupported types, create a stub StateVerifier
        StateVerifier(
          id = spec.verifierId,
          requirementId = spec.requirementId,
          timeout = spec.timeout,
          maxRetries = spec.maxRetries,
        )
    }
  }
}
