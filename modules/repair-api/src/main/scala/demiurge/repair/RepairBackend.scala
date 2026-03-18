package demiurge.repair

import java.time.Instant
import java.util.UUID

import demiurge.model._

// Spec §10.1: RepairBackend trait — session-based interface for repair backends.
// Implementations manage a session lifecycle: prepare → submit → (cancel/getUsage) → close.
// The `proposePatch` method is retained as a convenience that wraps the full session lifecycle.
trait RepairBackend {

  // Spec §10.1: Prepare a repair session with policy and budget constraints.
  def prepareSession(config: RepairSessionConfig): Either[RepairBackendError, RepairSessionHandle] =
    Right(RepairSessionHandle(UUID.randomUUID().toString, backendId, Instant.now()))

  // Spec §10.1: Submit a repair task within a prepared session.
  def submitRepairTask(handle: RepairSessionHandle, request: RepairRequest): Either[RepairBackendError, RepairResult] =
    Left(RepairBackendError.TaskSubmissionFailed("Not implemented"))

  // Spec §10.1: Cancel an in-progress repair session.
  def cancel(handle: RepairSessionHandle): Unit = ()

  // Spec §10.1: Get usage summary for a session.
  def getUsage(handle: RepairSessionHandle): RepairUsageSummary =
    RepairUsageSummary(0L, 0L, 0L, 0L, 0, None)

  // Spec §10.1: Close a session and release resources.
  def closeSession(handle: RepairSessionHandle): Unit = ()

  // Spec §10.1: Backend identifier string.
  def backendId: String = "unknown"

  // Convenience: single-call repair that wraps full session lifecycle.
  // Retained for backward compatibility with RepairSession and RepairExecutor.
  def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse
}
