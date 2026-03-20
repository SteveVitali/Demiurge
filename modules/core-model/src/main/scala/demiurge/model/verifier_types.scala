package demiurge.model

import java.time.Duration

// Spec §3.2: VerifierSpec
case class VerifierSpec(
  verifierId:         String,
  verifierType:       VerifierType,
  displayName:        String,
  requirementId:      String,
  executionLayer:     Int,
  parallelSafe:       Boolean,
  timeout:            Duration,
  maxRetries:         Int,
  retryDelayMs:       Int,
  browserFlowSpec:    Option[BrowserFlowVerifierSpec],
  apiContractSpec:    Option[ApiContractVerifierSpec],
  stateAssertionSpec: Option[StateAssertionVerifierSpec],
  envReadinessSpec:   Option[EnvReadinessVerifierSpec],
  consoleLogSpec:     Option[ConsoleLogVerifierSpec],
  networkSpec:        Option[NetworkExpectationVerifierSpec],
  queueJobSpec:       Option[QueueJobVerifierSpec],
  persistenceSpec:    Option[PersistenceReloadVerifierSpec],
  regressionSpec:     Option[RegressionVerifierSpec],
  agentBrowserSpec:   Option[AgentBrowserVerifierSpec] = None,
)

// Spec §3.2: BrowserFlowVerifierSpec
case class BrowserFlowVerifierSpec(
  entryUrl:           String,
  selectorMapRef:     Option[String],
  entryConditions:    List[Assertion],
  actions:            List[BrowserAction],
  assertions:         List[Assertion],
  artifactPlan:       List[ArtifactCapture],
  cleanup:            List[BrowserAction],
)

// Spec §3.2: BrowserAction
case class BrowserAction(
  actionType:         String,
  selector:           Option[SelectorRef],
  value:              Option[String],
  url:                Option[String],
  timeoutMs:          Option[Int],
  description:        String,
)

// Spec §3.2: SelectorRef
case class SelectorRef(
  strategy:           String,
  value:              String,
  roleName:           Option[String],
)

// Spec §3.2: Assertion
case class Assertion(
  assertionType:      String,
  selector:           Option[SelectorRef],
  expected:           Option[String],
  jsonPath:           Option[String],
  tolerance:          Option[Double],
  description:        String,
)

// Spec §3.2: ArtifactCapture
case class ArtifactCapture(
  artifactType:       ArtifactType,
  trigger:            String,
  label:              Option[String],
)

// Spec §3.2: ApiContractVerifierSpec
case class ApiContractVerifierSpec(
  method:             String,
  path:               String,
  serviceId:          Option[String] = None,
  authMode:           Option[AuthMode] = None,
  headers:            Map[String, String] = Map.empty,
  queryParams:        Map[String, String] = Map.empty,
  requestBody:        Option[String] = None,
  expectedStatus:     Int,
  expectedHeaders:    Map[String, String] = Map.empty,
  responseAssertions: List[Assertion] = Nil,
  sideEffectChecks:   List[StateAssertionVerifierSpec] = Nil,
  artifactPlan:       List[ArtifactCapture] = Nil,
)

// Spec §3.2: StateAssertionVerifierSpec
case class StateAssertionVerifierSpec(
  source:             String,
  serviceId:          Option[String] = None,
  query:              String,
  bindVariables:      Map[String, String] = Map.empty,
  assertions:         List[Assertion] = Nil,
  readOnly:           Boolean = true,
)

// Spec §3.2: EnvReadinessVerifierSpec
case class EnvReadinessVerifierSpec(
  serviceId:          String,
  checkType:          String = "http_status",
  target:             Option[String] = None,
  expectedValue:      Option[String] = None,
  probeOverride:      Option[ReadinessProbe] = None,
  requiredLogPatterns: List[String] = Nil,
)

// Spec §3.2: ConsoleLogVerifierSpec
case class ConsoleLogVerifierSpec(
  url:                String,
  forbiddenPatterns:  List[String] = Nil,
  requiredPatterns:   List[String] = Nil,
  severityThreshold:  String = "error",
)

// Spec §3.2: NetworkExpectationVerifierSpec
case class NetworkExpectationVerifierSpec(
  targetUrl:          String,
  expectedRequests:   List[ExpectedNetworkRequest],
  forbiddenRequests:  List[ForbiddenNetworkRequest],
  captureAll:         Boolean,
)

// Spec §3.2: ExpectedNetworkRequest
case class ExpectedNetworkRequest(
  urlPattern:         String,
  method:             Option[String],
  expectedStatus:     Option[Int],
  description:        String,
)

// Spec §3.2: ForbiddenNetworkRequest
case class ForbiddenNetworkRequest(
  urlPattern:         String,
  method:             Option[String],
  description:        String,
)

// Spec §3.2: QueueJobVerifierSpec
case class QueueJobVerifierSpec(
  triggerAction:      String,
  queueServiceId:     String,
  expectedJobType:    String,
  maxWaitMs:          Int,
  assertions:         List[Assertion],
)

// Spec §3.2: PersistenceReloadVerifierSpec
case class PersistenceReloadVerifierSpec(
  setupActions:       List[BrowserAction],
  reloadMethod:       String,
  postReloadAssertions: List[Assertion],
  dataIntegrityChecks: List[Assertion],
)

// Spec §3.2: RegressionVerifierSpec
case class RegressionVerifierSpec(
  baselineVerifierId: String,
  regressionScope:    List[String],
  toleranceOverrides: Map[String, Double],
)

// Design: Agentic Browser UI Verification §7.2
case class AgentBrowserVerifierSpec(
  entryUrl:            String,
  featureDescription:  String,
  viewports:           List[Viewport]       = Nil,
  tasteSensitivity:    TasteSensitivity     = TasteSensitivity.Normal,
  tasteTriggersRepair: Boolean              = true,
  maxBudgetUsd:        Double               = 50.0,
)

case class Viewport(width: Int, height: Int)

sealed trait TasteSensitivity
object TasteSensitivity {
  case object Strict  extends TasteSensitivity  // all taste issues → repair
  case object Normal  extends TasteSensitivity  // warning + error → repair
  case object Lenient extends TasteSensitivity  // only error → repair
  case object Off     extends TasteSensitivity  // never triggers repair
}
