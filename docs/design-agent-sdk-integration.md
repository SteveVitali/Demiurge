# Design: Claude Agent SDK Integration — Replacing Bespoke Repair with Agentic Coding

**Status:** Draft
**Date:** 2026-03-18
**Branch:** (to be created)
**Depends on:** close-all-spec-gaps (all E2E fixes merged)

This document specifies the architectural shift from Demiurge's bespoke single-shot LLM repair/build pipeline to delegating code generation and repair to the **Claude Agent SDK** — giving the system the same capabilities as Claude Code (file read/write, shell commands, iterative self-correction) while Demiurge retains ownership of verification, environment management, and orchestration.

---

## Table of Contents

1. [Motivation](#1-motivation)
2. [Architecture Overview](#2-architecture-overview)
3. [Current Architecture (What Changes)](#3-current-architecture-what-changes)
4. [Target Architecture](#4-target-architecture)
5. [Integration Surfaces](#5-integration-surfaces)
6. [MCP Server: Demiurge Verification Tools](#6-mcp-server-demiurge-verification-tools)
7. [Agent System Prompt Design](#7-agent-system-prompt-design)
8. [Orchestrator Changes](#8-orchestrator-changes)
9. [Module-by-Module Impact](#9-module-by-module-impact)
10. [New Module: agent-backend](#10-new-module-agent-backend)
11. [Session Management & Budget Tracking](#11-session-management--budget-tracking)
12. [Worktree & Environment Interaction](#12-worktree--environment-interaction)
13. [Error Handling & Fallback](#13-error-handling--fallback)
14. [Configuration](#14-configuration)
15. [Migration Path](#15-migration-path)
16. [Implementation Plan](#16-implementation-plan)
17. [Testing Strategy](#17-testing-strategy)
18. [Open Questions](#18-open-questions)

---

## 1. Motivation

### 1.1 The Problem

Demiurge's current repair/build pipeline is a bespoke single-shot prompt-and-apply system with fundamental limitations:

1. **Shallow feedback loop.** When a patch fails verification, the next LLM attempt receives only a one-line summary and list of changed files. It never sees server-side error logs, stack traces, compilation errors, or the specific HTTP response that didn't match.

2. **Crude file selection.** `ClaudePromptBuilder.collectRelevantFiles` uses keyword matching against file content, capped at 8 files × 5KB. It misses transitive dependencies, can't follow import graphs, and drops large files entirely. The LLM is forced to work with an incomplete, heuristically-selected subset of the codebase.

3. **No pre-verification sanity check.** The pipeline generates code → applies patch → boots entire environment → runs HTTP verifiers → discovers a syntax error. Each wasted attempt burns tokens, wall-clock time, and one of the limited `maxAttempts`.

4. **No iterative self-correction.** Each repair is one prompt → one response. The LLM can't read additional files, run `tsc --noEmit`, `npm test`, grep for usages, or validate its own work before submitting.

5. **Fragile patch application.** `PatchApplier` implements find-and-replace with fuzzy whitespace matching fallback. This is inherently fragile — the LLM must reproduce `oldContent` character-for-character from a subset of files it was shown, without being able to verify the match.

6. **No dependency management.** If the LLM generates code requiring a new npm package, there's no mechanism to detect or install it. The server will crash on boot.

7. **Language/ecosystem hardcoding.** Worktree setup assumes Node.js (.env copy, node_modules symlink). The prompt builder hardcodes file extension lists.

### 1.2 The Insight

Claude Code / the Claude Agent SDK already solves all of these problems. It provides:

- **File read/write** — reads any file on demand, following imports; edits files directly with no patch format
- **Shell execution** — runs `tsc`, `npm test`, `npm install`, `curl`, `grep`, etc.
- **Multi-turn iteration** — sees errors and fixes them within the same session
- **Context management** — intelligent window management across large codebases
- **Self-validation** — can run the code, check output, and iterate before declaring done

### 1.3 The Architectural Reframe

**Demiurge's unique value is not code generation.** It is the verification-first orchestration layer:

- Requirements compilation (task string → structured, verifiable requirement graph)
- Automated verification (HTTP probes, TCP checks, browser flows, state assertions)
- Environment management (worktrees, service lifecycle, process supervision)
- The outer loop ("keep trying until all verifiers pass or budget is exhausted")
- Persistence and resume (crash-tolerant state machine with SQLite WAL)

The clean separation:

> **Demiurge = the judge** — defines requirements, runs verification, manages the environment, decides pass/fail
>
> **Claude Agent SDK = the developer** — reads code, writes code, runs tests, installs deps, iterates until done

The product becomes: **Claude Code + automated acceptance testing, running in a loop until it passes.**

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     RunOrchestrator (Scala)                      │
│  State Machine: Created → Inspect → Compile → Plan → Boot →     │
│  [Verify → Agent Repair Loop → Re-verify] → Succeeded/Exhausted │
└──────────────┬──────────────────────────────────┬───────────────┘
               │                                  │
               │ invoke                           │ expose
               ▼                                  ▼
┌──────────────────────────┐    ┌──────────────────────────────────┐
│   Claude Agent SDK       │    │   Demiurge MCP Server            │
│   (CLI subprocess or     │◄──►│   (verification + env tools)     │
│    TypeScript SDK)       │    │                                  │
│                          │    │   Tools:                         │
│   Built-in tools:        │    │   - verify_requirements()        │
│   - Read files           │    │   - get_service_logs(serviceId)  │
│   - Edit files           │    │   - restart_service(serviceId)   │
│   - Run bash commands    │    │   - get_verification_results()   │
│   - Grep/search          │    │   - get_requirement_details(id)  │
│                          │    │   - check_service_health()       │
│   CWD: worktree path     │    │                                  │
└──────────────────────────┘    └──────────────────────────────────┘
```

---

## 3. Current Architecture (What Changes)

### 3.1 Current Repair Flow (files and call chain)

```
RunOrchestrator.execute()
  └─ Verification fails
     └─ RepairSession.executeWithSession()
        ├─ FailurePacketBuilder.build()           → FailurePacket
        ├─ RepairBackend.proposePatch()           → RepairResponse
        │   └─ ClaudeRepairBackend.proposePatch()
        │      ├─ ClaudePromptBuilder.buildSystemPrompt()
        │      ├─ ClaudePromptBuilder.buildUserPrompt()
        │      │   ├─ extractFailureKeywords()    → Set[String]
        │      │   └─ collectRelevantFiles()      → 8 files × 5KB
        │      ├─ ClaudeClient.sendMessage()      → 1 API call
        │      └─ RepairResponseParser.parsePatchProposal() → PatchProposal
        └─ PatchApplier.apply()                   → edit files in worktree
```

### 3.2 Key Files in Current Pipeline

| File | LOC | Role | Fate |
|------|-----|------|------|
| `repair-claude/ClaudePromptBuilder.scala` | 337 | Builds prompts, collects files, keyword scoring | **Replace**: system prompt builder only |
| `repair-claude/ClaudeClient.scala` | 142 | Raw HTTP client for Claude API | **Eliminate**: Agent SDK handles API calls |
| `repair-claude/ClaudeRepairBackend.scala` | 100 | RepairBackend impl using ClaudeClient | **Replace**: new AgentBackend |
| `repair-api/PatchApplier.scala` | 122 | Find-and-replace with fuzzy matching | **Eliminate**: Agent edits files directly |
| `repair-api/RepairExecutor.scala` | 58 | Orchestrates single repair: build packet → call backend → apply patch | **Replace**: AgentExecutor (invoke agent, wait, check results) |
| `repair-api/RepairSession.scala` | 188 | Session lifecycle, transcript, commit SHA tracking | **Simplify**: session wraps agent invocation instead of prompt+patch |
| `repair-api/InferenceBackedRepairBackend.scala` | 156 | RepairBackend using InferenceService | **Replace**: agent backend replaces both |
| `repair-api/RepairBackend.scala` | 38 | Trait: `proposePatch(FailurePacket, RepairContext) → RepairResponse` | **Redesign**: new `AgentBackend` trait |
| `repair-api/RepairContext.scala` | 26 | Context DTO (worktreePath, verdicts, graph, patchHistory, etc.) | **Evolve**: becomes agent system prompt context |
| `repair-api/FailurePacketBuilder.scala` | ~80 | Builds failure packet from verdicts | **Keep**: still needed for system prompt context |
| `orchestrator/RunOrchestrator.scala` | 761 | State machine, repair loop at lines 521-760 | **Modify**: repair section invokes AgentBackend |
| `orchestrator/BuildPhaseManager.scala` | 297 | PlanningFeature + GeneratingCode phases | **Modify**: GeneratingCode invokes AgentBackend |

### 3.3 Current Data Flow

```
RepairContext {
  runId, attemptNumber, taskText, worktreePath,
  graph: RequirementGraph,
  verdicts: List[RequirementVerdict],
  inspectionReport, runtimePlan, patchHistory,
  generationMode, featureSpec, featurePlan, logs
}
  ↓
ClaudePromptBuilder.buildUserPrompt()
  → giant text blob with task, failures, requirements, 8 files
  ↓
Single API call → JSON response
  ↓
PatchProposal { edits, newFiles, deletions }
  ↓
PatchApplier.apply() → mutate files in worktree
```

**After the shift**, the equivalent is:

```
RepairContext (same data)
  ↓
AgentSystemPromptBuilder.buildPrompt()
  → focused context: task, failures, requirements, verification results
  → NO file contents (agent reads them itself)
  ↓
Agent SDK session (multi-turn, tool-using)
  → Agent reads files, runs commands, edits code, self-validates
  → Agent can call Demiurge MCP tools (run verifiers, check logs)
  ↓
Agent completes → worktree is modified in-place
  ↓
Demiurge re-verifies (authoritative pass/fail)
```

---

## 4. Target Architecture

### 4.1 Component Diagram

```
┌────────────────────────────────────────────────────────────────────┐
│                        RunOrchestrator                              │
│                                                                     │
│  ┌─────────┐   ┌──────────┐   ┌─────────┐   ┌──────────────────┐  │
│  │Inspector│──►│Compiler  │──►│Planner  │──►│RuntimeSupervisor │  │
│  └─────────┘   └──────────┘   └─────────┘   └──────────────────┘  │
│                                                      │              │
│  ┌──────────────────┐                    ┌───────────▼──────────┐  │
│  │VerificationEngine│◄───────────────────│EnvironmentHealthMon │  │
│  └────────┬─────────┘                    └──────────────────────┘  │
│           │                                                         │
│    verdict│ = Fail                                                  │
│           │                                                         │
│  ┌────────▼─────────────────────────────────────────────────────┐  │
│  │                    AgentBackend (new)                          │  │
│  │                                                               │  │
│  │  ┌─────────────────────┐     ┌────────────────────────────┐  │  │
│  │  │AgentSystemPrompt    │     │AgentExecutor               │  │  │
│  │  │Builder (from        │────►│  - spawns claude subprocess│  │  │
│  │  │RepairContext)       │     │  - or calls Agent SDK      │  │  │
│  │  └─────────────────────┘     │  - streams events          │  │  │
│  │                              │  - tracks token usage      │  │  │
│  │                              └─────────────┬──────────────┘  │  │
│  │                                            │                  │  │
│  │                              ┌─────────────▼──────────────┐  │  │
│  │                              │DemiurgeMcpServer           │  │  │
│  │                              │  (stdio or in-process)     │  │  │
│  │                              │  - verify_requirements()   │  │  │
│  │                              │  - get_service_logs()      │  │  │
│  │                              │  - restart_service()       │  │  │
│  │                              │  - get_requirement_details()│ │  │
│  │                              └────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  Demiurge re-verifies → Pass? → Succeeded                          │
│                       → Fail? → next attempt (invoke agent again)   │
└────────────────────────────────────────────────────────────────────┘
```

### 4.2 Separation of Concerns

| Concern | Owner | Rationale |
|---------|-------|-----------|
| Requirements compilation | Demiurge | Structured verification needs structured requirements |
| Verification execution | Demiurge | Authoritative pass/fail must be deterministic and controlled |
| Environment lifecycle | Demiurge | Service boot, health monitoring, teardown |
| Worktree management | Demiurge | Git worktree create/remove, isolation |
| State machine & persistence | Demiurge | Crash recovery, resume, audit trail |
| File reading & editing | Agent SDK | Agent reads what it needs, edits directly |
| Shell command execution | Agent SDK | Compile checks, test runs, dep install |
| Iterative self-correction | Agent SDK | Multi-turn within one repair attempt |
| Context management | Agent SDK | Manages its own context window |

---

## 5. Integration Surfaces

Three viable integration paths, in order of implementation priority:

### 5.1 CLI Subprocess (Phase 1 — recommended starting point)

Invoke `claude` CLI from Scala via `ProcessBuilder`:

```scala
// Pseudocode for AgentExecutor
def invokeAgent(
  worktreePath: Path,
  systemPrompt: String,
  userPrompt: String,
  allowedTools: List[String],
  mcpConfig: Option[Path],
  maxTurns: Option[Int],
  timeoutMs: Long,
): AgentResult = {
  val cmd = ListBuffer("claude", "-p", userPrompt)
  cmd ++= List("--system-prompt", systemPrompt)
  cmd ++= List("--allowedTools", allowedTools.mkString(","))
  cmd ++= List("--output-format", "json")
  mcpConfig.foreach(p => cmd ++= List("--mcp-config", p.toString))
  maxTurns.foreach(n => cmd ++= List("--max-turns", n.toString))

  val process = new ProcessBuilder(cmd.asJava)
    .directory(worktreePath.toFile)
    .redirectErrorStream(false)
    .start()

  // Read stdout (JSON result) and stderr (progress) concurrently
  val stdout = readStreamAsync(process.getInputStream)
  val stderr = readStreamAsync(process.getErrorStream)

  val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
  if (!exited) {
    process.destroyForcibly()
    return AgentResult.Timeout(timeoutMs)
  }

  val exitCode = process.exitValue()
  val output = stdout.get()

  if (exitCode == 0) {
    parseAgentJsonOutput(output)
  } else {
    AgentResult.Failed(s"Agent exited with code $exitCode: ${stderr.get()}")
  }
}
```

**Advantages:** Zero new dependencies. Works from the JVM. `claude` handles all API communication, tool execution, and context management.

**Requirements:** `claude` CLI must be installed on the machine running Demiurge. `ANTHROPIC_API_KEY` must be set.

**Output format:** With `--output-format json`, the CLI returns:
```json
{
  "result": "I've fixed the failing health endpoint...",
  "session_id": "abc123",
  "cost_usd": 0.0234,
  "duration_ms": 45000,
  "input_tokens": 12000,
  "output_tokens": 3400
}
```

### 5.2 TypeScript SDK via Worker (Phase 2)

Extend the existing `worker/` TypeScript process (which already communicates with Scala via JSON-RPC) to host Agent SDK sessions:

```typescript
// worker/src/methods/agentRepair.ts
import { query, ClaudeAgentOptions } from 'claude-agent-sdk';

export async function agentRepair(params: {
  prompt: string;
  systemPrompt: string;
  allowedTools: string[];
  cwd: string;
  mcpServers?: Record<string, McpServerConfig>;
}): Promise<AgentResult> {
  const messages = [];
  for await (const message of query({
    prompt: params.prompt,
    options: new ClaudeAgentOptions({
      allowed_tools: params.allowedTools,
      system_prompt: params.systemPrompt,
      cwd: params.cwd,
      mcp_servers: params.mcpServers,
    }),
  })) {
    messages.push(message);
    // Stream progress back to Scala via JSON-RPC notifications
    if (message.type === 'tool_use') {
      rpcNotify('agent/progress', { tool: message.name, status: 'running' });
    }
  }
  return extractResult(messages);
}
```

**Advantages:** Streaming progress, conversation continuation (`--continue`), programmatic MCP server registration, richer control over tool approval callbacks.

**Requirements:** `npm install claude-agent-sdk` in the worker package.

### 5.3 Python SDK via Sidecar (Phase 3 — optional)

A Python sidecar process for the most feature-complete SDK:

```python
# agent_sidecar/main.py
import asyncio
from claude_agent_sdk import query, ClaudeAgentOptions

async def run_repair(prompt, system_prompt, cwd, mcp_servers=None):
    result = None
    async for message in query(
        prompt=prompt,
        options=ClaudeAgentOptions(
            allowed_tools=["Read", "Edit", "Bash", "Grep", "Glob"],
            system_prompt=system_prompt,
            cwd=cwd,
            mcp_servers=mcp_servers,
        ),
    ):
        if hasattr(message, "result"):
            result = message
    return result
```

**Advantages:** Most feature-complete SDK, in-process MCP servers, native async.

**Requirements:** Python 3.10+, `pip install claude-agent-sdk`.

### 5.4 Recommendation

**Start with CLI subprocess (5.1)** — it works today from the JVM with zero new dependencies beyond the `claude` CLI itself. Move to the TypeScript SDK (5.2) when streaming progress and conversation continuation become important. The Python SDK (5.3) is optional and only needed if we want in-process MCP servers.

---

## 6. MCP Server: Demiurge Verification Tools

The key differentiator: give the agent access to Demiurge's verification infrastructure as callable tools. This creates a tight feedback loop where the agent can verify its own work *during* its coding session.

### 6.1 Tool Definitions

```json
{
  "tools": [
    {
      "name": "verify_requirements",
      "description": "Run the full Demiurge verification suite against the current state of the worktree. Returns structured results: per-requirement verdict (Pass/Fail/Timeout), failure messages, and aggregate summary. Use this after making code changes to check if the fixes work before finishing.",
      "inputSchema": {
        "type": "object",
        "properties": {
          "requirementIds": {
            "type": "array",
            "items": { "type": "string" },
            "description": "Optional subset of requirement IDs to verify. Omit to run all."
          }
        }
      }
    },
    {
      "name": "get_service_logs",
      "description": "Get recent log output (stdout + stderr) from a running service managed by Demiurge. Use this to see server-side error messages, stack traces, and startup failures.",
      "inputSchema": {
        "type": "object",
        "properties": {
          "serviceId": { "type": "string", "description": "Service ID from the runtime plan" },
          "tailLines": { "type": "integer", "default": 100, "description": "Number of recent lines to return" }
        },
        "required": ["serviceId"]
      }
    },
    {
      "name": "restart_service",
      "description": "Restart a running service after making code changes. This performs a graceful stop followed by a fresh start. Use this after editing server code to pick up changes.",
      "inputSchema": {
        "type": "object",
        "properties": {
          "serviceId": { "type": "string" }
        },
        "required": ["serviceId"]
      }
    },
    {
      "name": "get_requirement_details",
      "description": "Get detailed information about a specific requirement including its verifier configuration, priority, dependencies, and human description.",
      "inputSchema": {
        "type": "object",
        "properties": {
          "requirementId": { "type": "string" }
        },
        "required": ["requirementId"]
      }
    },
    {
      "name": "get_verification_results",
      "description": "Get the results from the most recent Demiurge verification run, including per-requirement verdicts, failure messages, HTTP response details, and aggregate summary.",
      "inputSchema": {
        "type": "object",
        "properties": {}
      }
    },
    {
      "name": "check_service_health",
      "description": "Check the health status of all running services. Returns each service's status (healthy/unhealthy/stopped), PID, port, and last health check result.",
      "inputSchema": {
        "type": "object",
        "properties": {}
      }
    }
  ]
}
```

### 6.2 Implementation: stdio MCP Server in Scala

The MCP server runs as a Scala subprocess that communicates via stdin/stdout JSON-RPC. The orchestrator spawns it before invoking the agent and passes its stdio handle to the `claude` CLI via `--mcp-config`.

```scala
// agent-backend/src/main/scala/demiurge/agent/DemiurgeMcpServer.scala
object DemiurgeMcpServer {

  /**
   * Start the MCP server as a background thread that reads JSON-RPC
   * from stdin and writes responses to stdout.
   *
   * The server holds references to:
   * - VerificationEngine (to run verifiers on demand)
   * - RuntimeSupervisor (to restart services, get logs)
   * - RequirementGraph (to look up requirement details)
   * - Last VerificationResult (to return cached results)
   */
  def start(
    graph: RequirementGraph,
    supervisor: RuntimeSupervisor,
    verificationRunner: () => VerificationEngine.VerificationResult,
    lastResult: () => Option[VerificationEngine.VerificationResult],
    logCollector: (String) => Option[String],
  ): McpServerHandle = { ... }
}
```

Alternatively, for the CLI subprocess approach, the MCP config can be written to a temp file:

```json
{
  "mcpServers": {
    "demiurge": {
      "type": "stdio",
      "command": "java",
      "args": ["-cp", "<demiurge-classpath>", "demiurge.agent.DemiurgeMcpServer"],
      "env": {
        "DEMIURGE_RUN_ID": "run-abc123",
        "DEMIURGE_WORKTREE": "/path/to/worktree",
        "DEMIURGE_DB_PATH": "/path/to/db.sqlite"
      }
    }
  }
}
```

### 6.3 Tool Execution Flow

When the agent calls `verify_requirements()`:

1. MCP server receives the tool call
2. Server invokes `VerificationEngine.runVerification()` with the current requirement graph
3. Verifiers run against the services in the worktree environment
4. Results are returned to the agent as structured JSON
5. Agent reads the results, identifies remaining failures, and continues coding

This is the **core innovation**: the agent can run the verifiers itself within its coding loop, creating a tight feedback cycle that doesn't require handing control back to the orchestrator.

---

## 7. Agent System Prompt Design

The system prompt replaces `ClaudePromptBuilder.buildSystemPrompt()` + `buildUserPrompt()`. Critically, it does **not** include file contents — the agent reads those itself.

### 7.1 System Prompt Template

```
You are a code repair agent working inside a git worktree managed by Demiurge,
a verification-first code automation system.

## Your Task
{context.taskText}

## Generation Mode
{Repair | InitialBuild}

## Working Directory
{context.worktreePath}
All file paths are relative to this directory. You may read and edit any file here.

## Requirements
The following requirements must pass verification:

{for each requirement in context.graph.nodes:}
- [{req.requirementId}] {req.humanDescription}
  Category: {req.category}, Priority: {req.priority}
  {for each verifier in req.verifiers:}
  Verifier: {v.verifierType} — {v.description}
  {end}
{end}

## Current Verification Status
{if mode == Repair:}
The following requirements FAILED verification:

{for each verdict in failedVerdicts:}
- [{v.requirementId}] Status: {v.status}
  Failure: {v.failureMessage}
  {v.details (HTTP status, response body excerpt, etc.)}
{end}

## Failure Analysis
{packet.summary}

{if packet.suspectedRootCauses.nonEmpty:}
Suspected root causes:
{for each cause:}
- {cause.description} (confidence: {cause.confidence})
{end}
{end}

{if context.patchHistory.nonEmpty:}
## Prior Repair Attempts
{for each patch:}
- Attempt {patch.attemptNumber}: {patch.summary}
  Files changed: {patch.filesChanged.mkString(", ")}
  Outcome: {succeeded or failed and why}
{end}
{end}

{if mode == InitialBuild:}
## Feature Plan
{featurePlan.summary}
Files to create: {filesToCreate}
Files to modify: {filesToModify}
New dependencies: {requiresNewDeps}
{end}

## Service Logs
{context.logs (last 200 lines of each service)}

## Available Tools
In addition to standard file and shell tools, you have access to Demiurge-specific
MCP tools:

- **verify_requirements()**: Run the verification suite to check your work.
  Call this after making changes to confirm fixes before finishing.
- **get_service_logs(serviceId)**: Get recent server logs to see errors/stack traces.
- **restart_service(serviceId)**: Restart a service after code changes.
- **get_requirement_details(requirementId)**: Get full details of a requirement.
- **check_service_health()**: Check health status of all running services.

## Instructions
1. Read the relevant source files to understand the codebase structure.
2. Identify the root cause of the failing requirement(s).
3. Make the minimal changes needed to fix the failures.
4. After editing, restart any affected services using restart_service().
5. Run verify_requirements() to confirm your fixes work.
6. If verification still fails, read the new failure details and iterate.
7. Only declare done when verify_requirements() returns all Pass verdicts
   (or you've exhausted reasonable approaches and need to explain why).
```

### 7.2 Key Differences from Current Prompt

| Aspect | Current (ClaudePromptBuilder) | New (AgentSystemPromptBuilder) |
|--------|-------------------------------|-------------------------------|
| File contents | 8 files × 5KB embedded in prompt | None — agent reads what it needs |
| File selection | Keyword-based heuristic scoring | Agent follows imports, greps |
| Response format | JSON patch proposal | Direct file edits (no parsing needed) |
| Iteration | Single-shot; no self-correction | Multi-turn with tool use |
| Verification | Not available during generation | Agent calls `verify_requirements()` |
| Service logs | `context.logs` field (barely populated) | Agent calls `get_service_logs()` |
| Dependency install | Not supported | Agent runs `npm install`, etc. |
| Compilation check | Not performed | Agent runs `tsc`, `npm test`, etc. |

---

## 8. Orchestrator Changes

### 8.1 Current Repair Loop (RunOrchestrator.scala lines 521-760)

The repair loop currently:
1. Transitions to AnalyzingFailure → PlanningRepair → Repairing
2. Builds FailurePacketInput and RepairContext
3. Calls `RepairSession.executeWithSession(backend, worktreePath, input, context)`
4. RepairSession calls `backend.proposePatch()` → gets PatchProposal → calls `PatchApplier.apply()`
5. Returns RepairOutcome (Applied or Rejected)
6. If applied: determines reset strategy (soft vs full rebuild), transitions to SoftResettingEnvironment or RebuildingEnvironment
7. Loops back to ReadyToVerify → Verifying

### 8.2 New Repair Loop

The new loop:
1. Transitions to AnalyzingFailure → PlanningRepair → Repairing (same state machine)
2. Builds AgentContext from the same RepairContext data
3. **Calls `AgentBackend.executeRepair(agentContext)`** which:
   a. Builds the system prompt (§7.1)
   b. Starts the Demiurge MCP server (§6)
   c. Invokes the Claude Agent SDK (`claude -p` or TS SDK)
   d. Agent reads files, edits code, runs commands, calls MCP tools
   e. Agent may call `verify_requirements()` within its session
   f. Agent completes → returns AgentResult
4. AgentResult replaces RepairOutcome:
   ```scala
   sealed trait AgentResult
   case class AgentCompleted(
     sessionId: String,
     filesChanged: List[String],       // detected via git diff
     agentVerified: Boolean,           // did the agent's own verify_requirements() pass?
     tokenUsage: TokenUsage,
     durationMs: Long,
     summary: String,                  // agent's own summary of what it did
   ) extends AgentResult
   case class AgentFailed(reason: String, tokenUsage: TokenUsage) extends AgentResult
   case class AgentTimeout(timeoutMs: Long) extends AgentResult
   ```
5. If AgentCompleted:
   - Detect changed files via `git diff --name-only` in worktree
   - Determine reset strategy based on changed files (same as current InfraSensitiveDetector)
   - If the agent already ran verify_requirements() and it passed, we still do an authoritative re-verify (agent's verification is advisory, not authoritative)
   - Transition to SoftResettingEnvironment or RebuildingEnvironment
6. Loop back to ReadyToVerify → Verifying (same as current)

### 8.3 State Machine — No Changes Required

The existing state machine is already correct for this flow. The states (AnalyzingFailure → PlanningRepair → Repairing → SoftResettingEnvironment → ReadyToVerify → Verifying) all still apply. The only change is *what happens inside* the Repairing state.

### 8.4 Build Mode Changes

`BuildPhaseManager.generateCode()` currently calls `RepairExecutor.executeRepair()` with `GenerationMode.InitialBuild`. In the new architecture, it calls `AgentBackend.executeBuild(buildContext)` instead. Same system prompt template (§7.1) but with the InitialBuild mode sections.

---

## 9. Module-by-Module Impact

### 9.1 Eliminated Code

| Module | File | LOC | Reason |
|--------|------|-----|--------|
| repair-claude | `ClaudeClient.scala` | 142 | Agent SDK handles API communication |
| repair-api | `PatchApplier.scala` | 122 | Agent edits files directly |

### 9.2 Substantially Rewritten

| Module | File | Current LOC | Change |
|--------|------|-------------|--------|
| repair-claude | `ClaudePromptBuilder.scala` | 337 | Becomes `AgentSystemPromptBuilder` — builds system prompt only, no file collection |
| repair-claude | `ClaudeRepairBackend.scala` | 100 | Becomes `AgentBackend` — invokes Agent SDK instead of raw API |
| repair-api | `RepairExecutor.scala` | 58 | Becomes `AgentExecutor` — invoke agent, detect changed files via git diff |
| repair-api | `RepairSession.scala` | 188 | Simplified — wraps agent session instead of prompt+patch lifecycle |
| repair-api | `InferenceBackedRepairBackend.scala` | 156 | Eliminated for repair; InferenceService still used for requirement compilation |

### 9.3 Modified (Small Changes)

| Module | File | Change |
|--------|------|--------|
| orchestrator | `RunOrchestrator.scala` | Repair section (lines 521-760): replace `RepairSession.executeWithSession()` call with `AgentBackend.executeRepair()` |
| orchestrator | `BuildPhaseManager.scala` | `generateCode()`: replace `RepairExecutor.executeRepair()` with `AgentBackend.executeBuild()` |
| core-model | `enums.scala` | No changes — existing enums are sufficient |

### 9.4 Unchanged

| Module | Reason |
|--------|--------|
| verification-engine | Gains importance; exposed via MCP but internal code unchanged |
| runtime-supervisor | Exposed via MCP for restart_service/get_logs; internal code unchanged |
| persistence | No schema changes needed |
| config-resolver | Unchanged; still used for auto-config |
| inspector | Unchanged |
| compiler | Unchanged |
| planner | Unchanged |
| worker | May gain Agent SDK hosting in Phase 2 (§5.2) |
| cli | Unchanged; `run` and `build` commands work as before |

---

## 10. New Module: agent-backend

### 10.1 Module Structure

```
modules/agent-backend/
  src/main/scala/demiurge/agent/
    AgentBackend.scala          # Trait: executeRepair, executeBuild
    AgentExecutor.scala         # Invokes claude CLI or TS SDK
    AgentSystemPromptBuilder.scala  # Builds system prompt from RepairContext
    AgentResult.scala           # Result ADTs
    DemiurgeMcpServer.scala     # MCP server exposing verification tools
    McpToolHandlers.scala       # Tool implementations
    AgentSessionManager.scala   # Session tracking, conversation continuation
  src/test/scala/demiurge/agent/
    AgentSystemPromptBuilderSuite.scala
    AgentExecutorSuite.scala
    McpToolHandlersSuite.scala
  BUILD.bazel
```

### 10.2 AgentBackend Trait

```scala
package demiurge.agent

import java.nio.file.Path
import demiurge.model._
import demiurge.repair.RepairContext

trait AgentBackend {

  /** Execute a repair session: agent fixes failing verifiers in the worktree. */
  def executeRepair(context: AgentContext): AgentResult

  /** Execute a build session: agent generates code from a feature plan. */
  def executeBuild(context: AgentContext): AgentResult

  /** Cancel an in-progress agent session. */
  def cancel(sessionId: String): Unit

  def backendId: String
}

case class AgentContext(
  repairContext: RepairContext,
  verificationResult: Option[VerificationEngine.VerificationResult],
  failurePacket: Option[FailurePacket],
  serviceIds: List[String],                    // for MCP tool access
  agentConfig: AgentConfig,                    // timeouts, allowed tools, model
)

case class AgentConfig(
  model: Option[String] = None,                // override default model
  maxTurns: Option[Int] = None,                // limit agent turns
  timeoutMs: Long = 300000,                    // 5 min default per attempt
  allowedTools: List[String] = List("Read", "Edit", "Bash", "Grep", "Glob"),
  enableMcpTools: Boolean = true,              // expose Demiurge MCP tools
  enableBash: Boolean = true,                  // allow shell commands
)
```

### 10.3 AgentResult ADT

```scala
sealed trait AgentResult

case class AgentCompleted(
  sessionId: String,
  filesChanged: List[String],
  agentSelfVerified: Boolean,    // did the agent call verify_requirements() and get Pass?
  inputTokens: Long,
  outputTokens: Long,
  costUsd: Option[Double],
  durationMs: Long,
  summary: String,               // agent's own summary
  transcript: Option[String],    // raw JSON transcript for debugging
) extends AgentResult

case class AgentFailed(
  reason: String,
  inputTokens: Long = 0,
  outputTokens: Long = 0,
  durationMs: Long = 0,
) extends AgentResult

case class AgentTimeout(
  timeoutMs: Long,
  partialChanges: List[String],  // files changed before timeout
) extends AgentResult
```

### 10.4 BUILD.bazel

```python
scala_library(
    name = "agent-backend",
    srcs = glob(["src/main/scala/**/*.scala"]),
    deps = [
        "//modules/core-model",
        "//modules/repair-api",
        "//modules/verification-engine",
        "//modules/runtime-supervisor",
    ],
    visibility = ["//visibility:public"],
)

scala_test(
    name = "agent-backend-tests",
    srcs = glob(["src/test/scala/**/*.scala"]),
    deps = [
        ":agent-backend",
        "@maven//:org_scalameta_munit_2_13",
    ],
)
```

---

## 11. Session Management & Budget Tracking

### 11.1 Token Budget

The Agent SDK tracks token usage internally. Demiurge's budget constraints apply at the orchestrator level:

```scala
// In RunOrchestrator, before each agent invocation:
val remainingBudget = currentRun.maxTokens - cumulativeTokensUsed
val agentConfig = AgentConfig(
  timeoutMs = math.min(300000, remainingTimeMs),
  // Agent SDK doesn't natively support token budgets,
  // so we enforce via timeout and maxTurns as proxies
  maxTurns = Some(estimateMaxTurns(remainingBudget)),
)
```

After each agent session, accumulate usage:
```scala
result match {
  case AgentCompleted(_, _, _, inputTokens, outputTokens, _, _, _, _) =>
    cumulativeTokensUsed += inputTokens + outputTokens
  case _ => // timeout or failure, still track partial usage
}
```

### 11.2 Conversation Continuation

The Agent SDK supports continuing a previous conversation via session ID. This is powerful for multi-attempt repair: instead of starting fresh, the second attempt can `--continue` from the first, giving the agent memory of what it already tried.

```scala
// On second repair attempt for the same run:
if (previousSessionId.isDefined) {
  cmd ++= List("--continue", "--session-id", previousSessionId.get)
  // User prompt becomes: "Your previous fix didn't work. Here are the new verification results: ..."
}
```

### 11.3 Transcript Storage

Agent transcripts are stored as artifacts (same as current RepairTranscript):
```scala
val transcript = result.transcript.getOrElse("")
RepairManager.persistRepairTranscript(
  runId, attemptNumber, transcript,
  preCommitSha, postCommitSha,
)
```

---

## 12. Worktree & Environment Interaction

### 12.1 Agent CWD

The agent runs with CWD set to the worktree path. All file operations are relative to the worktree. This is the same isolation model as the current PatchApplier — the agent never touches the original repo.

### 12.2 Service Restarts After Code Changes

Current flow: PatchApplier modifies files → orchestrator does SoftReset (restart all services).

New flow: The agent edits files directly. It can call `restart_service()` via MCP to restart a specific service and verify its changes before completing. However, the orchestrator still performs an authoritative SoftReset/Rebuild after the agent completes, to ensure a clean state for the authoritative verification pass.

### 12.3 Dependency Installation

The agent can run `npm install <package>` (or equivalent for other ecosystems) directly in the worktree. This solves the dependency management gap. The worktree already has `node_modules` symlinked from the repo root; the agent's `npm install` will modify the symlink target. This is acceptable because:
1. The worktree is isolated
2. Adding a package doesn't break the repo root
3. The agent is explicitly given `Bash` tool access

For stricter isolation, we can:
- Break the symlink and copy node_modules into the worktree (slower but safer)
- Restrict Bash to specific commands via `--allowedTools "Bash(npm *),Bash(npx *),Bash(tsc *)"`

### 12.4 Worktree Setup Changes

The current Node.js-specific worktree setup (`.env` copy, `node_modules` symlink) becomes less critical because the agent can perform these steps itself. However, keeping the auto-setup is still valuable for speed — the agent shouldn't spend turns on boilerplate.

The `WorktreeManager` TODO about making this configurable via `demiurge.yaml` should still be implemented:

```yaml
# demiurge.yaml
worktree:
  copy:
    - .env
    - .env.local
  symlink:
    - node_modules
    - .venv
```

---

## 13. Error Handling & Fallback

### 13.1 Agent SDK Not Available

If the `claude` CLI is not installed, fall back to the current `InferenceBackedRepairBackend` (single-shot prompt + PatchApplier). This preserves backwards compatibility:

```scala
val backend: CodeGenerationBackend = if (isClaudeCliAvailable()) {
  new ClaudeAgentBackend(agentConfig)
} else {
  System.err.println("[warn] claude CLI not found — falling back to single-shot repair")
  new LegacyRepairBackendAdapter(inferenceBackedRepairBackend)
}
```

### 13.2 Agent Timeout

If the agent hits its timeout:
1. Force-kill the subprocess
2. Check `git diff --name-only` in the worktree to see if any files were changed
3. If files were changed: proceed with re-verification (the partial fix might work)
4. If no files changed: count as a failed attempt

### 13.3 Agent Crashes

If the `claude` process exits with a non-zero exit code:
1. Capture stderr for logging
2. Count as a failed repair attempt
3. The orchestrator's existing retry logic handles the next attempt

### 13.4 MCP Server Failures

If the MCP server fails to start or crashes mid-session:
1. The agent can still function with its built-in tools (Read, Edit, Bash)
2. It loses access to `verify_requirements()` and `restart_service()`
3. This degrades to "edit code and hope" — similar to the current system
4. Log a warning; don't abort the session

---

## 14. Configuration

### 14.1 demiurge.yaml Extensions

```yaml
# demiurge.yaml
agent:
  # Backend selection: "agent-sdk" (default when claude CLI available) or "legacy"
  backend: agent-sdk

  # Model override for agent sessions
  model: claude-sonnet-4-20250514

  # Maximum wall-clock time per agent session (ms)
  session_timeout_ms: 300000  # 5 minutes

  # Maximum agent turns per session (limits runaway loops)
  max_turns: 50

  # Tools the agent is allowed to use
  allowed_tools:
    - Read
    - Edit
    - Bash
    - Grep
    - Glob

  # Whether to expose Demiurge MCP tools (verification, logs, restart)
  mcp_tools: true

  # Bash command restrictions (glob patterns)
  # Only relevant when Bash is in allowed_tools
  allowed_bash_patterns:
    - "npm *"
    - "npx *"
    - "node *"
    - "tsc *"
    - "curl *"
    - "git diff *"
    - "git status *"
    - "cat *"
    - "grep *"
    - "find *"
    - "ls *"
  denied_bash_patterns:
    - "rm -rf *"
    - "git push *"
    - "git checkout *"
    - "sudo *"
```

### 14.2 Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `ANTHROPIC_API_KEY` | API key for Agent SDK | (required) |
| `DEMIURGE_AGENT_BACKEND` | Override backend: `agent-sdk` or `legacy` | auto-detect |
| `DEMIURGE_AGENT_TIMEOUT_MS` | Session timeout | 300000 |
| `DEMIURGE_AGENT_MAX_TURNS` | Max turns per session | 50 |
| `CLAUDE_CODE_USE_BEDROCK` | Use AWS Bedrock instead of Anthropic API | unset |
| `CLAUDE_CODE_USE_VERTEX` | Use Google Vertex AI | unset |

---

## 15. Migration Path

### 15.1 Backwards Compatibility

The `RepairBackend` trait is preserved with a `LegacyRepairBackendAdapter` that wraps the new `AgentBackend` interface. Existing code that uses `RepairBackend.proposePatch()` continues to work. The adapter:
1. Invokes the agent
2. Detects changed files via `git diff`
3. Synthesizes a `PatchProposal` from the diff (for persistence/audit trail)
4. Returns `RepairResponse.Success(proposal)`

### 15.2 Feature Flag

The agent backend is gated behind a configuration flag:
```yaml
agent:
  backend: agent-sdk  # or "legacy" for current behavior
```

When `backend: legacy`, the system uses `InferenceBackedRepairBackend` with `ClaudePromptBuilder` — exactly as today.

### 15.3 Gradual Rollout

1. **Phase 1:** Agent backend works alongside legacy. CLI flag `--agent` enables it.
2. **Phase 2:** Agent backend becomes the default when `claude` CLI is detected.
3. **Phase 3:** Legacy backend moves to `repair-legacy/` module; `repair-claude/` is archived.

---

## 16. Implementation Plan

### Phase 1: CLI Subprocess Agent Backend (Priority: High)

**Goal:** Replace single-shot repair with agentic repair via `claude -p`.

**Steps:**
1. Create `modules/agent-backend/` module with BUILD.bazel
2. Implement `AgentSystemPromptBuilder` — builds system prompt from RepairContext (no file contents)
3. Implement `AgentExecutor` — invokes `claude -p` with system prompt, output-format json, cwd=worktree
4. Implement `AgentBackend` trait and `ClaudeAgentBackend` implementation
5. Implement `AgentResult` ADT and result parsing
6. Wire into `RunOrchestrator`: replace `RepairSession.executeWithSession()` call in the repair loop
7. Wire into `BuildPhaseManager.generateCode()`: replace `RepairExecutor.executeRepair()` call
8. Add feature flag in config: `agent.backend: agent-sdk | legacy`
9. Add `LegacyRepairBackendAdapter` for backwards compatibility
10. Add CLI `--agent` flag to explicitly enable agent backend

**Files to create:**
- `modules/agent-backend/BUILD.bazel`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentBackend.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentExecutor.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentSystemPromptBuilder.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentResult.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentConfig.scala`

**Files to modify:**
- `modules/orchestrator/src/main/scala/demiurge/orchestrator/RunOrchestrator.scala` (repair section)
- `modules/orchestrator/src/main/scala/demiurge/orchestrator/BuildPhaseManager.scala` (generateCode)
- `modules/orchestrator/BUILD.bazel` (add agent-backend dep)
- `modules/cli/src/main/scala/demiurge/cli/Commands/OrchestrationRunner.scala` (wire agent backend)

**Tests:**
- `AgentSystemPromptBuilderSuite` — verify prompt includes requirements, verdicts, failure context
- `AgentExecutorSuite` — mock subprocess, verify command construction and result parsing
- E2E re-run: Level 4 (repair) and Level 5 (build) with agent backend

**Estimated LOC:** ~400 new, ~50 modified

### Phase 2: Demiurge MCP Server

**Goal:** Give the agent access to verification, logs, and service restart tools.

**Steps:**
1. Implement `DemiurgeMcpServer` — stdio JSON-RPC server with tool handlers
2. Implement tool handlers: `verify_requirements`, `get_service_logs`, `restart_service`, `get_requirement_details`, `check_service_health`, `get_verification_results`
3. MCP config file generation — write temp JSON config pointing to the MCP server
4. Wire MCP config into `AgentExecutor` invocation (via `--mcp-config`)
5. Update system prompt to document available MCP tools

**Files to create:**
- `modules/agent-backend/src/main/scala/demiurge/agent/DemiurgeMcpServer.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/McpToolHandlers.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/McpProtocol.scala` (JSON-RPC message types)

**Tests:**
- `McpToolHandlersSuite` — unit test each tool handler
- `DemiurgeMcpServerSuite` — integration test JSON-RPC communication

**Estimated LOC:** ~600 new

### Phase 3: TypeScript SDK Integration (via Worker)

**Goal:** Streaming progress, conversation continuation, richer control.

**Steps:**
1. Add `claude-agent-sdk` to `worker/package.json`
2. Implement `agentRepair` JSON-RPC method in worker
3. Add `AgentWorkerBackend` implementation that delegates to worker
4. Implement conversation continuation (pass session ID for multi-attempt)
5. Stream agent progress events via SSE to the API

**Files to create:**
- `worker/src/methods/agentRepair.ts`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentWorkerBackend.scala`

**Files to modify:**
- `worker/package.json` (add claude-agent-sdk dep)
- `worker/src/index.ts` (register agentRepair method)

**Estimated LOC:** ~300 new

### Phase 4: Cleanup Legacy Code

**Goal:** Remove unused bespoke repair code.

**Steps:**
1. Archive `ClaudeClient.scala` (eliminated)
2. Archive `PatchApplier.scala` (eliminated)
3. Simplify `RepairSession.scala` to wrap agent sessions
4. Move `ClaudePromptBuilder.scala` to legacy module
5. Update documentation

---

## 17. Testing Strategy

### 17.1 Unit Tests

| Test | What it verifies |
|------|-----------------|
| `AgentSystemPromptBuilderSuite` | Prompt includes task, requirements, verdicts, failure context; no file contents; InitialBuild vs Repair modes |
| `AgentExecutorSuite` | Command line construction, JSON output parsing, timeout handling, error handling |
| `McpToolHandlersSuite` | Each MCP tool returns correct JSON, handles errors gracefully |

### 17.2 Integration Tests

| Test | What it verifies |
|------|-----------------|
| CLI subprocess test | `claude -p` invocation with `--output-format json` in a temp worktree |
| MCP server test | Start MCP server, send JSON-RPC requests, verify tool responses |
| Full loop test | Broken repo → agent fixes → Demiurge re-verifies → Pass |

### 17.3 E2E Re-validation

Re-run the existing E2E test suite (son-of-steve repo, demiurge-e2e-test branch):
- **Level 4 (Repair Loop):** Broken health endpoint → agent fixes → verifiers pass
- **Level 5 (Build Mode):** Feature spec → agent generates code → verifiers pass

Both levels should pass with the agent backend, demonstrating parity with the legacy approach plus improved reliability on harder tasks.

### 17.4 Regression Guard

Keep legacy backend available via `--agent=legacy` flag. CI runs both backends on the E2E suite to ensure parity.

---

## 18. Open Questions

### 18.1 Claude CLI Availability

The CLI subprocess approach requires `claude` to be installed. Options:
- **Require it:** Document as a prerequisite; fail fast with helpful error message
- **Auto-install:** Run `npm install -g @anthropic-ai/claude-code` if not found
- **Bundle it:** Include the CLI in Demiurge's distribution

**Recommendation:** Require it; fail fast with a message like:
```
[error] Claude CLI not found. Install with: npm install -g @anthropic-ai/claude-code
[info] Falling back to legacy single-shot repair backend.
```

### 18.2 Agent Sandbox Safety

The agent has `Bash` tool access in the worktree. Risks:
- Agent could run destructive commands (`rm -rf /`)
- Agent could make network requests to external services
- Agent could modify files outside the worktree

**Mitigations:**
- `--allowedTools "Bash(npm *),Bash(tsc *),..."` restricts shell to specific command patterns
- Worktree isolation means the agent can't corrupt the main repo
- The agent runs as the same user as Demiurge; no privilege escalation
- Claude Code's own safety filters prevent obviously dangerous commands

### 18.3 Cost Implications

Agent sessions use more tokens than single-shot prompts because the agent reads files, runs commands, and iterates. However:
- Each attempt is more likely to succeed → fewer total attempts needed
- The `maxTurns` and `session_timeout_ms` bounds prevent runaway costs
- Token usage is tracked and reported via `AgentResult.inputTokens/outputTokens`

### 18.4 Conversation Continuation Across Attempts

Should attempt 2 continue from attempt 1's conversation? Options:
- **Fresh start:** Agent gets updated context (new verification results) but no memory of prior session
- **Continue:** Agent remembers what it tried; can avoid repeating failed approaches
- **Hybrid:** Fresh start with prior attempt summaries in the system prompt (current approach)

**Recommendation:** Start with fresh-start + summaries (same as current), then experiment with `--continue` in Phase 3.

### 18.5 Authoritative vs Advisory Verification

When the agent calls `verify_requirements()` via MCP, those results are advisory — the orchestrator still performs an authoritative verification pass after the agent completes. This is intentional:
- The agent's verification runs against the current service state, which may not reflect all code changes (e.g., server not yet restarted)
- The authoritative pass runs after a clean environment reset
- The agent's self-verification is useful for iterating but not for declaring success

---

## Appendix A: File Reference

Key files in the current codebase referenced by this design:

| Path | Purpose |
|------|---------|
| `modules/repair-api/src/main/scala/demiurge/repair/RepairBackend.scala` | Current trait (38 LOC) |
| `modules/repair-api/src/main/scala/demiurge/repair/RepairContext.scala` | Context DTO (26 LOC) |
| `modules/repair-api/src/main/scala/demiurge/repair/RepairExecutor.scala` | Single repair orchestrator (58 LOC) |
| `modules/repair-api/src/main/scala/demiurge/repair/RepairSession.scala` | Session lifecycle (188 LOC) |
| `modules/repair-api/src/main/scala/demiurge/repair/PatchApplier.scala` | Find-replace patch application (122 LOC) |
| `modules/repair-api/src/main/scala/demiurge/repair/InferenceBackedRepairBackend.scala` | RepairBackend via InferenceService (156 LOC) |
| `modules/repair-api/src/main/scala/demiurge/repair/FailurePacketBuilder.scala` | Builds failure context (~80 LOC) |
| `modules/repair-claude/src/main/scala/demiurge/repair/claude/ClaudePromptBuilder.scala` | Prompt builder with file collection (337 LOC) |
| `modules/repair-claude/src/main/scala/demiurge/repair/claude/ClaudeClient.scala` | Raw HTTP API client (142 LOC) |
| `modules/repair-claude/src/main/scala/demiurge/repair/claude/ClaudeRepairBackend.scala` | RepairBackend via ClaudeClient (100 LOC) |
| `modules/orchestrator/src/main/scala/demiurge/orchestrator/RunOrchestrator.scala` | State machine (761 LOC, repair loop at 521-760) |
| `modules/orchestrator/src/main/scala/demiurge/orchestrator/BuildPhaseManager.scala` | Build mode phases (297 LOC) |
| `modules/orchestrator/src/main/scala/demiurge/orchestrator/WorktreeManager.scala` | Worktree create/remove (103 LOC) |
| `modules/verification-engine/src/main/scala/demiurge/verification/VerificationEngine.scala` | Verification execution (377 LOC) |
| `modules/runtime-supervisor/src/main/scala/demiurge/runtime/ServiceProcessManager.scala` | Service lifecycle (~283 LOC) |
| `modules/core-model/src/main/scala/demiurge/model/enums.scala` | All enums (364 LOC) |
| `modules/inference/src/main/scala/demiurge/inference/AnthropicInferenceBackend.scala` | Anthropic API for non-repair inference (~180 LOC) |
| `worker/src/index.ts` | TypeScript worker entry point |

## Appendix B: Agent SDK Reference

- **CLI reference:** https://code.claude.com/docs/en/headless
- **Agent SDK overview:** https://platform.claude.com/docs/en/agent-sdk/overview
- **MCP integration:** https://platform.claude.com/docs/en/agent-sdk/mcp
- **Python SDK:** https://platform.claude.com/docs/en/agent-sdk/python
- **TypeScript SDK:** https://platform.claude.com/docs/en/agent-sdk/typescript
- **GitHub (Python):** https://github.com/anthropics/claude-agent-sdk-python
- **GitHub (TypeScript):** https://github.com/anthropics/claude-agent-sdk-typescript

## Appendix C: Example Agent Session (Expected Flow)

A concrete example of what a repair session looks like with the new architecture:

```
[Demiurge] Verification failed: 3 of 7 requirements failed
[Demiurge] Invoking Claude Agent SDK for repair attempt 1...

[Agent] Reading verification results via get_verification_results()...
[Agent] Failed requirements:
  - REQ-002: GET /api/health returns 200 → got 500
  - REQ-004: GET /api/users returns JSON array → connection refused
  - REQ-005: POST /api/users creates user → connection refused

[Agent] Reading server logs via get_service_logs("api-server")...
[Agent] Found: "Error: Cannot find module 'cors'"

[Agent] The server is crashing because 'cors' is imported but not installed.
[Agent] Running: npm install cors
[Agent] ✓ cors@2.8.5 installed

[Agent] Restarting service via restart_service("api-server")...
[Agent] Service restarted successfully.

[Agent] Now investigating the health endpoint. Reading src/routes/health.ts...
[Agent] Found the bug: health endpoint throws because db.ping() is not awaited.
[Agent] Editing src/routes/health.ts: adding 'await' before db.ping()

[Agent] Running verify_requirements() to check fixes...
[Agent] Results: 7/7 Pass ✓

[Agent] All requirements now pass. Summary: installed missing 'cors' dependency
        and fixed async bug in health endpoint.

[Demiurge] Agent completed. Detecting changed files: package.json, package-lock.json,
           src/routes/health.ts
[Demiurge] Performing authoritative re-verification...
[Demiurge] ✓ All 7 verifiers passed after 1 repair(s)
```

Compare this to what the current system would do:
1. Build prompt with 8 keyword-matched files (might not include health.ts)
2. LLM guesses a fix without seeing the "Cannot find module 'cors'" error
3. LLM generates a JSON patch, probably only fixing one of the two issues
4. PatchApplier applies the patch
5. Demiurge re-verifies → still failing (cors not installed)
6. Second attempt: LLM sees "still failing" but still doesn't see the actual error
7. Repeat until maxAttempts exhausted

The agent approach solves both issues in a single attempt because it can read logs, install dependencies, and verify its own work.
