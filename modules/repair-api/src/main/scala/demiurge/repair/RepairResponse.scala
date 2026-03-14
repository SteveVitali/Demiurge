package demiurge.repair

// Phase 5: RepairResponse — result of a repair backend invocation.
sealed trait RepairResponse
object RepairResponse {
  case class Success(patch: PatchProposal) extends RepairResponse
  case class Failed(reason: String) extends RepairResponse
  case class InvalidPatch(reason: String) extends RepairResponse
}
