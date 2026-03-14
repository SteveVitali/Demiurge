package demiurge.model

import munit.FunSuite
import io.circe._
import io.circe.syntax._
import io.circe.parser._
import demiurge.model.JsonCodecs._

class EnumsSuite extends FunSuite {

  private def roundTrip[A: Encoder: Decoder](value: A, expectedJson: String): Unit = {
    val json = value.asJson.noSpaces
    assertEquals(json, expectedJson)
    val decoded = decode[A](json)
    assertEquals(decoded, Right(value))
  }

  // Spec §3.1: RunStatus — 21 values
  test("RunStatus serialization round-trip") {
    roundTrip[RunStatus](RunStatus.Created, "\"Created\"")
    roundTrip[RunStatus](RunStatus.Verifying, "\"Verifying\"")
    roundTrip[RunStatus](RunStatus.Succeeded, "\"Succeeded\"")
    roundTrip[RunStatus](RunStatus.Interrupted, "\"Interrupted\"")
    assertEquals(RunStatus.values.size, 21)
  }

  // Spec §3.1: AttemptStatus — 10 values
  test("AttemptStatus serialization round-trip") {
    roundTrip[AttemptStatus](AttemptStatus.Created, "\"Created\"")
    roundTrip[AttemptStatus](AttemptStatus.Verifying, "\"Verifying\"")
    roundTrip[AttemptStatus](AttemptStatus.Aborted, "\"Aborted\"")
    assertEquals(AttemptStatus.values.size, 10)
  }

  // Spec §3.1: ServiceStatus — 7 values
  test("ServiceStatus serialization round-trip") {
    roundTrip[ServiceStatus](ServiceStatus.Pending, "\"Pending\"")
    roundTrip[ServiceStatus](ServiceStatus.RunningHealthy, "\"RunningHealthy\"")
    roundTrip[ServiceStatus](ServiceStatus.Restarting, "\"Restarting\"")
    assertEquals(ServiceStatus.values.size, 7)
  }

  // Spec §3.1: EnvironmentStatus — 8 values
  test("EnvironmentStatus serialization round-trip") {
    roundTrip[EnvironmentStatus](EnvironmentStatus.Planned, "\"Planned\"")
    roundTrip[EnvironmentStatus](EnvironmentStatus.Ready, "\"Ready\"")
    roundTrip[EnvironmentStatus](EnvironmentStatus.Stopped, "\"Stopped\"")
    assertEquals(EnvironmentStatus.values.size, 8)
  }

  // Spec §3.1: RequirementPriority — 3 values
  test("RequirementPriority serialization round-trip") {
    roundTrip[RequirementPriority](RequirementPriority.Required, "\"Required\"")
    roundTrip[RequirementPriority](RequirementPriority.Important, "\"Important\"")
    roundTrip[RequirementPriority](RequirementPriority.NiceToHave, "\"NiceToHave\"")
    assertEquals(RequirementPriority.values.size, 3)
  }

  // Spec §3.1: RequirementCategory — 8 values
  test("RequirementCategory serialization round-trip") {
    roundTrip[RequirementCategory](RequirementCategory.UiFlow, "\"UiFlow\"")
    roundTrip[RequirementCategory](RequirementCategory.ApiContract, "\"ApiContract\"")
    roundTrip[RequirementCategory](RequirementCategory.EnvironmentReadiness, "\"EnvironmentReadiness\"")
    assertEquals(RequirementCategory.values.size, 8)
  }

  // Spec §3.1: VerdictStatus — 6 values
  test("VerdictStatus serialization round-trip") {
    roundTrip[VerdictStatus](VerdictStatus.Pass, "\"Pass\"")
    roundTrip[VerdictStatus](VerdictStatus.Fail, "\"Fail\"")
    roundTrip[VerdictStatus](VerdictStatus.Flake, "\"Flake\"")
    assertEquals(VerdictStatus.values.size, 6)
  }

  // Spec §3.1: VerifierType — 9 values
  test("VerifierType serialization round-trip") {
    roundTrip[VerifierType](VerifierType.EnvironmentReadiness, "\"EnvironmentReadiness\"")
    roundTrip[VerifierType](VerifierType.BrowserFlow, "\"BrowserFlow\"")
    roundTrip[VerifierType](VerifierType.TargetedRegression, "\"TargetedRegression\"")
    assertEquals(VerifierType.values.size, 9)
  }

  // Spec §3.1: FailureClass — 18 values
  test("FailureClass serialization round-trip") {
    roundTrip[FailureClass](FailureClass.EnvironmentBootFailure, "\"EnvironmentBootFailure\"")
    roundTrip[FailureClass](FailureClass.LocatorDrift, "\"LocatorDrift\"")
    roundTrip[FailureClass](FailureClass.UnknownFailure, "\"UnknownFailure\"")
    assertEquals(FailureClass.values.size, 18)
  }

  // Spec §3.1: ServiceKind — 7 values
  test("ServiceKind serialization round-trip") {
    roundTrip[ServiceKind](ServiceKind.Frontend, "\"Frontend\"")
    roundTrip[ServiceKind](ServiceKind.Api, "\"Api\"")
    roundTrip[ServiceKind](ServiceKind.ExternalMock, "\"ExternalMock\"")
    assertEquals(ServiceKind.values.size, 7)
  }

  // Spec §3.1: StartupMode — 4 values
  test("StartupMode serialization round-trip") {
    roundTrip[StartupMode](StartupMode.ComposeNative, "\"ComposeNative\"")
    roundTrip[StartupMode](StartupMode.ScriptNative, "\"ScriptNative\"")
    roundTrip[StartupMode](StartupMode.Hybrid, "\"Hybrid\"")
    assertEquals(StartupMode.values.size, 4)
  }

  // Spec §3.1: AuthMode — 5 values
  test("AuthMode serialization round-trip") {
    roundTrip[AuthMode](AuthMode.BrowserFormLogin, "\"BrowserFormLogin\"")
    roundTrip[AuthMode](AuthMode.ApiLogin, "\"ApiLogin\"")
    roundTrip[AuthMode](AuthMode.DevBypassHeader, "\"DevBypassHeader\"")
    assertEquals(AuthMode.values.size, 5)
  }

  // Spec §3.1: ArtifactType — 24 values
  test("ArtifactType serialization round-trip") {
    roundTrip[ArtifactType](ArtifactType.Plan, "\"Plan\"")
    roundTrip[ArtifactType](ArtifactType.Screenshot, "\"Screenshot\"")
    roundTrip[ArtifactType](ArtifactType.AttemptReport, "\"AttemptReport\"")
    assertEquals(ArtifactType.values.size, 24)
  }

  // Spec §3.1: RunMode — 4 values
  test("RunMode serialization round-trip") {
    roundTrip[RunMode](RunMode.Full, "\"Full\"")
    roundTrip[RunMode](RunMode.PlanOnly, "\"PlanOnly\"")
    roundTrip[RunMode](RunMode.RepairOnly, "\"RepairOnly\"")
    assertEquals(RunMode.values.size, 4)
  }

  // Spec §3.1: ResetStrategy — 3 values
  test("ResetStrategy serialization round-trip") {
    roundTrip[ResetStrategy](ResetStrategy.SoftReset, "\"SoftReset\"")
    roundTrip[ResetStrategy](ResetStrategy.HardReset, "\"HardReset\"")
    roundTrip[ResetStrategy](ResetStrategy.FullRebuild, "\"FullRebuild\"")
    assertEquals(ResetStrategy.values.size, 3)
  }

  // Spec §3.1: InferenceProvider — 4 values
  test("InferenceProvider serialization round-trip") {
    roundTrip[InferenceProvider](InferenceProvider.Anthropic, "\"Anthropic\"")
    roundTrip[InferenceProvider](InferenceProvider.OpenAI, "\"OpenAI\"")
    roundTrip[InferenceProvider](InferenceProvider.Mock, "\"Mock\"")
    assertEquals(InferenceProvider.values.size, 4)
  }

  // Spec §3.1: DependencyEdgeType — 3 values
  test("DependencyEdgeType serialization round-trip") {
    roundTrip[DependencyEdgeType](DependencyEdgeType.Hard, "\"Hard\"")
    roundTrip[DependencyEdgeType](DependencyEdgeType.Soft, "\"Soft\"")
    roundTrip[DependencyEdgeType](DependencyEdgeType.Ordering, "\"Ordering\"")
    assertEquals(DependencyEdgeType.values.size, 3)
  }

  // Spec §3.1: RepairResultStatus — 6 values
  test("RepairResultStatus serialization round-trip") {
    roundTrip[RepairResultStatus](RepairResultStatus.Success, "\"Success\"")
    roundTrip[RepairResultStatus](RepairResultStatus.PartialFix, "\"PartialFix\"")
    roundTrip[RepairResultStatus](RepairResultStatus.Cancelled, "\"Cancelled\"")
    assertEquals(RepairResultStatus.values.size, 6)
  }

  // Spec §3.1: RepairBackendError ADT — 9 variants
  test("RepairBackendError ADT serialization round-trip") {
    val err1: RepairBackendError = RepairBackendError.SessionCreationFailed("conn refused")
    val json1 = err1.asJson
    assertEquals(json1.hcursor.get[String]("type"), Right("SessionCreationFailed"))
    assertEquals(decode[RepairBackendError](json1.noSpaces), Right(err1))

    val err2: RepairBackendError = RepairBackendError.EmptyPatch
    val json2 = err2.asJson
    assertEquals(json2.hcursor.get[String]("type"), Right("EmptyPatch"))
    assertEquals(decode[RepairBackendError](json2.noSpaces), Right(err2))

    val err3: RepairBackendError = RepairBackendError.BudgetExceeded(150000L, 200000L)
    val json3 = err3.asJson
    assertEquals(json3.hcursor.get[String]("type"), Right("BudgetExceeded"))
    assertEquals(decode[RepairBackendError](json3.noSpaces), Right(err3))
  }

  // Spec §3.1: InferenceError ADT — 6 variants
  test("InferenceError ADT serialization round-trip") {
    val err1: InferenceError = InferenceError.Timeout("req-1", 5000L)
    val json1 = err1.asJson
    assertEquals(json1.hcursor.get[String]("type"), Right("Timeout"))
    assertEquals(decode[InferenceError](json1.noSpaces), Right(err1))

    val err2: InferenceError = InferenceError.RateLimited("req-2", 10000L)
    assertEquals(decode[InferenceError](err2.asJson.noSpaces), Right(err2))

    val err3: InferenceError = InferenceError.SchemaValidationFailed("req-3", "{}", List("err1", "err2"))
    assertEquals(decode[InferenceError](err3.asJson.noSpaces), Right(err3))
  }

  // Spec §3.1: WorkerTaskStatus — 5 values
  test("WorkerTaskStatus serialization round-trip") {
    roundTrip[WorkerTaskStatus](WorkerTaskStatus.Pass, "\"Pass\"")
    roundTrip[WorkerTaskStatus](WorkerTaskStatus.Fail, "\"Fail\"")
    roundTrip[WorkerTaskStatus](WorkerTaskStatus.Error, "\"Error\"")
    assertEquals(WorkerTaskStatus.values.size, 5)
  }
}
