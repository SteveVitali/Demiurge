package demiurge.model

import io.circe._
import io.circe.generic.semiauto._
import java.nio.file.{Path, Paths}
import java.time.{Duration, Instant}

// Rule 10: Use semiauto derivation for case classes, manual for sealed trait enums

object JsonCodecs {

  // --- Primitive type codecs ---

  // Rule 5: Instant as ISO-8601
  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  implicit val instantDecoder: Decoder[Instant] = Decoder.decodeString.emap { s =>
    try Right(Instant.parse(s)) catch { case e: Exception => Left(s"Invalid instant: $s") }
  }

  // Rule 6: Path as string
  implicit val pathEncoder: Encoder[Path] = Encoder.encodeString.contramap(_.toString)
  implicit val pathDecoder: Decoder[Path] = Decoder.decodeString.map(Paths.get(_))

  // Rule 7: Duration as ISO-8601 duration
  implicit val durationEncoder: Encoder[Duration] = Encoder.encodeString.contramap(_.toString)
  implicit val durationDecoder: Decoder[Duration] = Decoder.decodeString.emap { s =>
    try Right(Duration.parse(s)) catch { case e: Exception => Left(s"Invalid duration: $s") }
  }

  // --- Simple enum codecs (Rule 9: string tag name) ---

  private def simpleEnumEncoder[A](name: A => String): Encoder[A] =
    Encoder.encodeString.contramap(name)

  private def simpleEnumDecoder[A](parse: String => Option[A], typeName: String): Decoder[A] =
    Decoder.decodeString.emap(s => parse(s).toRight(s"Unknown $typeName: $s"))

  // RunStatus
  implicit val runStatusEncoder: Encoder[RunStatus] = simpleEnumEncoder(_.toString)
  implicit val runStatusDecoder: Decoder[RunStatus] = simpleEnumDecoder(s =>
    RunStatus.values.find(_.toString == s), "RunStatus")

  // AttemptStatus
  implicit val attemptStatusEncoder: Encoder[AttemptStatus] = simpleEnumEncoder(_.toString)
  implicit val attemptStatusDecoder: Decoder[AttemptStatus] = simpleEnumDecoder(s =>
    AttemptStatus.values.find(_.toString == s), "AttemptStatus")

  // ServiceStatus
  implicit val serviceStatusEncoder: Encoder[ServiceStatus] = simpleEnumEncoder(_.toString)
  implicit val serviceStatusDecoder: Decoder[ServiceStatus] = simpleEnumDecoder(s =>
    ServiceStatus.values.find(_.toString == s), "ServiceStatus")

  // EnvironmentStatus
  implicit val environmentStatusEncoder: Encoder[EnvironmentStatus] = simpleEnumEncoder(_.toString)
  implicit val environmentStatusDecoder: Decoder[EnvironmentStatus] = simpleEnumDecoder(s =>
    EnvironmentStatus.values.find(_.toString == s), "EnvironmentStatus")

  // RequirementPriority
  implicit val requirementPriorityEncoder: Encoder[RequirementPriority] = simpleEnumEncoder(_.toString)
  implicit val requirementPriorityDecoder: Decoder[RequirementPriority] = simpleEnumDecoder(s =>
    RequirementPriority.values.find(_.toString == s), "RequirementPriority")

  // RequirementCategory
  implicit val requirementCategoryEncoder: Encoder[RequirementCategory] = simpleEnumEncoder(_.toString)
  implicit val requirementCategoryDecoder: Decoder[RequirementCategory] = simpleEnumDecoder(s =>
    RequirementCategory.values.find(_.toString == s), "RequirementCategory")

  // VerdictStatus
  implicit val verdictStatusEncoder: Encoder[VerdictStatus] = simpleEnumEncoder(_.toString)
  implicit val verdictStatusDecoder: Decoder[VerdictStatus] = simpleEnumDecoder(s =>
    VerdictStatus.values.find(_.toString == s), "VerdictStatus")

  // VerifierType
  implicit val verifierTypeEncoder: Encoder[VerifierType] = simpleEnumEncoder(_.toString)
  implicit val verifierTypeDecoder: Decoder[VerifierType] = simpleEnumDecoder(s =>
    VerifierType.values.find(_.toString == s), "VerifierType")

  // FailureClass
  implicit val failureClassEncoder: Encoder[FailureClass] = simpleEnumEncoder(_.toString)
  implicit val failureClassDecoder: Decoder[FailureClass] = simpleEnumDecoder(s =>
    FailureClass.values.find(_.toString == s), "FailureClass")

  // ServiceKind
  implicit val serviceKindEncoder: Encoder[ServiceKind] = simpleEnumEncoder(_.toString)
  implicit val serviceKindDecoder: Decoder[ServiceKind] = simpleEnumDecoder(s =>
    ServiceKind.values.find(_.toString == s), "ServiceKind")

  // StartupMode
  implicit val startupModeEncoder: Encoder[StartupMode] = simpleEnumEncoder(_.toString)
  implicit val startupModeDecoder: Decoder[StartupMode] = simpleEnumDecoder(s =>
    StartupMode.values.find(_.toString == s), "StartupMode")

  // AuthMode
  implicit val authModeEncoder: Encoder[AuthMode] = simpleEnumEncoder(_.toString)
  implicit val authModeDecoder: Decoder[AuthMode] = simpleEnumDecoder(s =>
    AuthMode.values.find(_.toString == s), "AuthMode")

  // ArtifactType
  implicit val artifactTypeEncoder: Encoder[ArtifactType] = simpleEnumEncoder(_.toString)
  implicit val artifactTypeDecoder: Decoder[ArtifactType] = simpleEnumDecoder(s =>
    ArtifactType.values.find(_.toString == s), "ArtifactType")

  // RunMode
  implicit val runModeEncoder: Encoder[RunMode] = simpleEnumEncoder(_.toString)
  implicit val runModeDecoder: Decoder[RunMode] = simpleEnumDecoder(s =>
    RunMode.values.find(_.toString == s), "RunMode")

  // ResetStrategy
  implicit val resetStrategyEncoder: Encoder[ResetStrategy] = simpleEnumEncoder(_.toString)
  implicit val resetStrategyDecoder: Decoder[ResetStrategy] = simpleEnumDecoder(s =>
    ResetStrategy.values.find(_.toString == s), "ResetStrategy")

  // InferenceProvider
  implicit val inferenceProviderEncoder: Encoder[InferenceProvider] = simpleEnumEncoder(_.toString)
  implicit val inferenceProviderDecoder: Decoder[InferenceProvider] = simpleEnumDecoder(s =>
    InferenceProvider.values.find(_.toString == s), "InferenceProvider")

  // DependencyEdgeType
  implicit val dependencyEdgeTypeEncoder: Encoder[DependencyEdgeType] = simpleEnumEncoder(_.toString)
  implicit val dependencyEdgeTypeDecoder: Decoder[DependencyEdgeType] = simpleEnumDecoder(s =>
    DependencyEdgeType.values.find(_.toString == s), "DependencyEdgeType")

  // RepairResultStatus
  implicit val repairResultStatusEncoder: Encoder[RepairResultStatus] = simpleEnumEncoder(_.toString)
  implicit val repairResultStatusDecoder: Decoder[RepairResultStatus] = simpleEnumDecoder(s =>
    RepairResultStatus.values.find(_.toString == s), "RepairResultStatus")

  // WorkerTaskStatus
  implicit val workerTaskStatusEncoder: Encoder[WorkerTaskStatus] = simpleEnumEncoder(_.toString)
  implicit val workerTaskStatusDecoder: Decoder[WorkerTaskStatus] = simpleEnumDecoder(s =>
    WorkerTaskStatus.values.find(_.toString == s), "WorkerTaskStatus")

  // --- ADT enum codecs (Rule 9: JSON objects with type discriminator) ---

  // RepairBackendError
  implicit val repairBackendErrorEncoder: Encoder[RepairBackendError] = Encoder.instance {
    case RepairBackendError.SessionCreationFailed(reason) =>
      Json.obj("type" -> Json.fromString("SessionCreationFailed"), "reason" -> Json.fromString(reason))
    case RepairBackendError.TaskSubmissionFailed(reason) =>
      Json.obj("type" -> Json.fromString("TaskSubmissionFailed"), "reason" -> Json.fromString(reason))
    case RepairBackendError.BackendTimeout(elapsedMs) =>
      Json.obj("type" -> Json.fromString("BackendTimeout"), "elapsedMs" -> Json.fromLong(elapsedMs))
    case RepairBackendError.BackendCrashed(reason) =>
      Json.obj("type" -> Json.fromString("BackendCrashed"), "reason" -> Json.fromString(reason))
    case RepairBackendError.MalformedOutput(rawOutput, parseError) =>
      Json.obj("type" -> Json.fromString("MalformedOutput"), "rawOutput" -> Json.fromString(rawOutput), "parseError" -> Json.fromString(parseError))
    case RepairBackendError.PolicyViolation(tool, action, detail) =>
      Json.obj("type" -> Json.fromString("PolicyViolation"), "tool" -> Json.fromString(tool), "action" -> Json.fromString(action), "detail" -> Json.fromString(detail))
    case RepairBackendError.BudgetExceeded(tokensUsed, tokensAllowed) =>
      Json.obj("type" -> Json.fromString("BudgetExceeded"), "tokensUsed" -> Json.fromLong(tokensUsed), "tokensAllowed" -> Json.fromLong(tokensAllowed))
    case RepairBackendError.EmptyPatch =>
      Json.obj("type" -> Json.fromString("EmptyPatch"))
    case RepairBackendError.ConflictingPatch(detail) =>
      Json.obj("type" -> Json.fromString("ConflictingPatch"), "detail" -> Json.fromString(detail))
  }

  implicit val repairBackendErrorDecoder: Decoder[RepairBackendError] = Decoder.instance { c =>
    c.get[String]("type").flatMap {
      case "SessionCreationFailed" => c.get[String]("reason").map(RepairBackendError.SessionCreationFailed)
      case "TaskSubmissionFailed" => c.get[String]("reason").map(RepairBackendError.TaskSubmissionFailed)
      case "BackendTimeout" => c.get[Long]("elapsedMs").map(RepairBackendError.BackendTimeout)
      case "BackendCrashed" => c.get[String]("reason").map(RepairBackendError.BackendCrashed)
      case "MalformedOutput" => for { r <- c.get[String]("rawOutput"); p <- c.get[String]("parseError") } yield RepairBackendError.MalformedOutput(r, p)
      case "PolicyViolation" => for { t <- c.get[String]("tool"); a <- c.get[String]("action"); d <- c.get[String]("detail") } yield RepairBackendError.PolicyViolation(t, a, d)
      case "BudgetExceeded" => for { u <- c.get[Long]("tokensUsed"); a <- c.get[Long]("tokensAllowed") } yield RepairBackendError.BudgetExceeded(u, a)
      case "EmptyPatch" => Right(RepairBackendError.EmptyPatch)
      case "ConflictingPatch" => c.get[String]("detail").map(RepairBackendError.ConflictingPatch)
      case other => Left(DecodingFailure(s"Unknown RepairBackendError type: $other", c.history))
    }
  }

  // InferenceError
  implicit val inferenceErrorEncoder: Encoder[InferenceError] = Encoder.instance {
    case InferenceError.Timeout(requestId, elapsedMs) =>
      Json.obj("type" -> Json.fromString("Timeout"), "requestId" -> Json.fromString(requestId), "elapsedMs" -> Json.fromLong(elapsedMs))
    case InferenceError.BudgetExceeded(requestId, component, remainingTokens, requestedTokens) =>
      Json.obj("type" -> Json.fromString("BudgetExceeded"), "requestId" -> Json.fromString(requestId), "component" -> Json.fromString(component), "remainingTokens" -> Json.fromLong(remainingTokens), "requestedTokens" -> Json.fromLong(requestedTokens))
    case InferenceError.RateLimited(requestId, retryAfterMs) =>
      Json.obj("type" -> Json.fromString("RateLimited"), "requestId" -> Json.fromString(requestId), "retryAfterMs" -> Json.fromLong(retryAfterMs))
    case InferenceError.MalformedResponse(requestId, rawResponse, parseError) =>
      Json.obj("type" -> Json.fromString("MalformedResponse"), "requestId" -> Json.fromString(requestId), "rawResponse" -> Json.fromString(rawResponse), "parseError" -> Json.fromString(parseError))
    case InferenceError.ProviderError(requestId, statusCode, message) =>
      Json.obj("type" -> Json.fromString("ProviderError"), "requestId" -> Json.fromString(requestId), "statusCode" -> Json.fromInt(statusCode), "message" -> Json.fromString(message))
    case InferenceError.SchemaValidationFailed(requestId, rawJson, schemaErrors) =>
      Json.obj("type" -> Json.fromString("SchemaValidationFailed"), "requestId" -> Json.fromString(requestId), "rawJson" -> Json.fromString(rawJson), "schemaErrors" -> Json.arr(schemaErrors.map(Json.fromString): _*))
  }

  implicit val inferenceErrorDecoder: Decoder[InferenceError] = Decoder.instance { c =>
    c.get[String]("type").flatMap {
      case "Timeout" => for { r <- c.get[String]("requestId"); e <- c.get[Long]("elapsedMs") } yield InferenceError.Timeout(r, e)
      case "BudgetExceeded" => for { r <- c.get[String]("requestId"); comp <- c.get[String]("component"); rem <- c.get[Long]("remainingTokens"); req <- c.get[Long]("requestedTokens") } yield InferenceError.BudgetExceeded(r, comp, rem, req)
      case "RateLimited" => for { r <- c.get[String]("requestId"); a <- c.get[Long]("retryAfterMs") } yield InferenceError.RateLimited(r, a)
      case "MalformedResponse" => for { r <- c.get[String]("requestId"); raw <- c.get[String]("rawResponse"); p <- c.get[String]("parseError") } yield InferenceError.MalformedResponse(r, raw, p)
      case "ProviderError" => for { r <- c.get[String]("requestId"); s <- c.get[Int]("statusCode"); m <- c.get[String]("message") } yield InferenceError.ProviderError(r, s, m)
      case "SchemaValidationFailed" => for { r <- c.get[String]("requestId"); raw <- c.get[String]("rawJson"); errs <- c.get[List[String]]("schemaErrors") } yield InferenceError.SchemaValidationFailed(r, raw, errs)
      case other => Left(DecodingFailure(s"Unknown InferenceError type: $other", c.history))
    }
  }

  // --- Case class codecs (Rule 10: semiauto derivation) ---
  // Order matters: leaf types first, then types that depend on them.

  // AttemptVerdictSummary
  implicit val attemptVerdictSummaryEncoder: Encoder[AttemptVerdictSummary] = deriveEncoder
  implicit val attemptVerdictSummaryDecoder: Decoder[AttemptVerdictSummary] = deriveDecoder

  // SelectorRef
  implicit val selectorRefEncoder: Encoder[SelectorRef] = deriveEncoder
  implicit val selectorRefDecoder: Decoder[SelectorRef] = deriveDecoder

  // Assertion
  implicit val assertionEncoder: Encoder[Assertion] = deriveEncoder
  implicit val assertionDecoder: Decoder[Assertion] = deriveDecoder

  // ArtifactCapture
  implicit val artifactCaptureEncoder: Encoder[ArtifactCapture] = deriveEncoder
  implicit val artifactCaptureDecoder: Decoder[ArtifactCapture] = deriveDecoder

  // BrowserAction
  implicit val browserActionEncoder: Encoder[BrowserAction] = deriveEncoder
  implicit val browserActionDecoder: Decoder[BrowserAction] = deriveDecoder

  // BrowserFlowVerifierSpec
  implicit val browserFlowVerifierSpecEncoder: Encoder[BrowserFlowVerifierSpec] = deriveEncoder
  implicit val browserFlowVerifierSpecDecoder: Decoder[BrowserFlowVerifierSpec] = deriveDecoder

  // ReadinessProbe
  implicit val readinessProbeEncoder: Encoder[ReadinessProbe] = deriveEncoder
  implicit val readinessProbeDecoder: Decoder[ReadinessProbe] = deriveDecoder

  // ApiContractVerifierSpec
  implicit val apiContractVerifierSpecEncoder: Encoder[ApiContractVerifierSpec] = deriveEncoder
  implicit val apiContractVerifierSpecDecoder: Decoder[ApiContractVerifierSpec] = deriveDecoder

  // StateAssertionVerifierSpec
  implicit val stateAssertionVerifierSpecEncoder: Encoder[StateAssertionVerifierSpec] = deriveEncoder
  implicit val stateAssertionVerifierSpecDecoder: Decoder[StateAssertionVerifierSpec] = deriveDecoder

  // EnvReadinessVerifierSpec
  implicit val envReadinessVerifierSpecEncoder: Encoder[EnvReadinessVerifierSpec] = deriveEncoder
  implicit val envReadinessVerifierSpecDecoder: Decoder[EnvReadinessVerifierSpec] = deriveDecoder

  // ConsoleLogVerifierSpec
  implicit val consoleLogVerifierSpecEncoder: Encoder[ConsoleLogVerifierSpec] = deriveEncoder
  implicit val consoleLogVerifierSpecDecoder: Decoder[ConsoleLogVerifierSpec] = deriveDecoder

  // ExpectedNetworkRequest
  implicit val expectedNetworkRequestEncoder: Encoder[ExpectedNetworkRequest] = deriveEncoder
  implicit val expectedNetworkRequestDecoder: Decoder[ExpectedNetworkRequest] = deriveDecoder

  // ForbiddenNetworkRequest
  implicit val forbiddenNetworkRequestEncoder: Encoder[ForbiddenNetworkRequest] = deriveEncoder
  implicit val forbiddenNetworkRequestDecoder: Decoder[ForbiddenNetworkRequest] = deriveDecoder

  // NetworkExpectationVerifierSpec
  implicit val networkExpectationVerifierSpecEncoder: Encoder[NetworkExpectationVerifierSpec] = deriveEncoder
  implicit val networkExpectationVerifierSpecDecoder: Decoder[NetworkExpectationVerifierSpec] = deriveDecoder

  // QueueJobVerifierSpec
  implicit val queueJobVerifierSpecEncoder: Encoder[QueueJobVerifierSpec] = deriveEncoder
  implicit val queueJobVerifierSpecDecoder: Decoder[QueueJobVerifierSpec] = deriveDecoder

  // PersistenceReloadVerifierSpec
  implicit val persistenceReloadVerifierSpecEncoder: Encoder[PersistenceReloadVerifierSpec] = deriveEncoder
  implicit val persistenceReloadVerifierSpecDecoder: Decoder[PersistenceReloadVerifierSpec] = deriveDecoder

  // RegressionVerifierSpec
  implicit val regressionVerifierSpecEncoder: Encoder[RegressionVerifierSpec] = deriveEncoder
  implicit val regressionVerifierSpecDecoder: Decoder[RegressionVerifierSpec] = deriveDecoder

  // VerifierSpec
  implicit val verifierSpecEncoder: Encoder[VerifierSpec] = deriveEncoder
  implicit val verifierSpecDecoder: Decoder[VerifierSpec] = deriveDecoder

  // PortMapping
  implicit val portMappingEncoder: Encoder[PortMapping] = deriveEncoder
  implicit val portMappingDecoder: Decoder[PortMapping] = deriveDecoder

  // RestartPolicy
  implicit val restartPolicyEncoder: Encoder[RestartPolicy] = deriveEncoder
  implicit val restartPolicyDecoder: Decoder[RestartPolicy] = deriveDecoder

  // ServiceSpec
  implicit val serviceSpecEncoder: Encoder[ServiceSpec] = deriveEncoder
  implicit val serviceSpecDecoder: Decoder[ServiceSpec] = deriveDecoder

  // FixtureStep
  implicit val fixtureStepEncoder: Encoder[FixtureStep] = deriveEncoder
  implicit val fixtureStepDecoder: Decoder[FixtureStep] = deriveDecoder

  // AuthBootstrapPlan
  implicit val authBootstrapPlanEncoder: Encoder[AuthBootstrapPlan] = deriveEncoder
  implicit val authBootstrapPlanDecoder: Decoder[AuthBootstrapPlan] = deriveDecoder

  // AuthContext
  implicit val authContextEncoder: Encoder[AuthContext] = deriveEncoder
  implicit val authContextDecoder: Decoder[AuthContext] = deriveDecoder

  // ObservabilityTap
  implicit val observabilityTapEncoder: Encoder[ObservabilityTap] = deriveEncoder
  implicit val observabilityTapDecoder: Decoder[ObservabilityTap] = deriveDecoder

  // RuntimePlan
  implicit val runtimePlanEncoder: Encoder[RuntimePlan] = deriveEncoder
  implicit val runtimePlanDecoder: Decoder[RuntimePlan] = deriveDecoder

  // ServiceSnapshot
  implicit val serviceSnapshotEncoder: Encoder[ServiceSnapshot] = deriveEncoder
  implicit val serviceSnapshotDecoder: Decoder[ServiceSnapshot] = deriveDecoder

  // RuntimeSnapshot
  implicit val runtimeSnapshotEncoder: Encoder[RuntimeSnapshot] = deriveEncoder
  implicit val runtimeSnapshotDecoder: Decoder[RuntimeSnapshot] = deriveDecoder

  // Observation
  implicit val observationEncoder: Encoder[Observation] = deriveEncoder
  implicit val observationDecoder: Decoder[Observation] = deriveDecoder

  // RequirementVerdict
  implicit val requirementVerdictEncoder: Encoder[RequirementVerdict] = deriveEncoder
  implicit val requirementVerdictDecoder: Decoder[RequirementVerdict] = deriveDecoder

  // RepairScope
  implicit val repairScopeEncoder: Encoder[RepairScope] = deriveEncoder
  implicit val repairScopeDecoder: Decoder[RepairScope] = deriveDecoder

  // ReproductionStep
  implicit val reproductionStepEncoder: Encoder[ReproductionStep] = deriveEncoder
  implicit val reproductionStepDecoder: Decoder[ReproductionStep] = deriveDecoder

  // SuspectedCause
  implicit val suspectedCauseEncoder: Encoder[SuspectedCause] = deriveEncoder
  implicit val suspectedCauseDecoder: Decoder[SuspectedCause] = deriveDecoder

  // FailurePacket
  implicit val failurePacketEncoder: Encoder[FailurePacket] = deriveEncoder
  implicit val failurePacketDecoder: Decoder[FailurePacket] = deriveDecoder

  // RerunPlan
  implicit val rerunPlanEncoder: Encoder[RerunPlan] = deriveEncoder
  implicit val rerunPlanDecoder: Decoder[RerunPlan] = deriveDecoder

  // ExecutionBudget
  implicit val executionBudgetEncoder: Encoder[ExecutionBudget] = deriveEncoder
  implicit val executionBudgetDecoder: Decoder[ExecutionBudget] = deriveDecoder

  // ArtifactRecord
  implicit val artifactRecordEncoder: Encoder[ArtifactRecord] = deriveEncoder
  implicit val artifactRecordDecoder: Decoder[ArtifactRecord] = deriveDecoder

  // PatchRecord
  implicit val patchRecordEncoder: Encoder[PatchRecord] = deriveEncoder
  implicit val patchRecordDecoder: Decoder[PatchRecord] = deriveDecoder

  // GraphWarning
  implicit val graphWarningEncoder: Encoder[GraphWarning] = deriveEncoder
  implicit val graphWarningDecoder: Decoder[GraphWarning] = deriveDecoder

  // DependencyEdge
  implicit val dependencyEdgeEncoder: Encoder[DependencyEdge] = deriveEncoder
  implicit val dependencyEdgeDecoder: Decoder[DependencyEdge] = deriveDecoder

  // RequirementNode
  implicit val requirementNodeEncoder: Encoder[RequirementNode] = deriveEncoder
  implicit val requirementNodeDecoder: Decoder[RequirementNode] = deriveDecoder

  // RequirementGraph
  implicit val requirementGraphEncoder: Encoder[RequirementGraph] = deriveEncoder
  implicit val requirementGraphDecoder: Decoder[RequirementGraph] = deriveDecoder

  // ScoredInference[T] (generic)
  implicit def scoredInferenceEncoder[T: Encoder]: Encoder[ScoredInference[T]] = deriveEncoder
  implicit def scoredInferenceDecoder[T: Decoder]: Decoder[ScoredInference[T]] = deriveDecoder

  // CandidateService
  implicit val candidateServiceEncoder: Encoder[CandidateService] = deriveEncoder
  implicit val candidateServiceDecoder: Decoder[CandidateService] = deriveDecoder

  // ManifestRef
  implicit val manifestRefEncoder: Encoder[ManifestRef] = deriveEncoder
  implicit val manifestRefDecoder: Decoder[ManifestRef] = deriveDecoder

  // ImpactMap
  implicit val impactMapEncoder: Encoder[ImpactMap] = deriveEncoder
  implicit val impactMapDecoder: Decoder[ImpactMap] = deriveDecoder

  // RepoInspectionReport
  implicit val repoInspectionReportEncoder: Encoder[RepoInspectionReport] = deriveEncoder
  implicit val repoInspectionReportDecoder: Decoder[RepoInspectionReport] = deriveDecoder

  // FilesystemPolicy
  implicit val filesystemPolicyEncoder: Encoder[FilesystemPolicy] = deriveEncoder
  implicit val filesystemPolicyDecoder: Decoder[FilesystemPolicy] = deriveDecoder

  // NetworkPolicy
  implicit val networkPolicyEncoder: Encoder[NetworkPolicy] = deriveEncoder
  implicit val networkPolicyDecoder: Decoder[NetworkPolicy] = deriveDecoder

  // BrowserPolicy
  implicit val browserPolicyEncoder: Encoder[BrowserPolicy] = deriveEncoder
  implicit val browserPolicyDecoder: Decoder[BrowserPolicy] = deriveDecoder

  // ToolPolicy
  implicit val toolPolicyEncoder: Encoder[ToolPolicy] = deriveEncoder
  implicit val toolPolicyDecoder: Decoder[ToolPolicy] = deriveDecoder

  // DestructiveActionPolicy
  implicit val destructiveActionPolicyEncoder: Encoder[DestructiveActionPolicy] = deriveEncoder
  implicit val destructiveActionPolicyDecoder: Decoder[DestructiveActionPolicy] = deriveDecoder

  // PolicySnapshot
  implicit val policySnapshotEncoder: Encoder[PolicySnapshot] = deriveEncoder
  implicit val policySnapshotDecoder: Decoder[PolicySnapshot] = deriveDecoder

  // UsageRecord
  implicit val usageRecordEncoder: Encoder[UsageRecord] = deriveEncoder
  implicit val usageRecordDecoder: Decoder[UsageRecord] = deriveDecoder

  // RepairUsageSummary
  implicit val repairUsageSummaryEncoder: Encoder[RepairUsageSummary] = deriveEncoder
  implicit val repairUsageSummaryDecoder: Decoder[RepairUsageSummary] = deriveDecoder

  // RepairSessionConfig
  implicit val repairSessionConfigEncoder: Encoder[RepairSessionConfig] = deriveEncoder
  implicit val repairSessionConfigDecoder: Decoder[RepairSessionConfig] = deriveDecoder

  // RepairSessionHandle
  implicit val repairSessionHandleEncoder: Encoder[RepairSessionHandle] = deriveEncoder
  implicit val repairSessionHandleDecoder: Decoder[RepairSessionHandle] = deriveDecoder

  // RepairOutputContract
  implicit val repairOutputContractEncoder: Encoder[RepairOutputContract] = deriveEncoder
  implicit val repairOutputContractDecoder: Decoder[RepairOutputContract] = deriveDecoder

  // RequirementSummary
  implicit val requirementSummaryEncoder: Encoder[RequirementSummary] = deriveEncoder
  implicit val requirementSummaryDecoder: Decoder[RequirementSummary] = deriveDecoder

  // ScopedArtifactRef
  implicit val scopedArtifactRefEncoder: Encoder[ScopedArtifactRef] = deriveEncoder
  implicit val scopedArtifactRefDecoder: Decoder[ScopedArtifactRef] = deriveDecoder

  // PriorAttemptSummary
  implicit val priorAttemptSummaryEncoder: Encoder[PriorAttemptSummary] = deriveEncoder
  implicit val priorAttemptSummaryDecoder: Decoder[PriorAttemptSummary] = deriveDecoder

  // GenerationMode
  implicit val generationModeEncoder: Encoder[GenerationMode] = simpleEnumEncoder(_.toString)
  implicit val generationModeDecoder: Decoder[GenerationMode] = simpleEnumDecoder(s =>
    GenerationMode.values.find(_.toString == s), "GenerationMode")

  // PlannedFile
  implicit val plannedFileEncoder: Encoder[PlannedFile] = deriveEncoder
  implicit val plannedFileDecoder: Decoder[PlannedFile] = deriveDecoder

  // PlannedModification
  implicit val plannedModificationEncoder: Encoder[PlannedModification] = deriveEncoder
  implicit val plannedModificationDecoder: Decoder[PlannedModification] = deriveDecoder

  // FeaturePlan
  implicit val featurePlanEncoder: Encoder[FeaturePlan] = deriveEncoder
  implicit val featurePlanDecoder: Decoder[FeaturePlan] = deriveDecoder

  // RepairRequest
  implicit val repairRequestEncoder: Encoder[RepairRequest] = deriveEncoder
  implicit val repairRequestDecoder: Decoder[RepairRequest] = deriveDecoder

  // RepairResult
  implicit val repairResultEncoder: Encoder[RepairResult] = deriveEncoder
  implicit val repairResultDecoder: Decoder[RepairResult] = deriveDecoder

  // InferenceRequest
  implicit val inferenceRequestEncoder: Encoder[InferenceRequest] = deriveEncoder
  implicit val inferenceRequestDecoder: Decoder[InferenceRequest] = deriveDecoder

  // InferenceResponse
  implicit val inferenceResponseEncoder: Encoder[InferenceResponse] = deriveEncoder
  implicit val inferenceResponseDecoder: Decoder[InferenceResponse] = deriveDecoder

  // InferenceBudgetStatus
  implicit val inferenceBudgetStatusEncoder: Encoder[InferenceBudgetStatus] = deriveEncoder
  implicit val inferenceBudgetStatusDecoder: Decoder[InferenceBudgetStatus] = deriveDecoder

  // SystemEvent
  implicit val systemEventEncoder: Encoder[SystemEvent] = deriveEncoder
  implicit val systemEventDecoder: Decoder[SystemEvent] = deriveDecoder

  // TaskRun
  implicit val taskRunEncoder: Encoder[TaskRun] = deriveEncoder
  implicit val taskRunDecoder: Decoder[TaskRun] = deriveDecoder

  // Attempt
  implicit val attemptEncoder: Encoder[Attempt] = deriveEncoder
  implicit val attemptDecoder: Decoder[Attempt] = deriveDecoder

  // VerifierResult
  implicit val verifierResultEncoder: Encoder[VerifierResult] = deriveEncoder
  implicit val verifierResultDecoder: Decoder[VerifierResult] = deriveDecoder

  // RequirementResult
  implicit val requirementResultEncoder: Encoder[RequirementResult] = deriveEncoder
  implicit val requirementResultDecoder: Decoder[RequirementResult] = deriveDecoder

  // AttemptReportSummary
  implicit val attemptReportSummaryEncoder: Encoder[AttemptReportSummary] = deriveEncoder
  implicit val attemptReportSummaryDecoder: Decoder[AttemptReportSummary] = deriveDecoder

  // FlakyVerifierReport
  implicit val flakyVerifierReportEncoder: Encoder[FlakyVerifierReport] = deriveEncoder
  implicit val flakyVerifierReportDecoder: Decoder[FlakyVerifierReport] = deriveDecoder

  // AggregateUsage
  implicit val aggregateUsageEncoder: Encoder[AggregateUsage] = deriveEncoder
  implicit val aggregateUsageDecoder: Decoder[AggregateUsage] = deriveDecoder

  // FinalReport
  implicit val finalReportEncoder: Encoder[FinalReport] = deriveEncoder
  implicit val finalReportDecoder: Decoder[FinalReport] = deriveDecoder

  // VerifierLayer
  implicit val verifierLayerEncoder: Encoder[VerifierLayer] = deriveEncoder
  implicit val verifierLayerDecoder: Decoder[VerifierLayer] = deriveDecoder

  // ParallelGroup
  implicit val parallelGroupEncoder: Encoder[ParallelGroup] = deriveEncoder
  implicit val parallelGroupDecoder: Decoder[ParallelGroup] = deriveDecoder

  // OrderedVerifierPlan
  implicit val orderedVerifierPlanEncoder: Encoder[OrderedVerifierPlan] = deriveEncoder
  implicit val orderedVerifierPlanDecoder: Decoder[OrderedVerifierPlan] = deriveDecoder

  // PromptPackageArtifact
  implicit val promptPackageArtifactEncoder: Encoder[PromptPackageArtifact] = deriveEncoder
  implicit val promptPackageArtifactDecoder: Decoder[PromptPackageArtifact] = deriveDecoder
}
