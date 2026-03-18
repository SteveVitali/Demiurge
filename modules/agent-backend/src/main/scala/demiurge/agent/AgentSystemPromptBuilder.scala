package demiurge.agent

import demiurge.model._
import demiurge.repair.{RepairContext, PatchProposal}

// Design §7: Agent system prompt builder.
// Replaces ClaudePromptBuilder for agent-backed repairs. Key difference:
// NO file contents are embedded — the agent reads files itself.
// Provides task context, requirements, verdicts, patch history, and MCP tool descriptions.
object AgentSystemPromptBuilder {

  /**
   * Build the system prompt for the agent.
   * This is the primary instruction set that guides the agent's behavior.
   */
  def buildSystemPrompt(context: RepairContext): String = {
    val sb = new StringBuilder

    sb.append("You are a code repair agent working inside a git worktree managed by Demiurge,\n")
    sb.append("a verification-first code automation system.\n\n")

    // Task
    sb.append("## Your Task\n")
    sb.append(context.taskText).append("\n\n")

    // Generation mode
    sb.append("## Generation Mode\n")
    context.generationMode match {
      case GenerationMode.Repair       => sb.append("Repair\n\n")
      case GenerationMode.InitialBuild => sb.append("InitialBuild\n\n")
    }

    // Working directory
    sb.append("## Working Directory\n")
    sb.append(context.worktreePath.toAbsolutePath.toString).append("\n")
    sb.append("All file paths are relative to this directory. You may read and edit any file here.\n\n")

    // Requirements
    appendRequirements(sb, context.graph)

    // Current verification status (for Repair mode)
    if (context.generationMode == GenerationMode.Repair && context.verdicts.nonEmpty) {
      appendVerificationStatus(sb, context.verdicts)
    }

    // Feature plan (for InitialBuild mode)
    context.featurePlan.foreach { plan =>
      appendFeaturePlan(sb, plan)
    }

    // Patch history
    if (context.patchHistory.nonEmpty) {
      appendPatchHistory(sb, context.patchHistory)
    }

    // Service logs
    context.logs.foreach { logs =>
      sb.append("## Service Logs\n")
      sb.append(logs.take(10000)).append("\n\n") // cap at 10K chars
    }

    // Available tools
    appendToolDescriptions(sb)

    // Instructions
    appendInstructions(sb, context.generationMode)

    sb.toString()
  }

  /**
   * Build the user prompt — a concise action trigger.
   * The system prompt has all the context; the user prompt is just the kickoff.
   */
  def buildUserPrompt(context: RepairContext): String = {
    context.generationMode match {
      case GenerationMode.Repair =>
        val failCount = context.verdicts.count(v =>
          v.status == VerdictStatus.Fail || v.status == VerdictStatus.Inconclusive)
        s"$failCount requirement(s) are failing verification. " +
          "Read the relevant source files, identify the root cause, fix the code, " +
          "restart affected services, and run verify_requirements() to confirm."

      case GenerationMode.InitialBuild =>
        s"Implement the feature described above. " +
          "Read the existing codebase to understand the project structure, " +
          "create the necessary files, install any dependencies, " +
          "then run verify_requirements() to confirm everything passes."
    }
  }

  private def appendRequirements(sb: StringBuilder, graph: RequirementGraph): Unit = {
    sb.append("## Requirements\n")
    sb.append("The following requirements must pass verification:\n\n")
    graph.nodes.foreach { req =>
      sb.append(s"- [${req.requirementId}] ${req.humanDescription}\n")
      sb.append(s"  Category: ${req.category}, Priority: ${req.priority}\n")
      req.verifiers.foreach { v =>
        sb.append(s"  Verifier: ${v.verifierType} — ${v.displayName}\n")
      }
    }
    sb.append("\n")
  }

  private def appendVerificationStatus(sb: StringBuilder, verdicts: List[RequirementVerdict]): Unit = {
    val failed = verdicts.filter(v =>
      v.status == VerdictStatus.Fail || v.status == VerdictStatus.Inconclusive)

    if (failed.nonEmpty) {
      sb.append("## Current Verification Status\n")
      sb.append("The following requirements FAILED verification:\n\n")
      failed.foreach { v =>
        sb.append(s"- [${v.requirementId}] Status: ${v.status}\n")
        sb.append(s"  Failure: ${v.failureMessage.getOrElse("No details")}\n")
      }
      sb.append("\n")
    }
  }

  private def appendFeaturePlan(sb: StringBuilder, plan: FeaturePlan): Unit = {
    sb.append("## Feature Plan\n")
    sb.append(plan.summary).append("\n\n")

    if (plan.filesToCreate.nonEmpty) {
      sb.append("Files to create:\n")
      plan.filesToCreate.foreach { f =>
        sb.append(s"  - ${f.relativePath}: ${f.description}\n")
      }
    }
    if (plan.filesToModify.nonEmpty) {
      sb.append("Files to modify:\n")
      plan.filesToModify.foreach { f =>
        sb.append(s"  - ${f.relativePath}: ${f.description}\n")
      }
    }
    if (plan.requiresNewDeps.nonEmpty) {
      sb.append(s"New dependencies: ${plan.requiresNewDeps.mkString(", ")}\n")
    }
    sb.append("\n")
  }

  private def appendPatchHistory(sb: StringBuilder, patches: List[PatchProposal]): Unit = {
    sb.append("## Prior Repair Attempts\n")
    patches.foreach { patch =>
      sb.append(s"- Attempt ${patch.attemptNumber}: ${patch.summary}\n")
      sb.append(s"  Files changed: ${patch.filesChanged.mkString(", ")}\n")
    }
    sb.append("\n")
  }

  private def appendToolDescriptions(sb: StringBuilder): Unit = {
    sb.append("## Available Tools\n")
    sb.append("In addition to standard file and shell tools, you have access to Demiurge-specific\n")
    sb.append("MCP tools:\n\n")
    sb.append("- **verify_requirements()**: Run the verification suite to check your work.\n")
    sb.append("  Call this after making changes to confirm fixes before finishing.\n")
    sb.append("- **get_service_logs(serviceId)**: Get recent server logs to see errors/stack traces.\n")
    sb.append("- **restart_service(serviceId)**: Restart a service after code changes.\n")
    sb.append("- **get_requirement_details(requirementId)**: Get full details of a requirement.\n")
    sb.append("- **check_service_health()**: Check health status of all running services.\n\n")
  }

  private def appendInstructions(sb: StringBuilder, mode: GenerationMode): Unit = {
    sb.append("## Instructions\n")
    mode match {
      case GenerationMode.Repair =>
        sb.append("1. Read the relevant source files to understand the codebase structure.\n")
        sb.append("2. Identify the root cause of the failing requirement(s).\n")
        sb.append("3. Make the minimal changes needed to fix the failures.\n")
        sb.append("4. After editing, restart any affected services using restart_service().\n")
        sb.append("5. Run verify_requirements() to confirm your fixes work.\n")
        sb.append("6. If verification still fails, read the new failure details and iterate.\n")
        sb.append("7. Only declare done when verify_requirements() returns all Pass verdicts\n")
        sb.append("   (or you've exhausted reasonable approaches and need to explain why).\n")

      case GenerationMode.InitialBuild =>
        sb.append("1. Read existing source files to understand the project structure.\n")
        sb.append("2. Create all necessary files according to the feature plan.\n")
        sb.append("3. Install any required dependencies.\n")
        sb.append("4. Restart affected services using restart_service().\n")
        sb.append("5. Run verify_requirements() to confirm the implementation passes.\n")
        sb.append("6. If verification fails, read the failure details and iterate.\n")
        sb.append("7. Only declare done when verify_requirements() returns all Pass verdicts.\n")
    }
  }
}
