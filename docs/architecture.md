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
│  16 tables · TaskRun · Attempt · Verdict · Event ·       │
│  Artifact · FailurePacket · Patch · RuntimePlan · ...    │
└──────────────────────────────────────────────────────────┘
```

## Run State Machine

A run progresses through these states (defined in `RunStatus` enum, 21 values):

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

- **21 enums** — `RunStatus`, `AttemptStatus`, `VerdictStatus`, `FailureClass`, `VerifierType`, `ArtifactType`, `ServiceKind`, `StartupMode`, `AuthMode`, `RunMode`, `ResetStrategy`, `InferenceProvider`, etc.
- **79+ case classes** — `TaskRun`, `Attempt`, `RequirementVerdict`, `SystemEvent`, `ArtifactRecord`, `RuntimePlan`, `RuntimeSnapshot`, `RequirementGraph`, `FailurePacket`, `InferenceRequest`/`Response`, `BrowserAction`, `Assertion`, `Observation`, etc.
- **JSON codecs** — circe semiauto derivation for all DTOs
- **ExecutionBudgetDefaults** — default budget values (max attempts, timeouts, disk limits)

### Persistence (`modules/persistence`)

SQLite WAL-mode database with 16 tables (defined in `V001__initial.sql`):

- **Database** — connection factory with WAL mode, busy timeout, and pragmas
- **Migrator** — schema migration runner
- **TransactionManager** — atomic transaction wrapper
- **Repos** — `TaskRunRepo`, `AttemptRepo`, `VerdictRepo`, `EventRepo`, `ArtifactRecordRepo`, `FailurePacketRepo`, `PatchRepo`, `RepoInspectionReportRepo`, `RequirementGraphRepo`, `RuntimePlanRepo`, `RuntimeSnapshotRepo`

Data is stored at `<repo>/.demiurge/demiurge.db`.

### Orchestrator (`modules/orchestrator`)

The heart of the system — drives the run state machine:

- **RunOrchestrator** — main execution loop; takes pluggable inspector, compiler, planner, supervisor, repair backend, and browser executor
- **RunTransitionManager** — enforces persist-before-side-effects; publishes events to SSE listeners
- **AttemptManager** — creates and manages verification attempts
- **RepairManager** — builds failure inputs, repair contexts; persists failure packets and patch records
- **ResumeManager** — maps interrupted run states to resumption points
- **SignalHandler** — registers JVM shutdown hooks for SIGINT/SIGTERM; persists `Interrupted` status
- **TimeoutEnforcer** — tracks run-level and attempt-level timeouts via `RunClock`
- **LockManager** — file-based run locking (one active run per repo)
- **WorktreeManager** — creates and removes isolated git worktrees
- **StructuredLogger** — JSON log lines to stderr with event emission

### CLI (`modules/cli`)

Entry point: `demiurge.cli.Main` → `CliApp.run(args)`.

11 commands, hand-rolled arg parser (no external dependency):

| Command | Description |
|---------|-------------|
| `run` | Execute a full verification run |
| `plan` | Plan without executing |
| `resume` | Resume an interrupted run |
| `status` | Show run status or list recent runs |
| `inspect-run` | Detailed run inspection with verdicts/artifacts |
| `open-artifact` | Access run artifacts |
| `explain-failure` | Explain verification failures |
| `cancel` | Cancel an active run |
| `clean` | Clean up old runs and artifacts |
| `doctor` | Check system prerequisites |
| `init-manifest` | Generate a starter `demiurge.yaml` |

Output supports `--format human` (default) and `--format json`.

Exit codes: 0=success, 1=exhausted, 2=cancelled, 3=errored, 4=input error, 5=concurrent run conflict, 10=resume failed.

### Local API (`modules/local-api`)

HTTP server on `127.0.0.1:19440` using `com.sun.net.httpserver` (JDK built-in, no external deps).

Endpoints: `GET /health`, `GET /runs/{id}`, `POST /runs`, `GET /runs/{id}/plan`, `GET /runs/{id}/attempts`, `GET /runs/{id}/attempts/{n}/verdicts`, `GET /runs/{id}/artifacts`, `GET /runs/{id}/artifacts/{id}/content`, `POST /runs/{id}/resume`, `POST /runs/{id}/cancel`, `GET /runs/{id}/events` (SSE).

All responses use a JSON envelope (`ApiEnvelope`). The SSE endpoint streams `SystemEvent` objects in real-time.

### Verification Engine (`modules/verification-engine`)

Generates and executes verifiers from a `RequirementGraph`:

- **Verifier types** — `HttpVerifier`, `TcpVerifier`, `ExecVerifier`, `LogContainsVerifier`, `StateVerifier`, `BrowserFlowVerifier`
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
- **Methods** — `initialize`, `executeBrowserFlow` (navigate, actions, assertions, artifact capture), `executeAuthBootstrap` (form login, API login, static token, dev bypass), `executeApiRequest`, `capturePageSnapshot`, `cancel`, `shutdown`, `ping`

### Repair Pipeline

1. **FailureAnalyzer** (`modules/failure-analysis`) — LLM-backed analysis with rule-based fallback (confidence 0.3)
2. **RepairBackend** trait (`modules/repair-api`) — sync interface receiving `FailurePacket`, returning `PatchProposal`
3. **ClaudeRepairBackend** (`modules/repair-claude`) — Claude API client, prompt builder, JSON response parser
4. **PatchApplier** (`modules/repair-api`) — applies file edits, new files, deletions to worktree; stages via `git add`
5. **RepairExecutor** (`modules/repair-api`) — orchestrates packet → backend → apply

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
   b. RequirementCompiler: requirements.yaml + selectors.yaml → RequirementGraph
   c. EnvironmentPlanner: inspection + graph → RuntimePlan
   d. RuntimeSupervisor: boots services, runs readiness probes, seeds fixtures
   e. VerificationEngine: generates verifiers → executes → aggregates verdicts
   f. If failed + repair backend available:
      - FailureAnalyzer produces FailurePacket
      - RepairBackend proposes PatchProposal
      - PatchApplier writes changes to worktree
      - RuntimeSupervisor restarts environment
      - VerificationEngine re-runs verification
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
| Testing (TS) | Jest 29.7 + ts-jest |
| Functional | Cats 2.12.0, Shapeless 2.3.12 |
