package lastmile.verification

import lastmile.model._

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
          url = api.urlTemplate,
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
          ConsoleLogVerifierSpec(targetUrl = "", forbiddenPatterns = Nil, allowedPatterns = Nil, maxErrors = 0, captureLevel = "error")
        )
        LogContainsVerifier(
          id = spec.verifierId,
          requirementId = spec.requirementId,
          logPath = log.targetUrl,
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

      case _ =>
        // For unsupported types in Phase 4 (BrowserFlow, etc.), create a stub StateVerifier
        StateVerifier(
          id = spec.verifierId,
          requirementId = spec.requirementId,
          timeout = spec.timeout,
          maxRetries = spec.maxRetries,
        )
    }
  }
}
