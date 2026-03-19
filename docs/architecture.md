# Architecture

Demiurge is a verifier-first orchestration platform for automating last-mile web development tasks. This document describes the system design, module responsibilities, state machine, and data flow.

## Design Principles

1. **Verifier-first** — every task completion claim is backed by executable verifiers that produce structured verdicts
2. **Persist-before-side-effects** — every state transition is written to SQLite before any side effect executes (Spec §4.1)
3. **Isolated execution** — each run operates in a dedicated git worktree with its own artifact directory and lock file
4. **Structured observability** — all events are typed, timestamped, and persisted; available via SSE streaming
5. **Budget enforcement** — inference tokens, artifact disk, repair attempts, and timeouts are all bounded

## System Overview

```
┌──────────────────────────────────────────────────────────┐
│                      CLI (demiurge)                      │
│   Main → CliApp → CommandParsers → Command handlers      │
├──────────────────────────────────────────────────────────┤
│                  Local API Server                         │
│   127.0.0.1:19440 — REST + SSE (com.sun.net.httpserver) │
├──────────────────────────────────────────────────────────┤
│                     Orchestrator                          │
│   RunOrchestrator · RunTransitionManager · AttemptManager │
│   RepairManager · ResumeManager · SignalHandler           │
│   TimeoutEnforcer · LockManager · WorktreeManager         │
│   StructuredLogger                                        │
├─────────┬──────────┬───────────┬─────────────────────────┤
│  Repo   │Requirement│ Environ- │    Verification          │
│Inspector│ Compiler  │  ment    │      Engine              │
│         │           │ Planner  │ HTTP/TCP/Exec/Log/State  │
│         │           │          │ BrowserFlow (via worker)  │
├─────────┴──────────┴───────────┼─────────────────────────┤
│      Runtime Supervisor        │   Worker Protocol        │
│  (boot, teardown, fixtures,    │  (stdio JSON-RPC 2.0)   │
│   readiness probes)            │  WorkerProcessManager    │
├────────────────────────────────┼─────────────────────────┤
│  Failure Analysis              │   Browser Worker (TS)    │
│  Inference Service             │  Playwright · Artifacts  │
│  Repair API · Claude Backend   │  Auth Bootstrap          │
│  Artifact Store · Evidence     │  Page Snapshots          │
├────────────────────────────────┴─────────────────────────┤
│               Persistence (SQLite WAL mode)               │
│  17 tables · TaskRun · Attempt · Verdict · Event ·       │
│  Artifact · FailurePacket · Patch · RuntimePlan · ...    │
└──────────────────────────────────────────────────────────┘
```

## Run State Machine

A run progresses through these states (defined in `RunStatus` enum, 23 values including build mode states):

```
Created
  → InspectingRepo
    → CompilingRequirements
      → PlanningEnvironment
        → BootstrappingEnvironment
          ├─→ EnvironmentFailed (terminal if boot fails)
          └─→ SeedingFixtures
                → BootstrappingAuth (if auth configured)
                  → ReadyToVerify
                    → Verifying
                      ├─→ Succeeded (all pass)
                      ├─→ Exhausted (no repair backend or already repaired)
                      └─→ AnalyzingFailure
                            → PlanningRepair
                              → Repairing
                                ├─→ RepairFailed → Exhausted
                                └─→ SoftResettingEnvironment
                                      → ReadyToVerify
                                        → Verifying
                                          ├─→ Succeeded
                                          └─→ Exhausted

Special transitions:
  Any state → Cancelled (via cancel command or API)
  Any state → Interrupted (via SIGINT/SIGTERM, resumable)
```

### Transition Invariant

Every state transition follows the persist-before-side-effects pattern:

1. Write new status + event to SQLite (in a transaction)
2. Publish event to SSE listeners
3. Execute the side effect for the target state

This ensures that if the process crashes during a side effect, the persisted state accurately reflects the last committed transition.

## Module Details

### Core Model (`modules/core-model`)

Foundation types shared across all modules:

- **22 enums** — `RunStatus`, `AttemptStatus`, `VerdictStatus`, `FailureClass`, `VerifierType`, `ArtifactType`, `ServiceKind`, `StartupMode`, `AuthMode`, `RunMode`, `ResetStrategy`, `InferenceProvider`, `GenerationMode`, etc.
- **79+ case classes** — `TaskRun`, `Attempt`, `RequirementVerdict`, `SystemEvent`, `ArtifactRecord`, `RuntimePlan`, `RuntimeSnapshot`, `RequirementGraph`, `FailurePacket`, `InferenceRequest`/`Response`, `BrowserAction`, `Assertion`, `Observation`, etc.
- **JSON codecs** — circe semiauto derivation for all DTOs
- **ExecutionBudgetDefaults** — default budget values (max attempts, timeouts, disk limits)

### Persistence (`modules/persistence`)

SQLite WAL-mode database with 17 tables (defined in `V001__initial.sql` + `V002__build_mode.sql`):

- **Database** — connection factory with WAL mode, busy timeout, and pragmas
- **Migrator** — schema migration runner
- **TransactionManager** — atomic transaction wrapper
- **Repos** — `TaskRunRepo`, `AttemptRepo`, `VerdictRepo`, `EventRepo`, `ArtifactRecordRepo`, `FailurePacketRepo`, `PatchRepo`, `RepoInspectionReportRepo`, `RequirementGraphRepo`, `RuntimePlanRepo`, `RuntimeSnapshotRepo`

Data is stored at `<repo>/.demiurge/demiurge.db`.

### Orchestrator (`modules/orchestrator`)

The heart of the system — drives the run state machine:

- **RunOrchestrator** — main execution loop; takes pluggable inspector, compiler, planner, supervisor, repair backend, and browser executor; supports resume via `resumeFromStatus` parameter that skips completed phases
- **RunTransitionManager** — enforces persist-before-side-effects; publishes events to SSE listeners
- **AttemptManager** — creates and manages verification attempts
- **RepairManager** — builds failure inputs, repair contexts; persists failure packets and patch records
- **ResumeManager** — maps interrupted run states to resumption points; prepares runs for smart resume
- **ResumeDataLoader** — loads persisted inspection reports, requirement graphs, runtime plans, patch history, and attempt counts from the database to reconstruct state for resumed runs
- **SignalHandler** — registers JVM shutdown hooks for SIGINT/SIGTERM; persists `Interrupted` status
- **TimeoutEnforcer** — tracks run-level and attempt-level timeouts via `RunClock`
- **LockManager** — file-based run locking (one active run per repo)
- **WorktreeManager** — creates and removes isolated git worktrees
- **StructuredLogger** — JSON log lines to stderr with event emission

### CLI (`modules/cli`)

Entry point: `demiurge.cli.Main` → `CliApp.run(args)`.

13 commands, hand-rolled arg parser (no external dependency):

| Command | Description |
|---------|-------------|
| `run` | Execute a full verification run (auto-generates config if missing) |
| `build` | Build mode — generate code from a task description (sugar for `run --mode build`) |
| `plan` | Plan without executing |
| `resume` | Resume an interrupted run |
| `status` | Show run status or list recent runs |
| `inspect-run` | Detailed run inspection with verdicts/artifacts |
| `open-artifact` | Access run artifacts |
| `explain-failure` | Explain verification failures |
| `cancel` | Cancel an active run |
| `clean` | Clean up old runs and artifacts |
| `doctor` | Check system prerequisites |
| `init` | Generate `demiurge.yaml` and `requirements.yaml` (deterministic or `--smart` agentic). Also aliased as `init-manifest`. |
| `serve` | Start persistent backend server (desktop app sidecar) — REST + WebSocket on configurable ports |

Output supports `--format human` (default) and `--format json`.

Exit codes: 0=success, 1=exhausted, 2=cancelled, 3=errored, 4=input error, 5=concurrent run conflict, 10=resume failed.

### Local API (`modules/local-api`)

HTTP server on `127.0.0.1:19440` using `com.sun.net.httpserver` (JDK built-in, no external deps).

Endpoints: `GET /health`, `GET /runs/{id}`, `POST /runs`, `GET /runs/{id}/plan`, `GET /runs/{id}/attempts`, `GET /runs/{id}/attempts/{n}/verdicts`, `GET /runs/{id}/artifacts`, `GET /runs/{id}/artifacts/{id}/content`, `POST /runs/{id}/resume`, `POST /runs/{id}/cancel`, `GET /runs/{id}/events` (SSE).

All responses use a JSON envelope (`ApiEnvelope`). The SSE endpoint streams `SystemEvent` objects in real-time.

### Verification Engine (`modules/verification-engine`)

Generates and executes verifiers from a `RequirementGraph`:

- **Verifier types** — `HttpVerifier`, `TcpVerifier`, `ExecVerifier`, `LogContainsVerifier`, `StateVerifier`, `BrowserFlowVerifier`, `AgentBrowserVerifier`
- **VerifierGenerator** — maps `RequirementGraph` nodes to executable verifiers
- **VerifierExecutor** — runs HTTP/TCP/exec/log/state verifiers in-process
- **VerdictAggregator** — aggregates individual outcomes into an overall verdict
- **BrowserFlowVerifier** — dispatched to the TypeScript worker via `WorkerProcessManager`; supports selector fallbacks

### Worker Protocol (`modules/worker-protocol`)

Scala-side client for the TypeScript browser worker:

- **JsonRpc** — parse/serialize JSON-RPC 2.0 messages
- **WorkerMessages** — typed request/response builders for all worker methods
- **WorkerClient** — stdio reader/writer with request/response correlation
- **WorkerProcessManager** — spawn, initialize, ping, executeBrowserFlow, executeAuthBootstrap, executeApiRequest, capturePageSnapshot, cancel, shutdown; crash detection and restart budget

### Browser Worker (`worker/`)

TypeScript + Playwright process communicating via stdio JSON-RPC 2.0:

- **RPC server** — newline-delimited JSON, method registration
- **BrowserManager** — launches Chromium, creates fresh contexts per task, reuses browser process
- **ArtifactWriter** — temp-file-then-rename, SHA-256 checksums, gzip compression >1MB
- **Methods** — `initialize`, `executeBrowserFlow` (navigate, actions, assertions, artifact capture), `executeAuthBootstrap` (form login, API login, static token, dev bypass), `executeApiRequest`, `capturePageSnapshot`, `cancel`, `shutdown`, `ping`, `agent/execute` (agentic operations via Claude Code SDK with MCP tools)

### Config Resolver (`modules/config-resolver`)

Layered configuration resolution:

- **Layer 1: Explicit YAML** — loads `demiurge.yaml` and `requirements.yaml` from the repo root
- **Layer 2: Cached Inference** — loads previously inferred config from `.demiurge/inferred/`
- **No Layer 3** — heuristic inference was removed; if no config is found, `NoConfigError` directs the user to run `demiurge init --smart`
- **InferredConfigWriter** — serializes `ResolvedConfig` back to manifest YAML for caching

### Agent Backend (`modules/agent-backend`)

Bridge between the Scala orchestrator and the TypeScript worker for agentic operations. **This is the default repair mechanism** when `ANTHROPIC_API_KEY` is set:

- **AgentBackend** trait — defines the interface for agent-powered code generation and repair
- **ClaudeAgentBackend** — concrete implementation that delegates to the Claude Code SDK via the TypeScript worker
- **AgentExecutor** — sends `agent/execute` JSON-RPC requests to the worker, which runs Claude Agent SDK `query()` with MCP tools for verification, service health checks, and log access
- **AgentToolRpcHandlers** — handles callback notifications from the worker (verify_requirements, restart_service, get_service_logs, check_service_health, get_requirement_details) and agent progress events
- **AgentSystemPromptBuilder** — builds system/user prompts with failure context for the agent

### Repair Pipeline

**Primary path (Agent Backend — default when `ANTHROPIC_API_KEY` is set):**

The Claude Code agent receives failure context and has multi-turn access to the codebase with file editing, shell commands, and Demiurge MCP tools (verify, restart, logs). It iterates autonomously until the fix is applied.

**Legacy fallback (when no worker is available):**

1. **FailureAnalyzer** (`modules/failure-analysis`) — LLM-backed analysis with rule-based fallback (confidence 0.3)
2. **RepairBackend** trait (`modules/repair-api`) — sync interface receiving `FailurePacket`, returning `PatchProposal`
3. **ClaudeRepairBackend** (`modules/repair-claude`) — Claude API client, prompt builder, JSON response parser
4. **PatchApplier** (`modules/repair-api`) — applies file edits, new files, deletions to worktree; stages via `git add`
5. **RepairExecutor** (`modules/repair-api`) — orchestrates packet → backend → apply

Set `DEMIURGE_AGENT_BACKEND=none` to force the legacy path.

### Inference Service (`modules/inference`)

All LLM calls go through `InferenceService`:

- Budget enforcement per component per run
- Response caching (keyed by model + messages hash)
- Timeout and retry (max 1 retry, 2s backoff)
- Replay mode (serve from cache only, no live API calls)
- Usage tracking and auditing
- Pluggable `InferenceBackend` (mock for testing, real API for production)

### Artifact Store (`modules/artifact-store`)

- **ArtifactSink** — writes artifacts with temp-file-then-rename, SHA-256 checksums, gzip >1MB, disk budget enforcement, essential/non-essential classification
- **EvidenceCollector** — registers worker artifacts, writes verdict/failure-packet/report artifacts
- **ArtifactPaths** — canonical directory structure for artifact storage
- **Prompt package assembly** — priority sorting, truncation, omission, never-include types

## Data Flow

### Typical Run

```
1. CLI parses args → creates TaskRun → persists to SQLite
2. Creates git worktree → acquires file lock
3. Starts local API server on :19440
4. Orchestrator begins state machine:
   a. RepoInspector analyzes repo (file types, dependencies, impact map)
   b. ConfigResolver: demiurge.yaml (explicit or cached) → ResolvedConfig
   c. RequirementCompiler: requirements.yaml + selectors.yaml → RequirementGraph
   d. EnvironmentPlanner: inspection + graph → RuntimePlan
   d. RuntimeSupervisor: boots services, runs readiness probes, seeds fixtures
   e. VerificationEngine: generates verifiers → executes → aggregates verdicts
   f. If failed + agent/repair backend available:
      - Agent backend (default): Claude Code agent edits files, restarts services, re-verifies autonomously
      - Legacy fallback: FailureAnalyzer → RepairBackend → PatchApplier → restart → re-verify
5. Final status persisted (Succeeded/Exhausted)
6. Final report artifact written
7. Worker shutdown → API server stop → lock released
```

### Event Flow

```
Orchestrator → RunTransitionManager.transition()
  → SQLite (persist status + event)
  → eventListener callback
    → EventStream.publish()
      → SSE subscribers (GET /runs/{id}/events)
```

## CI/CD

GitHub Actions workflow (`.github/workflows/ci.yml`) runs on every push to `main` and on pull requests:

1. **Bazel build** — `bazel build //...` (all 43+ targets)
2. **Bazel tests** — `bazel test //...` (all 20+ test targets, including unit and integration)
3. **Worker tests** — `npm ci && npm test` in the `worker/` directory

Bazel caching is configured via `bazel-contrib/setup-bazel` for fast incremental builds.

## Testing Strategy

- **Unit tests** — per-module, exercising individual components with stubs (e.g., `OrchestratorSuite`, `ResumeSuite`)
- **End-to-end integration tests** — `EndToEndSuite` exercises the full orchestration pipeline with in-memory SQLite and configurable stub backends (`EndToEndTestHarness`). Covers: full pass, build mode, multi-attempt repair, exhaustion, auth bootstrap, resume from checkpoint, and signal interruption.
- **Resume tests** — `ResumeSuite` validates that `ResumeDataLoader` correctly loads persisted state and that `RunOrchestrator` skips completed phases when resuming.

All tests are deterministic and fast — no external network calls, no real Docker containers, no real LLM API calls.

## Desktop Application

Demiurge includes a native **desktop GUI** built with **Tauri v2 + React**. It provides full CLI parity through an interactive interface with real-time observability, artifact browsing, and configuration editing.

### Architecture

```
Tauri v2 Application
├── React Frontend (system WebView)
│   ├── Dashboard — run history, quick actions, system health
│   ├── Run Detail — live pipeline stepper, attempt tabs, timers
│   ├── Environment — service topology (React Flow), boot timeline, log tailing
│   ├── Verification — verdict cards, screenshot gallery
│   ├── Agent — transcript stream, tool call cards, cost tracker, diff viewer
│   ├── Artifacts — tree browser, content viewers (JSON, diff, markdown, logs, screenshots)
│   ├── Config — manifest editor (Monaco), requirements editor, budget editor
│   └── Settings — preferences, onboarding wizard
├── Tauri Rust Core (thin layer)
│   ├── SidecarManager — spawn/manage JVM backend process
│   ├── System tray — status indicator, quick actions
│   ├── Window management — detached log windows
│   └── Tauri plugins — shell, dialog, notification, store, window-state
└── Scala Backend Sidecar
    └── `demiurge serve` — persistent HTTP + WebSocket server
```

### Sidecar Integration

The desktop app communicates with the Scala backend via a sidecar process:

1. On launch, Tauri spawns the JVM sidecar (`demiurge serve --port 19440 --ws-port 19441`)
2. The frontend connects via HTTP REST + WebSocket for real-time events
3. SSE/WebSocket streams provide live pipeline updates, agent transcripts, and log tailing
4. The sidecar is packaged as a fat JAR via `desktop/scripts/package-sidecar.sh`

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Build system | Bazel 9.0+ with Bzlmod |
| Scala version | 2.13.18 |
| Java version | 17 (remotejdk) |
| JSON | circe 0.14.10 |
| YAML | SnakeYAML 2.2 |
| Database | SQLite 3.45+ (via sqlite-jdbc) |
| HTTP server | com.sun.net.httpserver (JDK built-in) |
| Testing (Scala) | MUnit 1.0.3 |
| Worker runtime | Node.js (TypeScript, ES2022) |
| Browser automation | Playwright 1.42.1 |
| Agent SDK | @anthropic-ai/claude-code ^1.0.128 |
| MCP | @modelcontextprotocol/sdk ^1.27.1, @playwright/mcp ^0.0.28 |
| Testing (TS) | Jest 29.7 + ts-jest |
| Functional | Cats 2.12.0, Shapeless 2.3.12 |
| Desktop framework | Tauri v2 (Rust core) |
| Desktop frontend | React 19, TypeScript, Tailwind CSS v4 |
| Desktop state | Zustand 5, TanStack Query 5, TanStack Router 1 |
| Desktop UI | Monaco Editor, React Flow, xterm.js, Framer Motion, Lucide |
