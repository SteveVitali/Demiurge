package demiurge.model

// Spec §3.1: RunStatus enum — 21 values
sealed trait RunStatus
object RunStatus {
  case object Created extends RunStatus
  case object InspectingRepo extends RunStatus
  case object CompilingRequirements extends RunStatus
  case object PlanningEnvironment extends RunStatus
  case object BootstrappingEnvironment extends RunStatus
  case object EnvironmentFailed extends RunStatus
  case object SeedingFixtures extends RunStatus
  case object BootstrappingAuth extends RunStatus
  case object ReadyToVerify extends RunStatus
  case object Verifying extends RunStatus
  case object AnalyzingFailure extends RunStatus
  case object PlanningRepair extends RunStatus
  case object Repairing extends RunStatus
  case object RepairFailed extends RunStatus
  case object PlanningRerun extends RunStatus
  case object SoftResettingEnvironment extends RunStatus
  case object RebuildingEnvironment extends RunStatus
  case object Succeeded extends RunStatus
  case object Exhausted extends RunStatus
  case object Cancelled extends RunStatus
  case object Interrupted extends RunStatus

  val values: List[RunStatus] = List(
    Created, InspectingRepo, CompilingRequirements, PlanningEnvironment,
    BootstrappingEnvironment, EnvironmentFailed, SeedingFixtures,
    BootstrappingAuth, ReadyToVerify, Verifying, AnalyzingFailure,
    PlanningRepair, Repairing, RepairFailed, PlanningRerun,
    SoftResettingEnvironment, RebuildingEnvironment,
    Succeeded, Exhausted, Cancelled, Interrupted
  )
}

// Spec §3.1: AttemptStatus enum — 10 values
sealed trait AttemptStatus
object AttemptStatus {
  case object Created extends AttemptStatus
  case object Verifying extends AttemptStatus
  case object VerificationPassed extends AttemptStatus
  case object VerificationFailed extends AttemptStatus
  case object Analyzing extends AttemptStatus
  case object Repairing extends AttemptStatus
  case object RepairSucceeded extends AttemptStatus
  case object RepairFailed extends AttemptStatus
  case object PendingRerun extends AttemptStatus
  case object Aborted extends AttemptStatus

  val values: List[AttemptStatus] = List(
    Created, Verifying, VerificationPassed, VerificationFailed,
    Analyzing, Repairing, RepairSucceeded, RepairFailed,
    PendingRerun, Aborted
  )
}

// Spec §3.1: ServiceStatus enum — 7 values
sealed trait ServiceStatus
object ServiceStatus {
  case object Pending extends ServiceStatus
  case object Starting extends ServiceStatus
  case object RunningUnhealthy extends ServiceStatus
  case object RunningHealthy extends ServiceStatus
  case object Failed extends ServiceStatus
  case object Stopped extends ServiceStatus
  case object Restarting extends ServiceStatus

  val values: List[ServiceStatus] = List(
    Pending, Starting, RunningUnhealthy, RunningHealthy,
    Failed, Stopped, Restarting
  )
}

// Spec §3.1: EnvironmentStatus enum — 8 values
sealed trait EnvironmentStatus
object EnvironmentStatus {
  case object Planned extends EnvironmentStatus
  case object Booting extends EnvironmentStatus
  case object PartiallyHealthy extends EnvironmentStatus
  case object Ready extends EnvironmentStatus
  case object Degraded extends EnvironmentStatus
  case object Failed extends EnvironmentStatus
  case object TearingDown extends EnvironmentStatus
  case object Stopped extends EnvironmentStatus

  val values: List[EnvironmentStatus] = List(
    Planned, Booting, PartiallyHealthy, Ready,
    Degraded, Failed, TearingDown, Stopped
  )
}

// Spec §3.1: RequirementPriority enum — 3 values
sealed trait RequirementPriority
object RequirementPriority {
  case object Required extends RequirementPriority
  case object Important extends RequirementPriority
  case object NiceToHave extends RequirementPriority

  val values: List[RequirementPriority] = List(Required, Important, NiceToHave)
}

// Spec §3.1: RequirementCategory enum — 8 values
sealed trait RequirementCategory
object RequirementCategory {
  case object UiFlow extends RequirementCategory
  case object ApiContract extends RequirementCategory
  case object PersistenceState extends RequirementCategory
  case object BackgroundProcessing extends RequirementCategory
  case object AuthSession extends RequirementCategory
  case object IntegrationInvariant extends RequirementCategory
  case object RegressionGuard extends RequirementCategory
  case object EnvironmentReadiness extends RequirementCategory

  val values: List[RequirementCategory] = List(
    UiFlow, ApiContract, PersistenceState, BackgroundProcessing,
    AuthSession, IntegrationInvariant, RegressionGuard, EnvironmentReadiness
  )
}

// Spec §3.1: VerdictStatus enum — 6 values
sealed trait VerdictStatus
object VerdictStatus {
  case object Pass extends VerdictStatus
  case object Fail extends VerdictStatus
  case object Inconclusive extends VerdictStatus
  case object Blocked extends VerdictStatus
  case object Timeout extends VerdictStatus
  case object Flake extends VerdictStatus

  val values: List[VerdictStatus] = List(Pass, Fail, Inconclusive, Blocked, Timeout, Flake)
}

// Spec §3.1: VerifierType enum — 9 values
sealed trait VerifierType
object VerifierType {
  case object EnvironmentReadiness extends VerifierType
  case object HttpApiContract extends VerifierType
  case object BrowserFlow extends VerifierType
  case object StateAssertion extends VerifierType
  case object QueueJob extends VerifierType
  case object ConsoleLogSanity extends VerifierType
  case object NetworkExpectation extends VerifierType
  case object PersistenceReload extends VerifierType
  case object TargetedRegression extends VerifierType

  val values: List[VerifierType] = List(
    EnvironmentReadiness, HttpApiContract, BrowserFlow,
    StateAssertion, QueueJob, ConsoleLogSanity,
    NetworkExpectation, PersistenceReload, TargetedRegression
  )
}

// Spec §3.1: FailureClass enum — 18 values
sealed trait FailureClass
object FailureClass {
  case object EnvironmentBootFailure extends FailureClass
  case object ServiceUnhealthy extends FailureClass
  case object FixtureFailure extends FailureClass
  case object AuthBootstrapFailure extends FailureClass
  case object LocatorDrift extends FailureClass
  case object BrowserTimingFlake extends FailureClass
  case object FrontendRenderError extends FailureClass
  case object ConsoleErrorRegression extends FailureClass
  case object BackendContractFailure extends FailureClass
  case object PersistenceFailure extends FailureClass
  case object QueueSideEffectFailure extends FailureClass
  case object IntegrationFailure extends FailureClass
  case object ObservabilityGap extends FailureClass
  case object AmbiguousRequirement extends FailureClass
  case object SuspectedNondeterminism extends FailureClass
  case object InferenceFailure extends FailureClass
  case object RepairBackendError extends FailureClass
  case object UnknownFailure extends FailureClass

  val values: List[FailureClass] = List(
    EnvironmentBootFailure, ServiceUnhealthy, FixtureFailure,
    AuthBootstrapFailure, LocatorDrift, BrowserTimingFlake,
    FrontendRenderError, ConsoleErrorRegression,
    BackendContractFailure, PersistenceFailure,
    QueueSideEffectFailure, IntegrationFailure,
    ObservabilityGap, AmbiguousRequirement,
    SuspectedNondeterminism, InferenceFailure,
    RepairBackendError, UnknownFailure
  )
}

// Spec §3.1: ServiceKind enum — 7 values
sealed trait ServiceKind
object ServiceKind {
  case object Frontend extends ServiceKind
  case object Api extends ServiceKind
  case object Db extends ServiceKind
  case object Cache extends ServiceKind
  case object Queue extends ServiceKind
  case object Worker extends ServiceKind
  case object ExternalMock extends ServiceKind

  val values: List[ServiceKind] = List(Frontend, Api, Db, Cache, Queue, Worker, ExternalMock)
}

// Spec §3.1: StartupMode enum — 4 values
sealed trait StartupMode
object StartupMode {
  case object ComposeNative extends StartupMode
  case object ScriptNative extends StartupMode
  case object Hybrid extends StartupMode
  case object VerifierOwnedContainer extends StartupMode

  val values: List[StartupMode] = List(ComposeNative, ScriptNative, Hybrid, VerifierOwnedContainer)
}

// Spec §3.1: AuthMode enum — 5 values
sealed trait AuthMode
object AuthMode {
  case object BrowserFormLogin extends AuthMode
  case object ApiLogin extends AuthMode
  case object StaticTestToken extends AuthMode
  case object SeededLocalSession extends AuthMode
  case object DevBypassHeader extends AuthMode

  val values: List[AuthMode] = List(
    BrowserFormLogin, ApiLogin, StaticTestToken, SeededLocalSession, DevBypassHeader
  )
}

// Spec §3.1: ArtifactType enum — 24 values
sealed trait ArtifactType
object ArtifactType {
  case object Plan extends ArtifactType
  case object ServiceLog extends ArtifactType
  case object StartupTimeline extends ArtifactType
  case object StdoutExcerpt extends ArtifactType
  case object StderrExcerpt extends ArtifactType
  case object BrowserTrace extends ArtifactType
  case object Screenshot extends ArtifactType
  case object DomSnapshot extends ArtifactType
  case object AccessibilitySnapshot extends ArtifactType
  case object ConsoleLog extends ArtifactType
  case object NetworkSummary extends ArtifactType
  case object ApiRequestResponse extends ArtifactType
  case object DbQueryResult extends ArtifactType
  case object QueueObservation extends ArtifactType
  case object PatchDiff extends ArtifactType
  case object StructuredVerdict extends ArtifactType
  case object FailurePacketArtifact extends ArtifactType
  case object FinalReport extends ArtifactType
  case object RepairTranscript extends ArtifactType
  case object InferenceLog extends ArtifactType
  case object RepoInspectionArtifact extends ArtifactType
  case object AuthStorageState extends ArtifactType
  case object PromptPackage extends ArtifactType
  case object AttemptReport extends ArtifactType

  val values: List[ArtifactType] = List(
    Plan, ServiceLog, StartupTimeline, StdoutExcerpt, StderrExcerpt,
    BrowserTrace, Screenshot, DomSnapshot, AccessibilitySnapshot,
    ConsoleLog, NetworkSummary, ApiRequestResponse,
    DbQueryResult, QueueObservation, PatchDiff,
    StructuredVerdict, FailurePacketArtifact, FinalReport,
    RepairTranscript, InferenceLog, RepoInspectionArtifact,
    AuthStorageState, PromptPackage, AttemptReport
  )
}

// Spec §3.1: RunMode enum — 4 values
sealed trait RunMode
object RunMode {
  case object Full extends RunMode
  case object PlanOnly extends RunMode
  case object VerifyOnly extends RunMode
  case object RepairOnly extends RunMode

  val values: List[RunMode] = List(Full, PlanOnly, VerifyOnly, RepairOnly)
}

// Spec §3.1: ResetStrategy enum — 3 values
sealed trait ResetStrategy
object ResetStrategy {
  case object SoftReset extends ResetStrategy
  case object HardReset extends ResetStrategy
  case object FullRebuild extends ResetStrategy

  val values: List[ResetStrategy] = List(SoftReset, HardReset, FullRebuild)
}

// Spec §3.1: InferenceProvider enum — 4 values
sealed trait InferenceProvider
object InferenceProvider {
  case object Anthropic extends InferenceProvider
  case object OpenAI extends InferenceProvider
  case object Local extends InferenceProvider
  case object Mock extends InferenceProvider

  val values: List[InferenceProvider] = List(Anthropic, OpenAI, Local, Mock)
}

// Spec §3.1: DependencyEdgeType enum — 3 values
sealed trait DependencyEdgeType
object DependencyEdgeType {
  case object Hard extends DependencyEdgeType
  case object Soft extends DependencyEdgeType
  case object Ordering extends DependencyEdgeType

  val values: List[DependencyEdgeType] = List(Hard, Soft, Ordering)
}

// Spec §3.1: RepairResultStatus enum — 6 values
sealed trait RepairResultStatus
object RepairResultStatus {
  case object Success extends RepairResultStatus
  case object NoChangeNeeded extends RepairResultStatus
  case object PartialFix extends RepairResultStatus
  case object Failed extends RepairResultStatus
  case object Timeout extends RepairResultStatus
  case object Cancelled extends RepairResultStatus

  val values: List[RepairResultStatus] = List(
    Success, NoChangeNeeded, PartialFix, Failed, Timeout, Cancelled
  )
}

// Spec §3.1: RepairBackendError ADT — 9 variants with fields
sealed trait RepairBackendError
object RepairBackendError {
  case class SessionCreationFailed(reason: String) extends RepairBackendError
  case class TaskSubmissionFailed(reason: String) extends RepairBackendError
  case class BackendTimeout(elapsedMs: Long) extends RepairBackendError
  case class BackendCrashed(reason: String) extends RepairBackendError
  case class MalformedOutput(rawOutput: String, parseError: String) extends RepairBackendError
  case class PolicyViolation(tool: String, action: String, detail: String) extends RepairBackendError
  case class BudgetExceeded(tokensUsed: Long, tokensAllowed: Long) extends RepairBackendError
  case object EmptyPatch extends RepairBackendError
  case class ConflictingPatch(detail: String) extends RepairBackendError
}

// Spec §3.1: InferenceError ADT — 6 variants with fields
sealed trait InferenceError
object InferenceError {
  case class Timeout(requestId: String, elapsedMs: Long) extends InferenceError
  case class BudgetExceeded(requestId: String, component: String, remainingTokens: Long, requestedTokens: Long) extends InferenceError
  case class RateLimited(requestId: String, retryAfterMs: Long) extends InferenceError
  case class MalformedResponse(requestId: String, rawResponse: String, parseError: String) extends InferenceError
  case class ProviderError(requestId: String, statusCode: Int, message: String) extends InferenceError
  case class SchemaValidationFailed(requestId: String, rawJson: String, schemaErrors: List[String]) extends InferenceError
}

// Spec §3.1: WorkerTaskStatus enum — 5 values
sealed trait WorkerTaskStatus
object WorkerTaskStatus {
  case object Pass extends WorkerTaskStatus
  case object Fail extends WorkerTaskStatus
  case object Inconclusive extends WorkerTaskStatus
  case object Timeout extends WorkerTaskStatus
  case object Error extends WorkerTaskStatus

  val values: List[WorkerTaskStatus] = List(Pass, Fail, Inconclusive, Timeout, Error)
}
