package lastmile.repair

import lastmile.model.FailurePacket

// Phase 5: RepairBackend trait — sync interface for repair backends.
// Implementations receive a FailurePacket and return a PatchProposal.
// No streaming. No async.
trait RepairBackend {
  def proposePatch(packet: FailurePacket, context: RepairContext): RepairResponse
}
