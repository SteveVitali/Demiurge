# Design: Claude Agent SDK Integration — Replacing Bespoke Repair with Agentic Coding

**Status:** Final Draft (v2 — revised after critical review)
**Date:** 2026-03-18
**Revised:** 2026-03-18
**Branch:** review/agent-sdk-design
**Depends on:** close-all-spec-gaps (all E2E fixes merged)

This document specifies the architectural shift from Demiurge's bespoke single-shot LLM repair/build pipeline to delegating code generation and repair to the **Claude Agent SDK** (TypeScript) — giving the system the same capabilities as Claude Code (file read/write, shell commands, iterative self-correction) while Demiurge retains ownership of verification, environment management, and orchestration.

### Revision Summary (v2)

Critical issues identified and addressed during architectural review:

1. **Phasing restructured.** CLI subprocess dropped as Phase 1; TypeScript SDK is now Phase 1. Python sidecar dropped entirely. Rationale: the TypeScript SDK provides in-process MCP servers, native sandbox, budget control, hooks, and file checkpointing — all unavailable via raw CLI.
2. **Sandbox specification added.** The SDK's `SandboxSettings` API is now the primary isolation mechanism, with explicit filesystem and network policies.
3. **node_modules isolation fixed.** Symlink approach replaced with copy-on-create for agent-backed runs to prevent cross-run contamination and repo root mutation.
4. **Budget control corrected.** Design now uses the SDK's native `maxBudgetUsd` instead of the incorrect claim that "Agent SDK doesn't natively support token budgets."
5. **MCP architecture simplified.** In-process SDK MCP server in the TypeScript worker replaces the over-engineered Scala stdio MCP server. Worker calls back to Scala orchestrator via existing JSON-RPC.
6. **File checkpointing added.** SDK's `enableFileCheckpointing` + `rewindFiles()` for clean rollback on timeout/failure.
7. **Hooks added** for real-time monitoring, transcript capture, and safety enforcement.
8. **Permission mode specified.** `bypassPermissions` + sandbox for headless automated operation.
9. **Graceful interrupt** via `Query.interrupt()` replaces force-kill as the primary shutdown mechanism.
10. **Structured output** via SDK's `outputFormat` for reliable agent result parsing.
11. **Session persistence** specified for crash recovery.
12. **Environment lifecycle during agent session** fully specified.

---

## Table of Contents

1. [Motivation](#1-motivation)
2. [Architecture Overview](#2-architecture-overview)
3. [Current Architecture (What Changes)](#3-current-architecture-what-changes)
4. [Target Architecture](#4-target-architecture)
5. [Integration Surface: TypeScript SDK via Worker](#5-integration-surface-typescript-sdk-via-worker)
6. [MCP Server: In-Process Demiurge Tools](#6-mcp-server-in-process-demiurge-tools)
7. [Agent System Prompt Design](#7-agent-system-prompt-design)
8. [Orchestrator Changes](#8-orchestrator-changes)
9. [Module-by-Module Impact](#9-module-by-module-impact)
10. [New Module: agent-backend](#10-new-module-agent-backend)
11. [Session Management & Budget Tracking](#11-session-management--budget-tracking)
12. [Worktree & Environment Isolation](#12-worktree--environment-isolation)
13. [Sandbox Configuration](#13-sandbox-configuration)
14. [Error Handling & Fallback](#14-error-handling--fallback)
15. [Configuration](#15-configuration)
16. [Migration Path](#16-migration-path)
17. [Implementation Plan](#17-implementation-plan)
18. [Testing Strategy](#18-testing-strategy)
19. [Resolved Questions](#19-resolved-questions)

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

## 5. Integration Surface: TypeScript SDK via Worker

### 5.1 Why TypeScript SDK Is the Only Viable Phase 1

The original draft proposed CLI subprocess as Phase 1. After researching the actual SDK API surface, this is incorrect. The TypeScript SDK must be Phase 1 because it provides critical capabilities unavailable via the raw CLI:

| Capability | CLI subprocess | TypeScript SDK |
|-----------|---------------|----------------|
| In-process MCP servers (`createSdkMcpServer`) | ❌ Must build separate Scala stdio server | ✅ Define tools in-process |
| Sandbox (`SandboxSettings`) | ❌ Not configurable | ✅ Full filesystem + network sandboxing |
| Budget control (`maxBudgetUsd`) | ❌ Must estimate from `maxTurns` | ✅ Native USD budget limit |
| File checkpointing (`rewindFiles`) | ❌ | ✅ Rollback worktree on failure |
| Hooks (pre/post tool execution) | ❌ | ✅ Real-time monitoring, transcript capture |
| Custom permission callbacks (`canUseTool`) | ❌ | ✅ Programmatic permission decisions |
| Graceful interrupt (`Query.interrupt()`) | ❌ Force-kill only | ✅ Graceful shutdown + force-kill fallback |
| Structured output (`outputFormat`) | ❌ | ✅ JSON schema validation on agent result |
| Streaming messages | ❌ Stdout only after exit | ✅ `AsyncGenerator<SDKMessage>` |
| Session resume | ❌ | ✅ `resume` option with session ID |

Additionally, Demiurge already has a TypeScript worker process (`worker/`) communicating with Scala via JSON-RPC. The infrastructure exists.

The Python SDK is feature-equivalent to the TypeScript SDK (both wrap the same Claude Code executable) and adds no unique value. It is dropped from this design.

### 5.2 Architecture: Worker as Agent Host

The existing `worker/` TypeScript process gains a new JSON-RPC method `agent/execute` that hosts Agent SDK sessions. The worker already has the JSON-RPC communication channel to Scala; the agent's MCP tools call back through this channel to access Demiurge's verification engine, runtime supervisor, and service logs.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   Scala: RunOrchestrator                                 │
│                                                                          │
│  JSON-RPC call: agent/execute                                            │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │                    │                         ▲                     │    │
│  │     params:        │                         │ result:            │    │
│  │     - prompt       │                         │ - session_id       │    │
│  │     - systemPrompt │                         │ - total_cost_usd   │    │
│  │     - worktreePath │                         │ - usage            │    │
│  │     - agentConfig  │                         │ - duration_ms      │    │
│  │     - serviceIds   │                         │ - summary          │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                       │                         ▲                        │
└───────────────────────┼─────────────────────────┼────────────────────────┘
                        │ JSON-RPC                │ JSON-RPC
                        ▼                         │
┌───────────────────────────────────────────────────────────────────────────┐
│                    TypeScript: Worker Process                               │
│                                                                             │
│  ┌──────────────────────────────┐    ┌────────────────────────────────┐   │
│  │  Agent SDK query()            │    │  In-Process MCP Server          │   │
│  │  - cwd: worktreePath         │◄──►│  (createSdkMcpServer)           │   │
│  │  - sandbox: enabled          │    │                                  │   │
│  │  - maxBudgetUsd              │    │  Tools:                          │   │
│  │  - hooks: transcript capture │    │  - verify_requirements()         │   │
│  │  - enableFileCheckpointing   │    │  - get_service_logs(serviceId)   │   │
│  │  - permissionMode: bypass    │    │  - restart_service(serviceId)    │   │
│  │                              │    │  - get_requirement_details(id)   │   │
│  │  Built-in tools:             │    │  - check_service_health()        │   │
│  │  - Read, Edit, Write         │    │                                  │   │
│  │  - Bash, Grep, Glob          │    │  Each tool handler calls back    │   │
│  └──────────────────────────────┘    │  to Scala via JSON-RPC:          │   │
│                                      │  → demiurge.verifyRequirements() │   │
│                                      │  → demiurge.getServiceLogs()     │   │
│                                      │  → demiurge.restartService()     │   │
│                                      └────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Worker Implementation

```typescript
// worker/src/methods/agentExecute.ts
import { query, tool, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import type { SDKMessage, SDKResultMessage } from "@anthropic-ai/claude-agent-sdk";

interface AgentExecuteParams {
  prompt: string;
  systemPrompt: string;
  worktreePath: string;
  repoRoot: string;
  serviceIds: string[];
  agentConfig: {
    model?: string;
    maxTurns?: number;
    maxBudgetUsd?: number;
    timeoutMs: number;
    sessionId?: string;   // for resume
    resume?: boolean;
  };
}

interface AgentExecuteResult {
  sessionId: string;
  totalCostUsd: number;
  inputTokens: number;
  outputTokens: number;
  durationMs: number;
  numTurns: number;
  summary: string;
  subtype: string;
  errors?: string[];
}

export async function agentExecute(
  params: AgentExecuteParams,
  rpcCall: (method: string, args: any) => Promise<any>,
  rpcNotify: (method: string, args: any) => void,
): Promise<AgentExecuteResult> {
  // Build in-process MCP server with Demiurge tools
  const demiurgeServer = buildDemiurgeMcpServer(params, rpcCall);

  const abortController = new AbortController();
  const timeout = setTimeout(() => abortController.abort(), params.agentConfig.timeoutMs);

  try {
    const q = query({
      prompt: params.prompt,
      options: {
        systemPrompt: params.systemPrompt,
        cwd: params.worktreePath,
        model: params.agentConfig.model,
        maxTurns: params.agentConfig.maxTurns,
        maxBudgetUsd: params.agentConfig.maxBudgetUsd,
        abortController,
        enableFileCheckpointing: true,
        persistSession: true,
        permissionMode: "bypassPermissions",

        // Resume from previous session if specified
        ...(params.agentConfig.resume && params.agentConfig.sessionId
          ? { resume: params.agentConfig.sessionId }
          : {}),

        // SDK sandbox configuration (§13)
        sandbox: {
          enabled: true,
          autoAllowBashIfSandboxed: true,
          network: {
            allowLocalBinding: true,   // services need to bind ports
            allowedDomains: [
              "registry.npmjs.org",    // npm install
              "registry.yarnpkg.com",  // yarn
              "pypi.org",              // pip
            ],
          },
          filesystem: {
            allowWrite: [params.worktreePath, "/tmp"],
            denyWrite: [params.repoRoot],
            denyRead: [],
          },
        },

        // MCP: in-process Demiurge tools
        mcpServers: {
          demiurge: demiurgeServer,
        },

        // Pre-approve all tools (sandbox provides safety)
        allowedTools: [
          "Read", "Edit", "Write", "Bash", "Grep", "Glob",
          "mcp__demiurge__*",
        ],

        // Hooks for monitoring
        hooks: {
          "preToolUse": [{
            callback: async (input) => {
              rpcNotify("agent/toolUse", {
                tool: input.tool_name,
                input: input.tool_input,
                timestamp: Date.now(),
              });
              return { decision: "approve" };
            },
          }],
        },
      },
    });

    let result: SDKResultMessage | null = null;
    for await (const message of q) {
      if (message.type === "result") {
        result = message as SDKResultMessage;
      }
      // Stream progress notifications
      if (message.type === "assistant") {
        rpcNotify("agent/progress", {
          type: "assistant",
          numTurns: (message as any).num_turns,
        });
      }
    }

    if (!result) {
      throw new Error("Agent session completed without a result message");
    }

    return {
      sessionId: result.session_id,
      totalCostUsd: result.total_cost_usd,
      inputTokens: result.usage.input_tokens,
      outputTokens: result.usage.output_tokens,
      durationMs: result.duration_ms,
      numTurns: result.num_turns,
      summary: result.subtype === "success" ? result.result : "",
      subtype: result.subtype,
      errors: "errors" in result ? result.errors : undefined,
    };
  } finally {
    clearTimeout(timeout);
  }
}
```

### 5.4 Requirements

- `npm install @anthropic-ai/claude-agent-sdk zod` in the worker package
- `ANTHROPIC_API_KEY` set in the environment (or Bedrock/Vertex credentials)
- Claude Code executable must be installed (`npm install -g @anthropic-ai/claude-code`)

---

## 6. MCP Server: In-Process Demiurge Tools

The key differentiator: give the agent access to Demiurge's verification infrastructure as callable tools. This creates a tight feedback loop where the agent can verify its own work *during* its coding session.

### 6.1 Architecture: In-Process SDK MCP Server

The original draft proposed building a separate Scala stdio MCP server — a substantial effort (JSON-RPC protocol implementation, separate process management, IPC). The TypeScript SDK eliminates this entirely via `createSdkMcpServer()`, which defines MCP tools as in-process functions.

The in-process tool handlers call back to Scala via the existing JSON-RPC channel. This means:
- **No new Scala MCP server code.** The MCP server runs in the TypeScript worker.
- **No additional subprocess.** Tools execute in the same process as the agent.
- **Existing JSON-RPC channel.** Tool handlers use the same `rpcCall()` mechanism the worker already uses for browser automation.

### 6.2 Tool Implementation

```typescript
// worker/src/methods/demiurgeMcpTools.ts
import { tool, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";

export function buildDemiurgeMcpServer(
  params: { serviceIds: string[] },
  rpcCall: (method: string, args: any) => Promise<any>,
) {
  return createSdkMcpServer({
    name: "demiurge",
    version: "1.0.0",
    tools: [
      tool(
        "verify_requirements",
        "Run Demiurge verification suite against the current worktree state. " +
        "Returns per-requirement verdict (Pass/Fail/Timeout), failure messages, " +
        "and aggregate summary. Call this after making code changes and restarting " +
        "affected services to check if your fixes work.",
        {
          requirementIds: z.array(z.string()).optional()
            .describe("Optional subset of requirement IDs to verify. Omit to run all."),
        },
        async (args) => {
          const result = await rpcCall("demiurge.verifyRequirements", {
            requirementIds: args.requirementIds,
          });
          return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
        },
        { annotations: { readOnly: true } },
      ),

      tool(
        "get_service_logs",
        "Get recent log output (stdout + stderr) from a running service. " +
        "Use this to see server-side error messages, stack traces, and startup failures.",
        {
          serviceId: z.string().describe("Service ID from the runtime plan"),
          tailLines: z.number().default(100).describe("Number of recent lines"),
        },
        async (args) => {
          const result = await rpcCall("demiurge.getServiceLogs", {
            serviceId: args.serviceId,
            tailLines: args.tailLines,
          });
          return { content: [{ type: "text", text: result.logs || "(no logs)" }] };
        },
        { annotations: { readOnly: true } },
      ),

      tool(
        "restart_service",
        "Restart a running service after making code changes. Performs a graceful " +
        "stop followed by a fresh start. Use after editing server code to pick up changes. " +
        "IMPORTANT: Always restart affected services BEFORE calling verify_requirements().",
        {
          serviceId: z.string().describe("Service ID to restart"),
        },
        async (args) => {
          const result = await rpcCall("demiurge.restartService", {
            serviceId: args.serviceId,
          });
          return { content: [{ type: "text", text: JSON.stringify(result) }] };
        },
        { annotations: { destructive: true } },
      ),

      tool(
        "get_requirement_details",
        "Get detailed information about a specific requirement including its " +
        "verifier configuration, priority, dependencies, and human description.",
        {
          requirementId: z.string().describe("Requirement ID"),
        },
        async (args) => {
          const result = await rpcCall("demiurge.getRequirementDetails", {
            requirementId: args.requirementId,
          });
          return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
        },
        { annotations: { readOnly: true } },
      ),

      tool(
        "check_service_health",
        "Check health status of all running services. Returns each service's " +
        "status (healthy/unhealthy/stopped), PID, port, and last health check result.",
        {},
        async () => {
          const result = await rpcCall("demiurge.checkServiceHealth", {});
          return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
        },
        { annotations: { readOnly: true } },
      ),
    ],
  });
}
```

### 6.3 Scala-Side JSON-RPC Handlers (New)

The Scala orchestrator must register new JSON-RPC handlers that the worker calls when MCP tool handlers fire. These are scoped to the current run's services and verification engine:

```scala
// New methods registered in WorkerRpcServer for agent tool callbacks
object AgentToolRpcHandlers {

  def verifyRequirements(
    engine: VerificationEngine,
    graph: RequirementGraph,
    plan: RuntimePlan,
    repoRoot: Path,
    requirementIds: Option[List[String]],
  ): VerificationResult = {
    val filteredGraph = requirementIds match {
      case Some(ids) => graph.filterByIds(ids.toSet)
      case None => graph
    }
    engine.runVerification(filteredGraph, plan, repoRoot)
  }

  def getServiceLogs(serviceId: String, tailLines: Int): ServiceLogResult = {
    val lines = ServiceProcessManager.getLogLines(serviceId).takeRight(tailLines)
    ServiceLogResult(serviceId, lines.mkString("\n"))
  }

  def restartService(serviceId: String, plan: RuntimePlan, repoRoot: Path): RestartResult = {
    val spec = plan.services.find(_.serviceId == serviceId)
    spec match {
      case Some(s) =>
        ServiceProcessManager.stopScript(s.serviceId, s.shutdownTimeoutMs,
          repoRoot.resolve(".demiurge").resolve("pids"))
        ServiceProcessManager.startScript(s, repoRoot.resolve(".demiurge").resolve("pids")) match {
          case Right(_) =>
            val ready = ReadinessChecker.waitUntilReady(
              s.readinessProbe, ServiceProcessManager.getLogLines(s.serviceId))
            RestartResult(serviceId, success = ready, message = if (ready) "healthy" else "readiness check failed")
          case Left(err) => RestartResult(serviceId, success = false, message = err)
        }
      case None => RestartResult(serviceId, success = false, message = "unknown service ID")
    }
  }

  def getRequirementDetails(graph: RequirementGraph, requirementId: String): Option[RequirementNode] =
    graph.nodes.find(_.requirementId == requirementId)

  def checkServiceHealth(plan: RuntimePlan): List[ServiceHealthStatus] =
    plan.services.map { spec =>
      val logs = ServiceProcessManager.getLogLines(spec.serviceId)
      ServiceHealthStatus(
        serviceId = spec.serviceId,
        status = if (ServiceProcessManager.isRunning(spec.serviceId)) "running" else "stopped",
        logTail = logs.takeRight(5),
      )
    }
}
```

### 6.4 Tool Execution Flow

When the agent calls `verify_requirements()`:

1. SDK routes the tool call to the in-process MCP server handler
2. Handler invokes `rpcCall("demiurge.verifyRequirements", ...)` over JSON-RPC to Scala
3. Scala invokes `VerificationEngine.runVerification()` with the current requirement graph
4. Verifiers run against the services in the worktree environment
5. Results are returned through JSON-RPC → MCP handler → agent as structured JSON
6. Agent reads the results, identifies remaining failures, and continues coding

### 6.5 Critical Ordering: Edit → Restart → Verify

The agent must follow this sequence for valid verification results:
1. Edit code files
2. Call `restart_service()` for each affected service
3. Wait for the restart result to confirm healthy status
4. Call `verify_requirements()`

This ordering is enforced via the system prompt (§7.1) and documented in tool descriptions. The `restart_service` tool blocks until the service is healthy or times out, ensuring the agent doesn't verify stale code.

### 6.6 Concurrent Run Safety

The `ServiceProcessManager` uses a global `ConcurrentHashMap` keyed by `serviceId`. For concurrent runs, service IDs must be unique per run (they currently are, since they include the run ID prefix from the runtime plan). The JSON-RPC handlers receive the current run's `RuntimePlan` as context, ensuring `restartService()` only affects services in the invoking run.

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
   b. Builds in-process Demiurge MCP server (§6)
   c. Invokes the Claude Agent SDK via worker's `agent/execute` JSON-RPC method (§5)
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
| worker | **Gains Agent SDK hosting** — new `agent/execute` JSON-RPC method, MCP tool definitions, new deps |
| cli | Unchanged; `run` and `build` commands work as before |

---

## 10. New Module: agent-backend

### 10.1 Module Structure

```
modules/agent-backend/
  src/main/scala/demiurge/agent/
    AgentBackend.scala              # Trait: executeRepair, executeBuild
    AgentExecutor.scala             # Invokes worker's agent/execute via JSON-RPC
    AgentSystemPromptBuilder.scala  # Builds system prompt from RepairContext
    AgentResult.scala               # Result ADTs
    AgentConfig.scala               # Configuration DTOs
    AgentToolRpcHandlers.scala      # Scala-side handlers for MCP tool callbacks
  src/test/scala/demiurge/agent/
    AgentSystemPromptBuilderSuite.scala
    AgentExecutorSuite.scala
    AgentToolRpcHandlersSuite.scala
  BUILD.bazel

worker/src/methods/
  agentExecute.ts                   # Agent SDK query() wrapper
  demiurgeMcpTools.ts               # In-process MCP server with Demiurge tools
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
  maxBudgetUsd: Option[Double] = None,         // native SDK budget limit per session
  timeoutMs: Long = 300000,                    // 5 min default per attempt
  enableMcpTools: Boolean = true,              // expose Demiurge MCP tools
  sessionId: Option[String] = None,            // for session resume
  resume: Boolean = false,                     // continue from previous session
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

### 11.1 Budget Control via `maxBudgetUsd`

The original draft incorrectly stated "Agent SDK doesn't natively support token budgets." The SDK provides **native budget control** via `maxBudgetUsd`:

```scala
// In RunOrchestrator, before each agent invocation:
val remainingBudgetUsd = currentRun.maxBudgetUsd - cumulativeCostUsd
val agentConfig = AgentConfig(
  maxBudgetUsd = Some(math.min(remainingBudgetUsd, perSessionBudgetUsd)),
  timeoutMs = math.min(300000, remainingTimeMs),
  maxTurns = Some(50),  // safety net, not primary budget control
)
```

The SDK enforces the budget natively. If exceeded, the result message has `subtype: "error_max_budget_usd"` with the `errors` field populated. After each session, accumulate cost:

```scala
result match {
  case AgentCompleted(_, _, _, inputTokens, outputTokens, costUsd, _, _, _) =>
    cumulativeCostUsd += costUsd.getOrElse(0.0)
    cumulativeTokensUsed += inputTokens + outputTokens
  case _ => // timeout or failure — SDK still reports partial cost in the result
}
```

### 11.2 Conversation Continuation via Session Resume

The SDK supports resuming a previous session via `resume: sessionId`. This is powerful for multi-attempt repair: instead of starting fresh, the second attempt continues from the first, giving the agent memory of what it already tried.

```scala
// On second repair attempt for the same run:
val agentConfig = if (previousSessionId.isDefined) {
  AgentConfig(
    sessionId = previousSessionId,
    resume = true,
    // Prompt becomes: "Your previous fix didn't work. Here are the new verification results: ..."
  )
} else {
  AgentConfig()
}
```

**Strategy by attempt number:**
- **Attempt 1:** Fresh session. Agent gets full context in system prompt.
- **Attempt 2:** Resume from attempt 1's session. Agent prompt includes updated verification results showing what still fails.
- **Attempt 3+:** Fresh session with all prior attempt summaries in system prompt (context window may be exhausted from prior resume).

### 11.3 Session Persistence for Crash Recovery

The SDK's `persistSession: true` (default) persists session state to disk. If Demiurge crashes mid-agent-session:
1. On restart, Demiurge detects the interrupted run via SQLite (persist-before-side-effects invariant)
2. The agent session ID is stored in the run's metadata
3. Demiurge can resume the session or start a fresh attempt

### 11.4 Transcript Storage

Agent session transcripts are captured via hooks and stored as artifacts:

```scala
val transcript = result.transcript.getOrElse("")
RepairManager.persistRepairTranscript(
  runId, attemptNumber, transcript,
  preCommitSha, postCommitSha,
)
```

The `preToolUse` hook (§5.3) streams tool calls to Scala via `agent/toolUse` notifications, allowing real-time transcript accumulation even if the session is interrupted.

---

## 12. Worktree & Environment Isolation

### 12.1 Agent CWD and Filesystem Scope

The agent runs with `cwd` set to the worktree path. All file operations (Read, Edit, Write) are relative to the worktree. The SDK's sandbox (§13) enforces this at the filesystem level:
- **`allowWrite: [worktreePath, "/tmp"]`** — agent can only write to the worktree
- **`denyWrite: [repoRoot]`** — agent is explicitly blocked from writing to the original repo

This is a **strict upgrade** over the current PatchApplier, which only operated on worktree paths by convention. The sandbox makes this a hard guarantee.

### 12.2 Environment Lifecycle During Agent Session

The design must specify exactly what happens to running services while the agent works:

1. **Before agent session:** Orchestrator boots services from the worktree (already running from the previous verification that detected failures).
2. **During agent session:**
   - Services are running and serving the current worktree code.
   - Agent edits code files — services still serve the **old** code.
   - Agent calls `restart_service(serviceId)` — service restarts and picks up **new** code.
   - Agent calls `verify_requirements()` — verifiers run against the now-updated services.
3. **After agent session:** Orchestrator performs an authoritative environment reset (SoftReset or full Rebuild depending on whether infra-sensitive files changed) followed by authoritative verification. This ensures a clean state independent of what the agent did.

This ordering is critical. The system prompt (§7.1) must instruct the agent to always restart affected services before verifying.

### 12.3 node_modules Isolation — CRITICAL FIX

The original draft's approach of symlinking `node_modules` from the repo root is **unsafe** for agent-backed runs:

**Problem:**
- The symlink means `npm install <package>` in the worktree modifies the **original repo's** `node_modules`
- Multiple concurrent runs would race on the shared `node_modules`
- A malicious or broken package corrupts the original repo's deps
- The sandbox's `denyWrite: [repoRoot]` would block `npm install` through the symlink anyway

**Fix:** For agent-backed runs, `WorktreeManager` must **copy** `node_modules` instead of symlinking:

```scala
// WorktreeManager.scala — revised for agent backend
def setupWorktree(worktreePath: Path, repoRoot: Path, agentMode: Boolean): Unit = {
  // Always copy .env files
  copyIfExists(repoRoot.resolve(".env"), worktreePath.resolve(".env"))

  if (agentMode) {
    // Agent mode: COPY node_modules for full isolation
    // This is slower (~5-30s) but prevents cross-contamination
    val srcModules = repoRoot.resolve("node_modules")
    if (Files.exists(srcModules)) {
      copyDirectory(srcModules, worktreePath.resolve("node_modules"))
    }
  } else {
    // Legacy mode: symlink (faster, acceptable for single-shot repair)
    val srcModules = repoRoot.resolve("node_modules")
    if (Files.exists(srcModules)) {
      Files.createSymbolicLink(worktreePath.resolve("node_modules"), srcModules)
    }
  }
}
```

**Optimization:** Use `cp -al` (hardlink copy) on Linux to make this near-instant:
```scala
// Hardlink copy: same speed as symlink, full isolation
new ProcessBuilder("cp", "-al",
  srcModules.toString, worktreePath.resolve("node_modules").toString)
  .start().waitFor()
```

### 12.4 Worktree Setup — Configurable via `demiurge.yaml`

The `WorktreeManager` TODO about making copy/symlink configurable should be implemented:

```yaml
# demiurge.yaml
worktree:
  copy:
    - .env
    - .env.local
  # For agent mode, these are always copied (not symlinked)
  # For legacy mode, these are symlinked for speed
  deps:
    - node_modules
    - .venv
```

---

## 13. Sandbox Configuration

### 13.1 SDK Sandbox: The Primary Safety Mechanism

The original draft's sandbox discussion (§18.2) listed mitigations as future concerns. The TypeScript SDK provides **built-in sandbox support** that must be the primary safety mechanism. The SDK's `SandboxSettings` type:

```typescript
type SandboxSettings = {
  enabled?: boolean;
  autoAllowBashIfSandboxed?: boolean;
  excludedCommands?: string[];
  allowUnsandboxedCommands?: boolean;
  network?: SandboxNetworkConfig;
  filesystem?: SandboxFilesystemConfig;
  ignoreViolations?: Record<string, string[]>;
};
```

### 13.2 Filesystem Sandbox

```typescript
filesystem: {
  allowWrite: [worktreePath, "/tmp"],
  denyWrite: [repoRoot],    // hard block on original repo
  denyRead: [],              // agent can read anything (needs repo root for reference)
}
```

**Key insight:** `denyWrite: [repoRoot]` prevents the agent from modifying the original repository even via symlinks or path traversal. Combined with the `node_modules` copy-on-create (§12.3), this provides defense-in-depth for worktree isolation.

### 13.3 Network Sandbox

```typescript
network: {
  allowLocalBinding: true,     // services need to bind ports
  allowedDomains: [
    "registry.npmjs.org",      // npm install
    "registry.yarnpkg.com",    // yarn
    "pypi.org",                // pip
    "files.pythonhosted.org",  // pip wheels
  ],
}
```

The network sandbox prevents the agent from making arbitrary outbound requests (e.g., exfiltrating code, downloading malicious packages from untrusted sources). Only explicitly allowed domains are reachable.

### 13.4 Permission Model for Headless Operation

For automated/headless operation, the SDK requires `permissionMode: "bypassPermissions"`. Without this, the SDK would hang waiting for interactive permission prompts.

**Safety invariant:** `bypassPermissions` + `sandbox: { enabled: true }` means:
- All tools are auto-approved (bypass)
- But all Bash commands run inside the sandbox (filesystem + network restrictions)
- `denyWrite` paths are enforced regardless of permission mode

The SDK documentation warns: *"If `permissionMode` is set to `bypassPermissions` and `allowUnsandboxedCommands` is enabled, the model can autonomously execute commands outside the sandbox without any approval prompts."* We explicitly set `allowUnsandboxedCommands: false` (the default) to prevent this.

### 13.5 Configurable Sandbox via `demiurge.yaml`

```yaml
# demiurge.yaml
agent:
  sandbox:
    enabled: true
    allowed_network_domains:
      - registry.npmjs.org
      - registry.yarnpkg.com
    # Additional write-allowed paths beyond the worktree
    extra_write_paths:
      - /tmp
```

---

## 14. Error Handling & Fallback

### 14.1 Agent SDK Not Available

If Claude Code is not installed, fall back to the current `InferenceBackedRepairBackend` (single-shot prompt + PatchApplier). This preserves backwards compatibility:

```scala
val backend: CodeGenerationBackend = if (isClaudeCodeAvailable()) {
  new ClaudeAgentBackend(agentConfig)
} else {
  System.err.println("[warn] Claude Code not found — falling back to single-shot repair")
  System.err.println("[info] Install with: npm install -g @anthropic-ai/claude-code")
  new LegacyRepairBackendAdapter(inferenceBackedRepairBackend)
}
```

### 14.2 Agent Timeout — Graceful Interrupt

If the agent hits its timeout, the SDK provides `Query.interrupt()` for graceful shutdown:

1. **First:** Call `abortController.abort()` (triggers SDK's graceful interrupt)
2. **Wait 10s** for the agent to produce a result message
3. **If no result:** Force-kill the process via `Query.close()`
4. Check `git diff --name-only` in the worktree to see if any files were changed
5. If files were changed: use `enableFileCheckpointing` + `rewindFiles()` to decide whether to keep partial changes or rollback
6. If no files changed: count as a failed attempt

```typescript
// Timeout handling in agentExecute.ts
const timeout = setTimeout(async () => {
  try {
    await q.interrupt();
    // Give 10s for graceful shutdown
    setTimeout(() => q.close(), 10000);
  } catch {
    q.close();
  }
}, params.agentConfig.timeoutMs);
```

### 14.3 Agent Budget Exceeded

The SDK's `maxBudgetUsd` triggers `subtype: "error_max_budget_usd"` in the result message. This is a **clean stop** — the agent has produced partial work that may be valuable. Handle identically to timeout: check for changed files and decide whether to keep or rewind.

### 14.4 Agent Execution Errors

If the SDK returns `subtype: "error_during_execution"`:
1. Capture the `errors` array for logging
2. Count as a failed repair attempt
3. The orchestrator's existing retry logic handles the next attempt

### 14.5 MCP Tool Failures

The in-process MCP server shares the worker process, so it can't crash independently. However, individual tool calls may fail (e.g., `restart_service()` fails because the service binary is missing):
1. The MCP tool handler returns an error message to the agent
2. The agent sees the error and adapts (e.g., tries a different approach)
3. This is a natural part of the agentic loop — not a system failure

If the JSON-RPC callback to Scala times out (e.g., verification takes too long), the tool handler returns a timeout error to the agent. The agent can retry or move on.

### 14.6 File Checkpointing for Rollback

When `enableFileCheckpointing: true`, the SDK tracks all file changes. On timeout or failure, Demiurge can rewind:

```typescript
// After timeout, check if we should keep partial changes
const diff = execSync("git diff --name-only", { cwd: worktreePath });
if (diff.toString().trim()) {
  // Partial changes exist — keep them for re-verification
  // (Demiurge's authoritative verification will decide if they help)
} else {
  // No changes — nothing to verify
}
```

For explicit rollback (e.g., agent made things worse):
```typescript
// Rewind to the state before the agent session started
await q.rewindFiles(firstUserMessageId, { dryRun: false });
```

---

## 15. Configuration

### 15.1 demiurge.yaml Extensions

```yaml
# demiurge.yaml
agent:
  # Backend selection: "agent-sdk" (default when Claude Code available) or "legacy"
  backend: agent-sdk

  # Model override for agent sessions
  model: claude-sonnet-4-20250514

  # Maximum wall-clock time per agent session (ms)
  session_timeout_ms: 300000  # 5 minutes

  # Maximum USD spend per agent session
  max_budget_usd: 1.00

  # Maximum agent turns per session (safety net)
  max_turns: 50

  # Whether to expose Demiurge MCP tools (verification, logs, restart)
  mcp_tools: true

  # Sandbox configuration
  sandbox:
    enabled: true
    allowed_network_domains:
      - registry.npmjs.org
      - registry.yarnpkg.com
    extra_write_paths:
      - /tmp
```

### 15.2 Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `ANTHROPIC_API_KEY` | API key for Agent SDK | (required) |
| `DEMIURGE_AGENT_BACKEND` | Override backend: `agent-sdk` or `legacy` | auto-detect |
| `DEMIURGE_AGENT_TIMEOUT_MS` | Session timeout | 300000 |
| `DEMIURGE_AGENT_MAX_TURNS` | Max turns per session | 50 |
| `DEMIURGE_AGENT_MAX_BUDGET_USD` | Max cost per session | 1.00 |
| `CLAUDE_CODE_USE_BEDROCK` | Use AWS Bedrock instead of Anthropic API | unset |
| `CLAUDE_CODE_USE_VERTEX` | Use Google Vertex AI | unset |
| `CLAUDE_CODE_USE_FOUNDRY` | Use Azure AI Foundry | unset |

---

## 16. Migration Path

### 16.1 Backwards Compatibility

The `RepairBackend` trait is preserved with a `LegacyRepairBackendAdapter` that wraps the new `AgentBackend` interface. Existing code that uses `RepairBackend.proposePatch()` continues to work. The adapter:
1. Invokes the agent
2. Detects changed files via `git diff`
3. Synthesizes a `PatchProposal` from the diff (for persistence/audit trail)
4. Returns `RepairResponse.Success(proposal)`

### 16.2 Feature Flag

The agent backend is gated behind a configuration flag:
```yaml
agent:
  backend: agent-sdk  # or "legacy" for current behavior
```

When `backend: legacy`, the system uses `InferenceBackedRepairBackend` with `ClaudePromptBuilder` — exactly as today.

### 16.3 Gradual Rollout

1. **Phase 1:** Agent backend works alongside legacy. CLI flag `--agent` enables it.
2. **Phase 2:** Agent backend becomes the default when Claude Code is detected.
3. **Phase 3:** Legacy backend moves to `repair-legacy/` module; `repair-claude/` is archived.

---

## 17. Implementation Plan

### Phase 1: TypeScript SDK Agent Backend + In-Process MCP (Priority: High)

**Goal:** Replace single-shot repair with agentic repair via TypeScript SDK, with in-process Demiurge MCP tools for verification.

**Steps:**
1. Add `@anthropic-ai/claude-agent-sdk` and `zod` to `worker/package.json`
2. Implement `worker/src/methods/agentExecute.ts` — Agent SDK `query()` wrapper (§5.3)
3. Implement `worker/src/methods/demiurgeMcpTools.ts` — In-process MCP server (§6.2)
4. Register `agent/execute` JSON-RPC method in `worker/src/index.ts`
5. Create `modules/agent-backend/` module with BUILD.bazel
6. Implement `AgentSystemPromptBuilder` — builds system prompt from RepairContext (no file contents)
7. Implement `AgentExecutor` — invokes worker's `agent/execute` via JSON-RPC
8. Implement `AgentBackend` trait and `ClaudeAgentBackend`
9. Implement `AgentToolRpcHandlers` — Scala-side JSON-RPC handlers for MCP tool callbacks (§6.3)
10. Register `demiurge.verifyRequirements`, `demiurge.getServiceLogs`, `demiurge.restartService`, `demiurge.getRequirementDetails`, `demiurge.checkServiceHealth` in Scala's JSON-RPC server
11. Wire into `RunOrchestrator`: replace `RepairSession.executeWithSession()` call
12. Wire into `BuildPhaseManager.generateCode()`: replace `RepairExecutor.executeRepair()` call
13. Update `WorktreeManager` for agent mode: copy `node_modules` instead of symlink (§12.3)
14. Add feature flag: `agent.backend: agent-sdk | legacy`
15. Add `LegacyRepairBackendAdapter` for backwards compatibility
16. Add CLI `--agent` flag

**Files to create:**
- `worker/src/methods/agentExecute.ts`
- `worker/src/methods/demiurgeMcpTools.ts`
- `modules/agent-backend/BUILD.bazel`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentBackend.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentExecutor.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentSystemPromptBuilder.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentResult.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentConfig.scala`
- `modules/agent-backend/src/main/scala/demiurge/agent/AgentToolRpcHandlers.scala`

**Files to modify:**
- `worker/package.json` (add `@anthropic-ai/claude-agent-sdk`, `zod`)
- `worker/src/index.ts` (register `agent/execute`)
- `modules/orchestrator/src/main/scala/demiurge/orchestrator/RunOrchestrator.scala`
- `modules/orchestrator/src/main/scala/demiurge/orchestrator/BuildPhaseManager.scala`
- `modules/orchestrator/src/main/scala/demiurge/orchestrator/WorktreeManager.scala`
- `modules/orchestrator/BUILD.bazel`

**Tests:**
- `AgentSystemPromptBuilderSuite` — prompt includes requirements, verdicts, failure context; no file contents
- `AgentExecutorSuite` — mock JSON-RPC, verify params and result parsing
- `AgentToolRpcHandlersSuite` — unit test each Scala-side handler
- E2E re-run: Level 4 (repair) and Level 5 (build)

**Estimated LOC:** ~700 new (Scala), ~400 new (TypeScript), ~100 modified

### Phase 2: Polish & Optimization

**Goal:** Session resume, structured output, advanced sandbox tuning.

**Steps:**
1. Implement conversation continuation across attempts (§11.2)
2. Add structured output for reliable result parsing
3. Stream agent progress events via SSE to the Demiurge API
4. Tune sandbox network domains per ecosystem (Node.js, Python, Ruby, etc.)
5. Implement `cp -al` hardlink optimization for `node_modules` copy

**Estimated LOC:** ~300 new

### Phase 3: Cleanup Legacy Code

**Goal:** Remove unused bespoke repair code.

**Steps:**
1. Archive `ClaudeClient.scala` (eliminated)
2. Archive `PatchApplier.scala` (eliminated)
3. Simplify `RepairSession.scala` to wrap agent sessions
4. Move `ClaudePromptBuilder.scala` to legacy module
5. Update documentation

---

## 18. Testing Strategy

### 18.1 Unit Tests

| Test | What it verifies |
|------|-----------------|
| `AgentSystemPromptBuilderSuite` | Prompt includes task, requirements, verdicts, failure context; no file contents; InitialBuild vs Repair modes |
| `AgentExecutorSuite` | JSON-RPC invocation params, result parsing, timeout handling, error handling |
| `AgentToolRpcHandlersSuite` | Each Scala-side MCP tool handler returns correct data, handles errors |

### 18.2 Integration Tests

| Test | What it verifies |
|------|-----------------|
| Worker agent test | `agent/execute` JSON-RPC method in worker with mocked SDK |
| MCP tool roundtrip | MCP tool call → JSON-RPC callback → Scala handler → response |
| Full loop test | Broken repo → agent fixes → Demiurge re-verifies → Pass |

### 18.3 E2E Re-validation

Re-run the existing E2E test suite (son-of-steve repo, demiurge-e2e-test branch):
- **Level 4 (Repair Loop):** Broken health endpoint → agent fixes → verifiers pass
- **Level 5 (Build Mode):** Feature spec → agent generates code → verifiers pass

Both levels should pass with the agent backend, demonstrating parity with the legacy approach plus improved reliability on harder tasks.

### 18.4 Regression Guard

Keep legacy backend available via `--agent=legacy` flag. CI runs both backends on the E2E suite to ensure parity.

---

## 19. Resolved Questions

These were listed as "open questions" in v1 and are now resolved:

### 19.1 Claude CLI Availability → Resolved

Claude Code must be installed. Fail fast with a helpful message, fall back to legacy backend. Not auto-installed.

### 19.2 Agent Sandbox Safety → Resolved (§13)

The SDK's built-in `SandboxSettings` is the primary safety mechanism:
- **Filesystem:** `allowWrite` whitelist + `denyWrite` blacklist
- **Network:** `allowedDomains` whitelist + `allowLocalBinding` for services
- **Permission mode:** `bypassPermissions` with sandbox enabled (auto-approve inside sandbox)
- **No unsandboxed commands:** `allowUnsandboxedCommands: false`

### 19.3 Cost Implications → Resolved (§11.1)

The SDK's `maxBudgetUsd` provides native per-session cost control. The `SDKResultMessage` reports `total_cost_usd` for post-session tracking.

### 19.4 Conversation Continuation → Resolved (§11.2)

Session resume strategy: Attempt 1 = fresh, Attempt 2 = resume, Attempt 3+ = fresh with summaries. SDK's `resume: sessionId` option is used directly.

### 19.5 Authoritative vs Advisory Verification → Resolved (§6.5, §12.2)

Agent's `verify_requirements()` calls are advisory. Orchestrator always performs authoritative re-verification after the agent completes. The critical ordering (edit → restart → verify) is enforced via system prompt instructions and tool descriptions.

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

- **Agent SDK overview:** https://platform.claude.com/docs/en/agent-sdk/overview
- **TypeScript SDK reference:** https://platform.claude.com/docs/en/agent-sdk/typescript
- **Permissions:** https://platform.claude.com/docs/en/agent-sdk/permissions
- **MCP integration:** https://platform.claude.com/docs/en/agent-sdk/mcp
- **Custom tools:** https://platform.claude.com/docs/en/agent-sdk/custom-tools
- **File checkpointing:** https://platform.claude.com/docs/en/agent-sdk/file-checkpointing
- **Hooks:** https://platform.claude.com/docs/en/agent-sdk/hooks
- **Sandbox:** TypeScript SDK reference → Sandbox Configuration section

## Appendix C: Example Agent Session (Expected Flow)

A concrete example of what a repair session looks like with the new architecture:

```
[Demiurge] Verification failed: 3 of 7 requirements failed
[Demiurge] Invoking Claude Agent SDK for repair attempt 1...
[Demiurge] Sandbox: enabled (write: /worktrees/run-abc/, network: npmjs.org)
[Demiurge] Budget: $1.00 max, 50 turns max, 5m timeout

[Agent] Checking service health via check_service_health()...
[Agent] Service api-server: status=stopped

[Agent] Reading server logs via get_service_logs("api-server")...
[Agent] Found: "Error: Cannot find module 'cors'"

[Agent] The server is crashing because 'cors' is imported but not installed.
[Agent] Running: npm install cors
[Agent] ✓ cors@2.8.5 installed (sandboxed: network allowed for registry.npmjs.org)

[Agent] Restarting service via restart_service("api-server")...
[Agent] Service restarted, healthy.

[Agent] Running verify_requirements() to check current state...
[Agent] Results: 4/7 Pass, 3/7 Fail
[Agent]   - REQ-002: GET /api/health returns 200 → got 500 (still failing)
[Agent]   - REQ-004: ✓ Pass
[Agent]   - REQ-005: ✓ Pass

[Agent] 2 issues fixed by installing cors. Health endpoint still fails.
[Agent] Reading src/routes/health.ts...
[Agent] Found the bug: health endpoint throws because db.ping() is not awaited.
[Agent] Editing src/routes/health.ts: adding 'await' before db.ping()

[Agent] Restarting service via restart_service("api-server")...
[Agent] Service restarted, healthy.

[Agent] Running verify_requirements() to check fixes...
[Agent] Results: 7/7 Pass ✓

[Agent] All requirements now pass. Summary: installed missing 'cors' dependency
        and fixed async bug in health endpoint.

[Demiurge] Agent completed. Session: abc123, cost: $0.18, turns: 12
[Demiurge] Changed files: package.json, package-lock.json, src/routes/health.ts
[Demiurge] Performing authoritative re-verification (full environment reset)...
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

The agent approach solves both issues in a single attempt because it can read logs, install dependencies, restart services, and verify its own work — all within a sandboxed worktree.
