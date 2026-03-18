package lastmile.model

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
  urlTemplate:        String,
  headers:            Map[String, String],
  bodyTemplate:       Option[String],
  expectedStatus:     Int,
  responseAssertions: List[Assertion],
  artifactPlan:       List[ArtifactCapture],
)

// Spec §3.2: StateAssertionVerifierSpec
case class StateAssertionVerifierSpec(
  queryType:          String,
  connectionRef:      String,
  query:              String,
  assertions:         List[Assertion],
  setupCommands:      List[String],
  teardownCommands:   List[String],
)

// Spec §3.2: EnvReadinessVerifierSpec
case class EnvReadinessVerifierSpec(
  serviceId:          String,
  probeOverride:      Option[ReadinessProbe],
  requiredLogPatterns: List[String],
)

// Spec §3.2: ConsoleLogVerifierSpec
case class ConsoleLogVerifierSpec(
  targetUrl:          String,
  forbiddenPatterns:  List[String],
  allowedPatterns:    List[String],
  maxErrors:          Int,
  captureLevel:       String,
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
