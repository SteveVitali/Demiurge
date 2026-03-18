package demiurge.repair

import java.nio.file.Path

import demiurge.model._

// Phase 5: RepairExecutor — orchestrates a single repair attempt.
// 1. Builds FailurePacket from verification results
// 2. Calls RepairBackend.proposePatch
// 3. Applies patch to worktree via PatchApplier
// Returns the result of the repair attempt.
object RepairExecutor {

  sealed trait RepairOutcome
  case class RepairApplied(
    packet: FailurePacket,
    proposal: PatchProposal,
    filesChanged: List[String],
  ) extends RepairOutcome
  case class RepairRejected(
    packet: FailurePacket,
    reason: String,
  ) extends RepairOutcome

  def executeRepair(
    backend: RepairBackend,
    worktreePath: Path,
    input: FailurePacketBuilder.FailurePacketInput,
    context: RepairContext,
  ): RepairOutcome = {
    // Step 1: Build failure packet
    val packet = FailurePacketBuilder.build(input)

    // Step 2: Call repair backend
    val response = backend.proposePatch(packet, context)

    response match {
      case RepairResponse.Success(proposal) =>
        if (proposal.isEmpty) {
          return RepairRejected(packet, "Repair backend returned empty patch")
        }
        // Step 3: Apply patch to worktree
        PatchApplier.apply(proposal, worktreePath) match {
          case PatchApplier.ApplySuccess(filesChanged) =>
            RepairApplied(packet, proposal, filesChanged)
          case PatchApplier.ApplyFailure(reason) =>
            RepairRejected(packet, s"Patch application failed: $reason")
        }

      case RepairResponse.Failed(reason) =>
        RepairRejected(packet, s"Repair backend failed: $reason")

      case RepairResponse.InvalidPatch(reason) =>
        RepairRejected(packet, s"Invalid patch from repair backend: $reason")
    }
  }
}
