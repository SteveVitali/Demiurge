package demiurge.repair

import demiurge.model._

// Spec §2.3: Trait for building repair/generation prompts.
// Implementations: ClaudePromptBuilder. Allows InferenceBackedRepairBackend
// to be prompt-builder-agnostic.
trait RepairPromptBuilder {
  def buildSystemPrompt(mode: GenerationMode = GenerationMode.Repair): String
  def buildUserPrompt(packet: FailurePacket, context: RepairContext): String
  // Spec §10.1: Build prompt from a RepairRequest (session-based interface)
  def buildRepairRequestPrompt(request: RepairRequest): String
}
