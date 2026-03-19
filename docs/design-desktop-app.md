# Demiurge Desktop Application — Design Specification

> **Status:** Draft v1  
> **Author:** Design session, 2026-03-18  
> **Scope:** Full interactive desktop GUI replacing/complementing the CLI  
> **Target:** Hand-off ready for long-running coding agent implementation

---

## Table of Contents

1. [Overview & Goals](#1-overview--goals)
2. [Design Principles](#2-design-principles)
3. [System Architecture](#3-system-architecture)
4. [Technology Stack](#4-technology-stack)
5. [Project Structure](#5-project-structure)
6. [Backend API Extensions](#6-backend-api-extensions)
7. [Real-Time Communication Protocol](#7-real-time-communication-protocol)
8. [Frontend State Management](#8-frontend-state-management)
9. [Screen Specifications](#9-screen-specifications)
10. [Component Library](#10-component-library)
11. [Data Flow Diagrams](#11-data-flow-diagrams)
12. [Tauri Shell Integration](#12-tauri-shell-integration)
13. [Packaging & Distribution](#13-packaging--distribution)
14. [Implementation Phases](#14-implementation-phases)
15. [Testing Strategy](#15-testing-strategy)
16. [CLI Migration & Coexistence](#16-cli-migration--coexistence)
17. [Performance Budget](#17-performance-budget)
18. [Accessibility](#18-accessibility)
19. [Future Considerations](#19-future-considerations)

---

## §1. Overview & Goals

Demiurge is currently a pure CLI tool (11 commands, Scala backend, TypeScript worker) that orchestrates verifier-first web task automation. This document specifies a **desktop GUI application** that serves as a full interactive replacement for the CLI while preserving CLI compatibility.

### 1.1 What Exists Today

| Layer | Technology | LOC |
|-------|-----------|-----|
| CLI entry point | Scala (`CliApp`, 11 command handlers) | ~2,200 |
| Local HTTP API | `com.sun.net.httpserver` on `:19440` | ~450 |
| SSE event stream | `EventStream` → `RunTransitionManager` | ~200 |
| Orchestrator | `RunOrchestrator` state machine (21 states) | ~900 |
| Persistence | SQLite WAL, 16 tables, 13 repo classes | ~2,500 |
| Worker | TypeScript/Playwright, stdio JSON-RPC | ~1,470 |
| Agent backend | Claude Code SDK via worker | ~700 |
| Core model | 79+ case classes, 21 enums | ~1,800 |

The existing `LocalApiServer` already exposes REST endpoints and SSE streaming. The desktop app builds on top of this infrastructure rather than replacing it.

### 1.2 Goals

1. **Full CLI parity** — every CLI command has a UI equivalent
2. **Real-time observability** — live pipeline progress, service log tailing, agent transcript streaming
3. **Rich artifact browsing** — screenshots, diffs, traces, reports rendered inline
4. **Interactive configuration** — visual editors for `demiurge.yaml`, `requirements.yaml`, and budget policies
5. **Multi-run management** — view history, compare runs, resume interrupted runs
6. **Zero new runtime dependencies for existing users** — CLI continues to work independently

### 1.3 Non-Goals (v1)

- Cloud/remote execution (all local)
- Multi-user collaboration
- Mobile support
- Plugin/extension system
- Custom theme editor

---

## §2. Design Principles

1. **Backend-first data** — The Scala backend remains source of truth. The UI is a client of the local API + SQLite. No business logic in frontend.
2. **Event-driven UI** — All live updates flow through SSE/WebSocket. No polling during active runs.
3. **Progressive disclosure** — High-level pipeline status by default; drill into details on demand.
4. **Offline-capable** — Works without internet (except LLM calls). Historical runs browsable from SQLite anytime.
5. **CLI coexistence** — Run started from CLI is visible in UI (and vice versa). Same SQLite + artifacts.
6. **Native feel** — System window chrome, keyboard shortcuts, system tray. Respect OS light/dark mode.
7. **Minimal footprint** — Tauri v2 with system webview. No bundled Chromium. Target <20MB installed (excluding JVM sidecar).

---

## §3. System Architecture

### 3.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Tauri v2 Application                       │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              React Frontend (System WebView)           │  │
│  │  Dashboard │ Pipeline │ Logs │ Artifacts │ Config      │  │
│  │                                                        │  │
│  │  State: Zustand (client) + TanStack Query (server)     │  │
│  └────────────────────────┬───────────────────────────────┘  │
│                           │                                   │
│  ┌────────────────────────┼───────────────────────────────┐  │
│  │     Tauri Rust Core    │  (thin layer)                  │  │
│  │  Sidecar mgmt · System tray · Window mgmt · FS plugin  │  │
│  └────────────────────────┼───────────────────────────────┘  │
└───────────────────────────┼───────────────────────────────────┘
                            │ HTTP + SSE + WebSocket
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              Demiurge Scala Backend (Sidecar)                 │
│  LocalApiServer :19440 (extended) + WebSocket :19441 (new)   │
│  Orchestrator · Persistence (SQLite) · Worker (JSON-RPC)     │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Key Architectural Decisions

**Decision 1: Tauri sidecar, not embedded JVM.** The Scala backend runs as a Tauri sidecar process. Tauri v2's `shell` plugin manages its lifecycle. Backend binary is identical whether invoked from CLI or desktop.

**Decision 2: HTTP API as the bridge, not Tauri IPC.** Frontend communicates via HTTP (REST + SSE + WS), not Tauri IPC. Reasons: API already exists; enables connecting to separately-running backend; decouples frontend dev (can dev in browser); avoids duplicating serialization. Tauri IPC only for native OS ops.

**Decision 3: Single SQLite database, shared between CLI and desktop.** Both read/write `.demiurge/demiurge.db`. `LockManager` prevents concurrent runs.

**Decision 4: Backend always runs while app is open.** Sidecar stays alive in system tray. Enables instant responses for browsing history, editing config, starting runs without JVM cold-start.

### 3.3 Process Model

```
Tauri App Process
  ├── System WebView (React frontend)
  ├── Tauri Rust runtime (tray, menus, window mgmt)
  └── Sidecar: demiurge-server (JVM)
        ├── LocalApiServer :19440 (extended)
        ├── WebSocketServer :19441 (new)
        ├── Orchestrator threads
        └── TypeScript Worker (child process, stdio JSON-RPC)
              ├── Playwright browser
              └── Claude Agent SDK
```

---

## §4. Technology Stack

### 4.1 Frontend

| Component | Technology | Version | Rationale |
|-----------|-----------|---------|-----------|
| Shell | Tauri v2 | 2.x | 10× lighter than Electron, native webview, sidecar support |
| UI framework | React | 19.x | Richest ecosystem, TS expertise from worker |
| Language | TypeScript | 5.x | Type safety, shared types with worker |
| Build tool | Vite | 6.x | Fast HMR, Tauri plugin support |
| Styling | Tailwind CSS | 4.x | Utility-first, consistent design system |
| Components | shadcn/ui | latest | Beautiful, accessible, copy-paste ownership |
| State (client) | Zustand | 5.x | Lightweight, no boilerplate, persistence middleware |
| State (server) | TanStack Query | 5.x | Cache, refetch, optimistic updates for REST |
| Routing | TanStack Router | 1.x | Type-safe routing with search params |
| Code editor | Monaco Editor | `@monaco-editor/react` | Syntax highlighting, diff view, YAML editing |
| Terminal/Logs | xterm.js | 5.x | ANSI color rendering, virtual scrolling, search |
| Charts | Recharts | 2.x | Composable, React-native charting |
| Graph viz | ReactFlow | 12.x | Requirement DAG, service topology |
| Icons | Lucide React | latest | Clean, consistent, 1000+ icons |
| Date/time | date-fns | 3.x | Lightweight date formatting |
| Markdown | react-markdown | latest | Render agent summaries, failure explanations |
| Diff viewer | react-diff-viewer-continued | latest | Inline/side-by-side diff for patches |
| Animation | Framer Motion | 11.x | Pipeline transitions, status animations |

### 4.2 Backend Extensions (Scala)

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| WebSocket server | Java-WebSocket (`org.java-websocket`) | Lightweight, JDK-compatible |
| Log streaming | Ring buffer per service + WS broadcast | Already have `ServiceProcessManager.getLogLines()` |
| Agent transcript | Forward `agent/toolUse` + `agent/progress` to WS | Already captured in `AgentToolRpcHandlers` |

### 4.3 Tauri Plugins

| Plugin | Purpose |
|--------|---------|
| `@tauri-apps/plugin-shell` | Sidecar management (start/stop/restart JVM backend) |
| `@tauri-apps/plugin-fs` | Direct file reads for large artifacts (bypass HTTP) |
| `@tauri-apps/plugin-dialog` | Native file/folder picker for repo selection |
| `@tauri-apps/plugin-notification` | OS notifications (run completed, failure) |
| `@tauri-apps/plugin-autostart` | Optional launch-on-login |
| `@tauri-apps/plugin-updater` | Auto-update distribution |
| `@tauri-apps/plugin-store` | Persistent user preferences |
| `@tauri-apps/plugin-window-state` | Remember window position/size |

---

## §5. Project Structure

```
desktop/                              # New top-level directory
├── src-tauri/                        # Tauri Rust backend
│   ├── src/
│   │   ├── main.rs                   # Entry point, sidecar setup
│   │   ├── tray.rs                   # System tray menu
│   │   ├── commands.rs               # Tauri IPC commands (native-only ops)
│   │   └── sidecar.rs                # JVM sidecar lifecycle management
│   ├── Cargo.toml
│   ├── tauri.conf.json               # Window config, sidecar, plugins
│   ├── capabilities/
│   │   └── default.json              # Permission capabilities
│   └── icons/                        # App icons (macOS icns, etc.)
│
├── src/                              # React frontend
│   ├── main.tsx                      # React entry point
│   ├── App.tsx                       # Root component, router setup
│   │
│   ├── api/                          # Backend API client layer
│   │   ├── client.ts                 # HTTP client (fetch wrapper)
│   │   ├── sse.ts                    # SSE EventSource manager
│   │   ├── websocket.ts             # WebSocket connection manager
│   │   ├── endpoints.ts             # Typed endpoint definitions
│   │   └── types.ts                 # API response types (mirrors Scala DTOs)
│   │
│   ├── stores/                       # Zustand stores
│   │   ├── app.store.ts             # Global app state (active repo, theme)
│   │   ├── run.store.ts             # Active run state (status, events)
│   │   ├── logs.store.ts            # Service log buffers (ring buffer)
│   │   ├── agent.store.ts           # Agent transcript state
│   │   └── preferences.store.ts     # User prefs (persisted via Tauri store)
│   │
│   ├── hooks/                        # React hooks
│   │   ├── useRun.ts                # TanStack Query for run data
│   │   ├── useAttempts.ts           # TanStack Query for attempts
│   │   ├── useVerdicts.ts           # TanStack Query for verdicts
│   │   ├── useArtifacts.ts          # TanStack Query for artifacts
│   │   ├── useSSE.ts               # SSE subscription hook
│   │   ├── useWebSocket.ts         # WebSocket subscription hook
│   │   ├── useServiceLogs.ts       # Service log streaming hook
│   │   ├── useAgentTranscript.ts   # Agent transcript streaming hook
│   │   └── useBackendHealth.ts     # Backend sidecar health polling
│   │
│   ├── screens/                      # Top-level route screens
│   │   ├── Dashboard/
│   │   │   ├── DashboardScreen.tsx
│   │   │   ├── RunHistoryTable.tsx
│   │   │   ├── SystemHealthWidget.tsx
│   │   │   └── QuickActions.tsx
│   │   ├── RunDetail/
│   │   │   ├── RunDetailScreen.tsx   # Main layout: pipeline + tabs
│   │   │   ├── PipelineStepper.tsx   # Horizontal state machine viz
│   │   │   ├── AttemptTabs.tsx       # Per-attempt tab strip
│   │   │   ├── RunTimers.tsx         # Run/attempt elapsed timers
│   │   │   └── RunActions.tsx        # Cancel/Resume/Restart buttons
│   │   ├── Verification/
│   │   │   ├── VerificationPanel.tsx
│   │   │   ├── VerifierMatrix.tsx    # Requirements × verifiers grid
│   │   │   ├── VerdictCard.tsx       # Expandable verdict detail
│   │   │   ├── RequirementGraph.tsx  # DAG visualization (ReactFlow)
│   │   │   └── AggregateBar.tsx      # Summary progress bar
│   │   ├── Agent/
│   │   │   ├── AgentPanel.tsx
│   │   │   ├── TranscriptStream.tsx  # Live agent message stream
│   │   │   ├── ToolCallCard.tsx      # Expandable MCP tool call card
│   │   │   ├── AgentDiffViewer.tsx   # File changes by agent
│   │   │   └── AgentCostTracker.tsx  # Token/cost real-time display
│   │   ├── Environment/
│   │   │   ├── EnvironmentPanel.tsx
│   │   │   ├── ServiceTopology.tsx   # Service dependency graph
│   │   │   ├── ServiceCard.tsx       # Per-service status card
│   │   │   ├── LogTailer.tsx         # xterm.js log viewer per service
│   │   │   └── BootTimeline.tsx      # Service boot sequence timeline
│   │   ├── Artifacts/
│   │   │   ├── ArtifactBrowser.tsx
│   │   │   ├── ArtifactTree.tsx      # Tree view grouped by type
│   │   │   ├── ContentViewer.tsx     # Polymorphic content renderer
│   │   │   ├── ScreenshotGallery.tsx # Image lightbox with diff
│   │   │   └── DiffViewer.tsx        # Monaco diff for patches
│   │   ├── Config/
│   │   │   ├── ConfigScreen.tsx
│   │   │   ├── ManifestEditor.tsx    # Monaco YAML + form toggle
│   │   │   ├── RequirementsEditor.tsx # Structured form per req
│   │   │   ├── BudgetEditor.tsx      # Policy/budget controls
│   │   │   ├── ProvenanceView.tsx    # Config source annotations
│   │   │   └── SmartInitWizard.tsx   # Agent-based config gen UI
│   │   ├── Inspection/
│   │   │   ├── InspectionPanel.tsx
│   │   │   ├── RepoOverview.tsx      # Languages, frameworks, files
│   │   │   ├── ImpactMap.tsx         # Changed files impact viz
│   │   │   └── InferenceTable.tsx    # ScoredInference with bars
│   │   ├── NewRun/
│   │   │   ├── NewRunDialog.tsx      # Modal for starting a run
│   │   │   ├── BuildDialog.tsx       # Modal for build mode
│   │   │   ├── ModeSelector.tsx      # Full/Build/PlanOnly/VerifyOnly
│   │   │   └── BudgetOverrides.tsx   # Optional budget overrides
│   │   └── Settings/
│   │       ├── SettingsScreen.tsx
│   │       ├── ApiKeySection.tsx     # ANTHROPIC_API_KEY management
│   │       ├── BackendSection.tsx    # Agent backend selection
│   │       ├── PathsSection.tsx      # Default repo path, worker path
│   │       └── AppearanceSection.tsx # Theme, font size, log limit
│   │
│   ├── components/                   # Shared reusable components
│   │   ├── ui/                       # shadcn/ui primitives
│   │   ├── StatusBadge.tsx
│   │   ├── PriorityIndicator.tsx
│   │   ├── VerifierTypeIcon.tsx
│   │   ├── ServiceKindIcon.tsx
│   │   ├── ArtifactTypeIcon.tsx
│   │   ├── FailureClassBadge.tsx
│   │   ├── ConfidenceBar.tsx
│   │   ├── ElapsedTimer.tsx
│   │   ├── CostDisplay.tsx
│   │   ├── JsonViewer.tsx
│   │   ├── YamlEditor.tsx
│   │   ├── CommandPalette.tsx
│   │   └── Sidebar.tsx
│   │
│   ├── lib/                          # Utilities
│   │   ├── format.ts                # Date, duration, byte formatters
│   │   ├── colors.ts                # Status → color mappings
│   │   ├── constants.ts             # API URLs, polling intervals
│   │   └── log-parser.ts            # Parse structured logs, ANSI
│   │
│   └── styles/
│       └── globals.css               # Tailwind base + CSS variables
│
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts
├── tailwind.config.ts
├── postcss.config.js
└── components.json                   # shadcn/ui config
```

---

## §6. Backend API Extensions

The existing `LocalApiServer` endpoints remain unchanged. New endpoints support UI-specific needs. All follow the same `ApiEnvelope` JSON format.

### 6.1 Existing Endpoints (unchanged)

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/health` | Backend health check |
| `GET` | `/runs/{id}` | Get run details |
| `POST` | `/runs` | Start a new run |
| `GET` | `/runs/{id}/plan` | Get runtime plan |
| `GET` | `/runs/{id}/attempts` | List attempts |
| `GET` | `/runs/{id}/attempts/{n}/verdicts` | Get verdicts for attempt |
| `GET` | `/runs/{id}/artifacts` | List artifacts (paginated) |
| `GET` | `/runs/{id}/artifacts/{id}/content` | Get artifact content |
| `POST` | `/runs/{id}/resume` | Resume interrupted run |
| `POST` | `/runs/{id}/cancel` | Cancel active run |
| `GET` | `/runs/{id}/events` | SSE event stream |

### 6.2 New REST Endpoints

#### Run Management

| Method | Path | Body/Query | Response | Purpose |
|--------|------|-----------|----------|---------|
| `GET` | `/runs` | `?status=&limit=&offset=&sort=&order=` | Paginated `TaskRun` list | Dashboard history table |
| `GET` | `/runs/active` | — | Active `TaskRun` or 404 | Quick check on app launch |
| `POST` | `/runs/{id}/restart` | `{ "task": "<override>" }` | New run ID | Re-run with same config |

#### Configuration

| Method | Path | Body/Query | Response | Purpose |
|--------|------|-----------|----------|---------|
| `GET` | `/config` | `?repo=<path>` | `ResolvedConfig` + `ConfigProvenance` | Config viewer |
| `PUT` | `/config/manifest` | Raw YAML string | Validation result + path | Save manifest from editor |
| `PUT` | `/config/requirements` | Raw YAML string | Validation result + path | Save requirements from editor |
| `POST` | `/config/validate` | `{ manifest, requirements }` | `{ valid, errors[], warnings[] }` | Live editor validation |
| `POST` | `/config/init-smart` | `{ repoPath, taskHint? }` | SSE stream → final config | Smart init wizard |

#### Inspection

| Method | Path | Response | Purpose |
|--------|------|----------|---------|
| `GET` | `/runs/{id}/inspection` | `RepoInspectionReport` JSON | Inspection panel |
| `GET` | `/runs/{id}/requirement-graph` | `RequirementGraph` JSON (nodes + edges) | Requirement DAG viz |
| `GET` | `/runs/{id}/feature-plan` | `FeaturePlan` JSON (build mode) | Build mode plan display |

#### Environment & Services

| Method | Path | Body/Query | Response | Purpose |
|--------|------|-----------|----------|---------|
| `GET` | `/runs/{id}/environment` | — | `RuntimeSnapshot` + `RuntimePlan` | Environment panel |
| `GET` | `/runs/{id}/services` | — | Service status list | Service cards |
| `GET` | `/runs/{id}/services/{svcId}/logs` | `?lines=&follow=true` | SSE log stream or JSON | Log tailing |
| `POST` | `/runs/{id}/services/{svcId}/restart` | — | Restart result | Manual service restart |

#### Agent

| Method | Path | Body/Query | Response | Purpose |
|--------|------|-----------|----------|---------|
| `GET` | `/runs/{id}/agent/transcript` | `?follow=true` | SSE message stream or JSON | Agent transcript |
| `GET` | `/runs/{id}/agent/cost` | — | `{ inputTokens, outputTokens, costUsd, numTurns, durationMs }` | Cost tracker |

#### Failure Analysis

| Method | Path | Response | Purpose |
|--------|------|----------|---------|
| `GET` | `/runs/{id}/attempts/{n}/failure-packet` | `FailurePacket` JSON | Failure analysis card |
| `GET` | `/runs/{id}/attempts/{n}/patches` | `PatchRecord` list with diff content | Patch browser |

#### System

| Method | Path | Body | Response | Purpose |
|--------|------|------|----------|---------|
| `GET` | `/system/doctor` | — | `{ checks: [{ name, status, message }] }` | System health widget |
| `GET` | `/system/preferences` | — | Preferences JSON | Settings screen |
| `PUT` | `/system/preferences` | Preferences JSON | Updated prefs | Save settings |
| `GET` | `/system/repos` | — | Recent repo paths list | Repo selector |

### 6.3 New API Module Structure

```
modules/local-api/src/main/scala/demiurge/api/
├── LocalApiServer.scala          # existing, extended with new contexts
├── Routes.scala                  # existing core routes
├── ApiEnvelope.scala             # existing
├── EventStream.scala             # existing
├── ConfigRoutes.scala            # NEW: config CRUD + validation + smart init
├── InspectionRoutes.scala        # NEW: inspection, requirement graph, feature plan
├── EnvironmentRoutes.scala       # NEW: services, snapshots, log streaming
├── AgentRoutes.scala             # NEW: agent transcript, cost tracking
├── FailureRoutes.scala           # NEW: failure packets, patch records
├── SystemRoutes.scala            # NEW: doctor, preferences, repos
├── WebSocketServer.scala         # NEW: bidirectional streaming (§7)
└── LogStreamManager.scala        # NEW: per-service log ring buffer + broadcast
```

### 6.4 CORS Configuration

Since Tauri webview loads from `tauri://localhost`, the backend must add CORS headers:

```
Access-Control-Allow-Origin: tauri://localhost
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
```

Added as a wrapper in `LocalApiServer` that intercepts all requests before dispatching to route handlers. For dev mode (browser), also allow `http://localhost:1420` (Vite dev server default).

---

## §7. Real-Time Communication Protocol

The UI needs three categories of real-time data, each served by a different transport.

### 7.1 SSE (Server-Sent Events) — Run-Level Events

**Existing:** `GET /runs/{id}/events` streams `SystemEvent` objects. Already works.

**Extended event types** beyond `state_transition`:

| Event Type | Payload | Source |
|------------|---------|--------|
| `state_transition` | `{ from_status, to_status }` | `RunTransitionManager` (existing) |
| `verification_started` | `{ attemptNumber, verifierCount }` | `VerificationEngine` (new) |
| `verdict_produced` | `{ requirementId, verifierId, status }` | `VerificationEngine` (new) |
| `service_status_changed` | `{ serviceId, oldStatus, newStatus }` | `ServiceProcessManager` (new) |
| `agent_tool_use` | `{ toolName, inputSummary }` | `AgentToolRpcHandlers` (existing capture) |
| `agent_progress` | `{ text }` | `AgentToolRpcHandlers` (existing capture) |
| `agent_completed` | `{ sessionId, filesChanged, costUsd }` | `RunOrchestrator` (new) |
| `artifact_created` | `{ artifactId, artifactType, relativePath }` | `ArtifactSink` (new) |
| `repair_started` | `{ attemptNumber, backend }` | `RunOrchestrator` (new) |
| `repair_completed` | `{ outcome, filesChanged }` | `RunOrchestrator` (new) |
| `boot_progress` | `{ serviceId, phase, message }` | `RuntimeSupervisor` (new) |

**Implementation:** New event producers call the same `EventStream.publish()`. Frontend subscribes once per active run.

### 7.2 WebSocket — Service Log & Agent Transcript Streaming

Service logs require bidirectional communication (subscribe/unsubscribe to specific services) and high throughput (hundreds of lines/second). Agent transcripts similarly need live streaming of tool calls and text blocks.

**New: `WebSocketServer` on `:19441`**

#### Client → Server Messages

```typescript
// Subscribe to service logs
{ "type": "subscribe_logs", "runId": string, "serviceId": string, "lines": number }

// Unsubscribe from service logs
{ "type": "unsubscribe_logs", "serviceId": string }

// Subscribe to agent transcript
{ "type": "subscribe_agent", "runId": string }

// Unsubscribe from agent transcript
{ "type": "unsubscribe_agent" }
```

#### Server → Client Messages

```typescript
// Initial log backfill (sent after subscribe_logs)
{ "type": "log_backfill", "serviceId": string, "lines": string[] }

// Live log line
{ "type": "log_line", "serviceId": string, "line": string, "timestamp": string }

// Agent message (text, tool use, tool result, progress)
{ "type": "agent_message", "messageType": "text" | "tool_use" | "tool_result" | "progress" | "error",
  "data": { "toolName"?: string, "inputSummary"?: string, "text"?: string, "isError"?: boolean },
  "timestamp": string }

// Heartbeat (every 15s)
{ "type": "heartbeat", "timestamp": string }
```

**Implementation:** `LogStreamManager` maintains a ring buffer (default 10,000 lines) per service. `ServiceProcessManager` already captures stdout/stderr — we add a broadcast hook. `WebSocketServer` manages client subscriptions and fans out new lines. Agent messages are forwarded from `AgentToolRpcHandlers` notifications (`agent/toolUse`, `agent/progress`) through the same WS broadcast.

### 7.3 Polling — Historical Data

For data that doesn't change in real-time:
- TanStack Query `staleTime: 30_000` for run history list
- TanStack Query `staleTime: Infinity` for completed run data (immutable)
- TanStack Query `staleTime: 5_000` for active run supplemental data (backs up SSE)

### 7.4 Connection Lifecycle

```
App Launch
  ├── Poll GET /health with exponential backoff (max 10s)
  ├── If no backend → start sidecar, wait for health
  ├── Open WebSocket to :19441
  └── If active run detected → subscribe SSE /runs/{id}/events

During Active Run
  ├── SSE: /runs/{id}/events (all state transitions + new events)
  ├── WS: log subscriptions per service the user is viewing
  └── WS: agent transcript subscription if agent panel is open

App Backgrounded (system tray)
  ├── Close WS and SSE connections
  ├── Backend sidecar continues running
  └── On foreground: reconnect, fetch missed events from EventRepo

Run Completes
  ├── SSE auto-closes (EventStream sends run_ended sentinel)
  ├── WS log subscriptions auto-close (services torn down)
  ├── Final data fetched via REST (TanStack Query invalidation)
  └── OS notification sent if app is backgrounded
```

### 7.5 Reconnection Strategy

| Transport | Strategy |
|-----------|----------|
| HTTP (REST) | TanStack Query built-in retry (3 attempts, exponential backoff) |
| SSE | Auto-reconnect with `EventSource` retry. On reconnect, fetch events from `EventRepo` since last known `event_id` to fill gaps. |
| WebSocket | Reconnect with exponential backoff (1s, 2s, 4s, max 30s). Re-subscribe to all active subscriptions on reconnect. Show "Reconnecting..." banner in UI. |

---

## §8. Frontend State Management

### 8.1 Store Architecture

```
React Components
      │
      ├── Zustand Stores (client state)
      │     ├── AppStore (active repo, theme, backend status)
      │     ├── RunStore (current run status from SSE events)
      │     ├── LogsStore (per-service log ring buffers from WS)
      │     ├── AgentStore (agent transcript from WS)
      │     └── PreferencesStore (persisted to Tauri store)
      │
      └── TanStack Query Cache (server state)
            ├── Runs (list, detail, active)
            ├── Attempts (list per run)
            ├── Verdicts (list per attempt)
            ├── Artifacts (list per run, content per artifact)
            ├── Inspection (report, requirement graph, feature plan)
            ├── Environment (snapshot, services)
            ├── Config (resolved config per repo)
            └── System (doctor, preferences, repos)
```

### 8.2 Zustand Store Definitions

**`app.store.ts`**
```typescript
interface AppState {
  activeRepoPath: string | null;
  activeRunId: string | null;
  backendStatus: 'connecting' | 'connected' | 'disconnected' | 'error';
  backendVersion: string | null;
  sidebarCollapsed: boolean;
  activeScreen: ScreenId;
  commandPaletteOpen: boolean;

  setActiveRepo: (path: string) => void;
  setActiveRun: (runId: string | null) => void;
  setBackendStatus: (status: AppState['backendStatus']) => void;
}
```

**`run.store.ts`**
```typescript
interface RunState {
  currentStatus: RunStatus | null;
  currentAttempt: number;
  events: SystemEvent[];              // ring buffer, last 500 events
  latestVerdicts: Map<string, VerdictStatus>;  // requirementId → latest

  runStartedAt: number | null;
  attemptStartedAt: number | null;
  sseConnected: boolean;

  handleEvent: (event: SystemEvent) => void;
  reset: () => void;
}
```

**`logs.store.ts`**
```typescript
interface LogsState {
  buffers: Map<string, LogBuffer>;
  subscriptions: Set<string>;

  appendLine: (serviceId: string, line: string) => void;
  backfill: (serviceId: string, lines: string[]) => void;
  subscribe: (serviceId: string) => void;
  unsubscribe: (serviceId: string) => void;
  clear: (serviceId: string) => void;
}

interface LogBuffer {
  lines: string[];                    // ring buffer, max 10,000
  totalLineCount: number;
  paused: boolean;                    // user paused auto-scroll
}
```

**`agent.store.ts`**
```typescript
interface AgentState {
  messages: AgentMessage[];
  toolCalls: ToolCallEntry[];
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  numTurns: number;
  startedAt: number | null;

  appendMessage: (msg: AgentMessage) => void;
  appendToolCall: (call: ToolCallEntry) => void;
  updateCost: (cost: AgentCostUpdate) => void;
  reset: () => void;
}

type AgentMessage = {
  id: string;
  type: 'text' | 'tool_use' | 'tool_result' | 'progress' | 'error';
  content: string;
  timestamp: number;
  toolName?: string;
  inputSummary?: string;
  isError?: boolean;
};
```

**`preferences.store.ts`**
```typescript
interface PreferencesState {
  theme: 'system' | 'light' | 'dark';
  fontSize: number;                   // 12-20
  logLineLimit: number;               // ring buffer size, default 10000
  autoConnectOnLaunch: boolean;
  showSystemTrayNotifications: boolean;
  defaultRepoPath: string | null;
  defaultRunMode: RunMode;
  defaultMaxAttempts: number;
  defaultRunTimeoutMs: number;
  anthropicApiKeySet: boolean;        // flag only; key in secure store
}
```

### 8.3 TanStack Query Key Factory

```typescript
const queryKeys = {
  runs: {
    all:    ['runs'] as const,
    list:   (filters: RunFilters) => ['runs', 'list', filters] as const,
    detail: (runId: string) => ['runs', runId] as const,
    active: ['runs', 'active'] as const,
  },
  attempts: {
    list:   (runId: string) => ['runs', runId, 'attempts'] as const,
  },
  verdicts: {
    list:   (runId: string, attemptNum: number) =>
              ['runs', runId, 'attempts', attemptNum, 'verdicts'] as const,
  },
  artifacts: {
    list:    (runId: string, filters?: ArtifactFilters) =>
               ['runs', runId, 'artifacts', filters] as const,
    content: (runId: string, artifactId: string) =>
               ['runs', runId, 'artifacts', artifactId, 'content'] as const,
  },
  inspection: {
    report:      (runId: string) => ['runs', runId, 'inspection'] as const,
    graph:       (runId: string) => ['runs', runId, 'requirement-graph'] as const,
    featurePlan: (runId: string) => ['runs', runId, 'feature-plan'] as const,
  },
  environment: {
    snapshot: (runId: string) => ['runs', runId, 'environment'] as const,
    services: (runId: string) => ['runs', runId, 'services'] as const,
  },
  config: {
    resolved: (repoPath: string) => ['config', repoPath] as const,
  },
  system: {
    doctor:      ['system', 'doctor'] as const,
    preferences: ['system', 'preferences'] as const,
    repos:       ['system', 'repos'] as const,
  },
} as const;
```

### 8.4 SSE → Store Integration

The `useSSE` hook connects to `GET /runs/{id}/events` and dispatches events to both the RunStore and TanStack Query cache:

```typescript
// Pseudocode for useSSE hook
function useSSE(runId: string) {
  useEffect(() => {
    const source = new EventSource(`http://localhost:19440/runs/${runId}/events`);
    const runStore = useRunStore.getState();
    const queryClient = useQueryClient();

    source.onmessage = (e) => {
      const event: SystemEvent = JSON.parse(e.data);
      runStore.handleEvent(event);

      // Invalidate relevant queries based on event type
      switch (event.eventType) {
        case 'state_transition':
          queryClient.invalidateQueries({ queryKey: queryKeys.runs.detail(runId) });
          break;
        case 'verdict_produced':
          queryClient.invalidateQueries({ queryKey: ['runs', runId, 'attempts'] });
          break;
        case 'artifact_created':
          queryClient.invalidateQueries({ queryKey: ['runs', runId, 'artifacts'] });
          break;
        case 'service_status_changed':
          queryClient.invalidateQueries({ queryKey: queryKeys.environment.services(runId) });
          break;
      }
    };

    return () => source.close();
  }, [runId]);
}
```

---

## §9. Screen Specifications

### 9.1 Dashboard Screen

**Route:** `/`

```
┌──────────────────────────────────────────────────────────┐
│ Sidebar │                  Dashboard                      │
│         │                                                 │
│ 🏠 Home │  ┌────────────────────────────────────────────┐│
│ 📊 Runs │  │  System Health          Quick Actions       ││
│ ⚙ Config│  │  ● Node 22 ✅           [New Run]          ││
│ 🔧 Sett.│  │  ● Docker ✅            [Build Feature]    ││
│         │  │  ● Playwright ✅         [Smart Init]       ││
│         │  │  ● API Key ✅            [Doctor]           ││
│         │  └────────────────────────────────────────────┘│
│         │                                                 │
│         │  ┌────────────────────────────────────────────┐│
│         │  │  Recent Runs                                ││
│         │  │ ┌──────┬───────┬──────┬──────┬──────────┐  ││
│         │  │ │Status│ Task  │ Mode │ Time │ Verdict   │  ││
│         │  │ ├──────┼───────┼──────┼──────┼──────────┤  ││
│         │  │ │ 🟢   │ Fix.. │ Full │ 4m23s│ Pass 5/5 │  ││
│         │  │ │ 🔴   │ Add.. │ Build│ 12m  │ Exhausted│  ││
│         │  │ │ 🟡   │ Verif.│ Full │ 2m.. │ Running  │  ││
│         │  │ │ ⚪   │ Plan..│ Plan │ 0m12s│ —        │  ││
│         │  │ └──────┴───────┴──────┴──────┴──────────┘  ││
│         │  │  Showing 4 of 23 runs  [Load More]          ││
│         │  └────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────┘
```

**Components:**

- **`SystemHealthWidget`** — Calls `GET /system/doctor`. Green/red/yellow dots per prerequisite. Auto-refreshes on app focus.
- **`QuickActions`** — Opens `NewRunDialog`, `BuildDialog`, `SmartInitWizard`. Disabled with tooltip if prerequisites fail.
- **`RunHistoryTable`** — Calls `GET /runs?limit=20&sort=created_at&order=desc`. Sortable columns. Click row → Run Detail. Active run row pulses. Status badge + mini verdict aggregate per row.

**Interactions:**

- Click run row → `/runs/{id}`
- Click "New Run" → `NewRunDialog` modal
- Click "Build Feature" → `BuildDialog` modal
- Click "Smart Init" → `SmartInitWizard` modal
- `Cmd+K` → `CommandPalette`

### 9.2 Run Detail Screen

**Route:** `/runs/{id}`

```
┌──────────────────────────────────────────────────────────┐
│ Sidebar │ Run: "Fix health endpoint"  ⏱ 2m 14s          │
│         │ Status: Verifying      [Cancel] [Resume]       │
│         │                                                 │
│         │ ┌────────────────────────────────────────────┐ │
│         │ │           Pipeline Stepper                  │ │
│         │ │ ○───○───○───●───○───○───○───○              │ │
│         │ │ Insp Comp Plan Boot Verify Anlz Repr Done  │ │
│         │ │                    ▲ (current)              │ │
│         │ └────────────────────────────────────────────┘ │
│         │                                                 │
│         │ Attempt: [1] [2*] [3]                          │
│         │                                                 │
│         │ ┌────────────────────────────────────────────┐ │
│         │ │ Tab: [Verification][Agent][Env][Artifacts] │ │
│         │ │      [Inspection][Events][Failure]          │ │
│         │ ├────────────────────────────────────────────┤ │
│         │ │                                              │ │
│         │ │         (Tab content area)                   │ │
│         │ │                                              │ │
│         │ └────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

**`PipelineStepper` — State-to-step mapping:**

| Step | States Included |
|------|----------------|
| Inspect | `InspectingRepo` |
| Compile | `CompilingRequirements` |
| Plan | `PlanningEnvironment`, `PlanningFeature` |
| Build | `GeneratingCode` (build mode only, hidden otherwise) |
| Boot | `BootstrappingEnvironment`, `SeedingFixtures`, `BootstrappingAuth` |
| Verify | `ReadyToVerify`, `Verifying` |
| Analyze | `AnalyzingFailure`, `PlanningRepair` |
| Repair | `Repairing`, `RepairFailed` |
| Reset | `SoftResettingEnvironment`, `RebuildingEnvironment`, `PlanningRerun` |
| Done | `Succeeded`, `Exhausted`, `Cancelled`, `Interrupted`, `EnvironmentFailed` |

Step visual states: `completed` (green fill), `active` (blue pulse animation), `pending` (gray outline), `failed` (red fill), `skipped` (dimmed). Loop indicator when repair cycle repeats (attempt 2+).

**Attempt Switcher:** Horizontal tabs `Attempt 1 | Attempt 2 | ...` with verdict badges. Selecting an attempt switches tab content below.

### 9.3 Verification Tab

```
┌────────────────────────────────────────────────────────┐
│ Aggregate: ██████████░░░░  4/7 passed                   │
│ (2 non-required failed, 1 flake)                        │
│                                                         │
│ ┌─────────────────────────────────────┬────────────┐   │
│ │ 🔴 health-endpoint (Required)       │ ✅ Pass     │ ▶ │
│ │    HttpApiContract: GET /health     │ 234ms      │   │
│ ├─────────────────────────────────────┼────────────┤   │
│ │ 🟡 auth-api (Important)             │ ❌ Fail     │ ▶ │
│ │    HttpApiContract: GET /api/me     │ 401        │   │
│ ├─────────────────────────────────────┼────────────┤   │
│ │ ⚪ dashboard-flow (NiceToHave)      │ ⏱ Timeout   │ ▶ │
│ │    BrowserFlow: /dashboard          │            │   │
│ └─────────────────────────────────────┴────────────┘   │
│                                                         │
│ Expanded: auth-api                                      │
│ ┌──────────────────────────────────────────────────┐   │
│ │ Status: Fail (401 Unauthorized)                   │   │
│ │ Duration: 234ms  Confidence: ████░ 0.95           │   │
│ │ Failure: BackendContractFailure                   │   │
│ │ Observations:                                     │   │
│ │   - Expected status 200, got 401                  │   │
│ │   - Body: {"error":"unauthorized"}                │   │
│ │ Evidence: [screenshot.png] [api-response.json]    │   │
│ └──────────────────────────────────────────────────┘   │
│                                                         │
│ [View Requirement Graph] → ReactFlow DAG modal          │
└────────────────────────────────────────────────────────┘
```

**`VerdictCard` expanded fields** (from `RequirementVerdict`):

- `status` → `StatusBadge` (Pass/Fail/Inconclusive/Blocked/Timeout/Flake)
- `executionDurationMs` → formatted duration
- `confidence` → `ConfidenceBar` (0.0–1.0 horizontal bar)
- `failureClass` → `FailureClassBadge` with color + tooltip
- `failureMessage` → monospace text
- `observations` → list of `Observation` items with expected vs actual highlighting
- `evidenceRefs` → clickable artifact links → opens in ContentViewer

**Requirement Graph Modal (ReactFlow):**

- Nodes: `RequirementNode` with priority-colored border, category icon
- Edges: `DependencyEdge` styled by type (Hard=solid, Soft=dashed, Ordering=dotted)
- Click node → scrolls to that requirement in the list
- Nodes colored by verdict status when viewing a specific attempt

### 9.4 Agent Tab

```
┌────────────────────────────────────────────────────────┐
│ Agent Session                          Cost: $0.42      │
│ Status: Running  Turns: 12/50         Tokens: 45k/12k  │
│ ⏱ 2m 34s                                               │
│                                                         │
│ ┌──────────────────────────────────────────────────┐   │
│ │ Transcript                            [Pause ⏸]  │   │
│ │                                                   │   │
│ │ 💬 Looking at the health endpoint...              │   │
│ │                                                   │   │
│ │ 🔧 Read("/src/routes/health.js")                  │   │
│ │    ▶ 42 lines read                               │   │
│ │                                                   │   │
│ │ 🔧 Edit("/src/routes/health.js")                  │   │
│ │    ▶ Line 15: res.status(500) → 200              │   │
│ │    [View Full Diff]                               │   │
│ │                                                   │   │
│ │ 🔧 restart_service("api-server")                  │   │
│ │    ▶ Status: running, probe: passed               │   │
│ │                                                   │   │
│ │ 🔧 verify_requirements()                          │   │
│ │    ▶ 5/5 passed                                   │   │
│ │                                                   │   │
│ │ ✅ All requirements now pass. Fixed the health     │   │
│ │    endpoint status code.                          │   │
│ └──────────────────────────────────────────────────┘   │
│                                                         │
│ ┌──────────────┐  ┌───────────────────────────────┐    │
│ │ Tool Summary  │  │ Files Changed                 │    │
│ │ Read: 8       │  │ src/routes/health.js  [Diff]  │    │
│ │ Edit: 2       │  └───────────────────────────────┘    │
│ │ Bash: 3       │                                       │
│ │ Verify: 2     │                                       │
│ │ Restart: 1    │                                       │
│ └──────────────┘                                        │
└────────────────────────────────────────────────────────┘
```

**`TranscriptStream`:**

- Receives messages via WebSocket `subscribe_agent`
- Message type styling: text=chat bubble w/ markdown, tool_use=collapsible card, tool_result=nested under card, progress=subtle status line, error=red border
- Auto-scrolls with Pause toggle
- Searchable (Cmd+F)

**`ToolCallCard`:**

- Collapsible per tool invocation
- Header: tool icon + name + timestamp + duration
- Body: input params (JSON tree) + output (formatted per tool type)
- `Edit` → inline diff viewer
- `verify_requirements` → mini verdict table
- `get_service_logs` → truncated log output with "Show All"
- `restart_service` → status badge + log tail

**`AgentCostTracker`:**

- Live counters: input tokens, output tokens, USD, turns, elapsed
- Sparkline chart showing cost accumulation
- Budget bar if `maxBudgetUsd` set

### 9.5 Environment Tab

```
┌────────────────────────────────────────────────────────┐
│ Environment: Ready  Mode: compose  Reset: SoftReset     │
│                                                         │
│ ┌──────────────────────────────────────────────────┐   │
│ │ Service Topology (ReactFlow)                      │   │
│ │                                                   │   │
│ │   ┌──────┐     ┌──────────┐     ┌──────┐        │   │
│ │   │Mongo ├────▶│api-server├────▶│client│        │   │
│ │   │ 🟢   │     │   🟢     │     │  🟡  │        │   │
│ │   │:27017│     │  :4000   │     │:3000 │        │   │
│ │   └──────┘     └──────────┘     └──────┘        │   │
│ └──────────────────────────────────────────────────┘   │
│                                                         │
│ Selected: api-server                                    │
│ ┌──────────────────────────────────────────────────┐   │
│ │ 🟢 Healthy  PID: 42195  Port: 4000               │   │
│ │ Kind: Api  Startup: script  Restarts: 0           │   │
│ │ [Restart] [Open in Browser]                       │   │
│ │                                                   │   │
│ │ Logs:                              [Detach ↗]    │   │
│ │ ┌────────────────────────────────────────────┐   │   │
│ │ │ $ npm start                                │   │   │
│ │ │ Server listening on port 4000              │   │   │
│ │ │ MongoDB connected                          │   │   │
│ │ │ GET /health 200 2ms                        │   │   │
│ │ │ GET /api/me 401 1ms                        │   │   │
│ │ │ ▌                              (scroll)    │   │   │
│ │ └────────────────────────────────────────────┘   │   │
│ └──────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┘
```

**`ServiceTopology` (ReactFlow):**

- Nodes = `ServiceSpec` entries from `RuntimePlan`
- Node: `ServiceKindIcon` + serviceId + status light + port
- Edges from `dependencyServices`
- Node color = service status (green/yellow/red/gray)
- Click node → select, show detail card below
- Real-time updates via SSE `service_status_changed`

**`ServiceCard`:**

- Status badge, PID/container ID, port mappings, startup mode, kind
- Actions: [Restart] → `POST /runs/{id}/services/{svcId}/restart`, [Open in Browser] → system browser
- Readiness probe status with last check time

**`LogTailer` (xterm.js):**

- ANSI-colored terminal output
- Virtual scrolling via xterm.js
- Live auto-scroll with scroll-lock toggle
- Search (Ctrl+F)
- "Detach" → new Tauri window (dedicated log viewer)
- Clear buffer, line count indicator
- Data via WebSocket `log_line` messages
- Initial backfill via `log_backfill` on subscribe

### 9.6 Artifacts Tab

```
┌────────────────────────────────────────────────────────┐
│ Artifacts (47)  Filter: [All Types ▾] Attempt: [All ▾] │
│ Search: [________________]                              │
│                                                         │
│ ┌──────────┬───────────────────────────────────────┐   │
│ │ Tree     │ Content Viewer                         │   │
│ │          │                                        │   │
│ │ ▾ Plan   │ api-response-auth.json                 │   │
│ │   └ plan │ Type: ApiRequestResponse               │   │
│ │ ▾ Logs   │ Size: 1.2 KB  Attempt: 1              │   │
│ │   ├ api  │ ┌──────────────────────────────────┐  │   │
│ │   └ clnt │ │ {                                 │  │   │
│ │ ▾ Scrnsh │ │   "request": {                    │  │   │
│ │   ├ s1   │ │     "method": "GET",              │  │   │
│ │   └ s2   │ │     "url": "/api/me"              │  │   │
│ │ ▾ Diffs  │ │   },                              │  │   │
│ │   └ p1   │ │   "response": {                   │  │   │
│ │ ▾ Report │ │     "status": 401                  │  │   │
│ │   └ fin  │ │   }                               │  │   │
│ └──────────┘ │ }                                  │  │   │
│              └──────────────────────────────────┘  │   │
└────────────────────────────────────────────────────────┘
```

**`ArtifactTree`:**

- Groups by `ArtifactType` with collapsible sections
- Each node: `ArtifactTypeIcon` + filename + size badge
- Click → load content in right panel
- Search filters by filename or type

**`ContentViewer` — polymorphic renderer:**

| ArtifactType | Renderer |
|---|---|
| `Plan`, `StructuredVerdict`, `FailurePacketArtifact` | JSON tree viewer (`JsonViewer`) |
| `ServiceLog`, `StdoutExcerpt`, `StderrExcerpt`, `ConsoleLog` | xterm.js (ANSI colors) |
| `Screenshot` | Image lightbox with zoom/pan |
| `BrowserTrace` | Playwright Trace Viewer iframe or JSON fallback |
| `DomSnapshot`, `AccessibilitySnapshot` | Collapsible tree component |
| `NetworkSummary` | Table (URL, method, status, duration) |
| `ApiRequestResponse` | Side-by-side request/response JSON |
| `DbQueryResult`, `QueueObservation` | Data table |
| `PatchDiff` | Unified/split diff (`react-diff-viewer-continued`) |
| `FinalReport` | Formatted report with sections (markdown) |
| `RepairTranscript` | Collapsible LLM conversation turns |
| `InferenceLog` | Table (model, tokens, latency, cost) |
| `RepoInspectionArtifact` | Formatted inspection report |
| `PromptPackage` | Collapsible prompt sections with token counts |
| `AttemptReport` | Formatted attempt summary |
| `AuthStorageState` | JSON tree (credentials redacted) |
| `StartupTimeline` | Timeline visualization of service boot |

### 9.7 Inspection Tab

```
┌────────────────────────────────────────────────────────┐
│ Repo: /Users/steve/Desktop/son-of-steve                 │
│ Inspected: 2026-03-18 14:23                             │
│                                                         │
│ ┌─────────────────────┐  ┌────────────────────────┐    │
│ │ Languages            │  │ Frameworks              │    │
│ │ ██████ JavaScript 85%│  │ Express ████ 0.92       │    │
│ │ ██ TypeScript 10%    │  │ React ███ 0.78          │    │
│ │ █ CSS 5%             │  │ Jest ██ 0.65            │    │
│ └─────────────────────┘  └────────────────────────┘    │
│                                                         │
│ Candidate Services                                      │
│ ┌──────────────────────────────────────────────────┐   │
│ │ api-server (Api)  conf: 0.95  port: 4000         │   │
│ │   startup: npm start  health: /health             │   │
│ │ mongo (Db)  conf: 0.90  port: 27017              │   │
│ │   startup: docker-compose                         │   │
│ └──────────────────────────────────────────────────┘   │
│                                                         │
│ Impact Map (if changed files present)                   │
│ ┌──────────────────────────────────────────────────┐   │
│ │ Changed: src/routes/health.js                     │   │
│ │ → Affected API handlers: /health (conf: 0.98)     │   │
│ │ → Affected services: api-server (conf: 0.95)      │   │
│ │ → Infra-sensitive: false                          │   │
│ └──────────────────────────────────────────────────┘   │
│                                                         │
│ Manifests Found                                         │
│ ┌──────────────────────────────────────────────────┐   │
│ │ package.json ✅  docker-compose.yaml ✅            │   │
│ │ .env ✅  demiurge.yaml ✅                          │   │
│ └──────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┘
```

**`InferenceTable`:** Renders `ScoredInference[T]` items with value, `ConfidenceBar`, and provenance tag. Used for languages, frameworks, startup commands, health endpoints, DB dependencies, etc.

**`ImpactMap`:** If `changedSurfaceMap` is present, visualizes affected routes, components, API handlers, DB models as tagged items with confidence scores.

### 9.8 Events Tab

```
┌────────────────────────────────────────────────────────┐
│ Events (234)  Filter: [All Types ▾] [All Severity ▾]    │
│                                                         │
│ ┌──────────────────────────────────────────────────┐   │
│ │ 14:23:01  INFO  orchestrator  state_transition    │   │
│ │    Created → InspectingRepo                       │   │
│ │                                                   │   │
│ │ 14:23:05  INFO  inspector  state_transition       │   │
│ │    InspectingRepo → CompilingRequirements          │   │
│ │                                                   │   │
│ │ 14:23:12  INFO  verifier  verification_started    │   │
│ │    Attempt 1, 7 verifiers                         │   │
│ │                                                   │   │
│ │ 14:23:15  WARN  verifier  verdict_produced        │ ▶ │
│ │    auth-api: Fail (401)                           │   │
│ │                                                   │   │
│ │ 14:23:16  INFO  agent  agent_tool_use             │   │
│ │    Read("/src/routes/health.js")                   │   │
│ │                                                   │   │
│ │ 14:24:01  INFO  orchestrator  repair_completed    │   │
│ │    Files changed: 1, outcome: success              │   │
│ └──────────────────────────────────────────────────┘   │
│                                                         │
│ Auto-scroll: [ON]                                       │
└────────────────────────────────────────────────────────┘
```

Chronological feed of all `SystemEvent` records. Each event shows: timestamp, severity badge (INFO=gray, WARN=yellow, ERROR=red), component tag, event type, human-readable message. Expandable to show full JSON payload. Filterable by event type and severity. Auto-scroll with toggle.

### 9.9 Config Screen

**Route:** `/config`

```
┌────────────────────────────────────────────────────────┐
│ Sidebar │ Configuration                                  │
│         │ Repo: /Users/steve/Desktop/son-of-steve [📁]  │
│         │                                                 │
│         │ ┌────────────────────────────────────────────┐ │
│         │ │ Tab: [Manifest] [Requirements] [Budget]    │ │
│         │ ├────────────────────────────────────────────┤ │
│         │ │                                              │ │
│         │ │ Toggle: [Form View] | [YAML View]           │ │
│         │ │                                              │ │
│         │ │ ┌──────────────────────────────────────┐   │ │
│         │ │ │ Monaco YAML editor                    │   │ │
│         │ │ │ (or structured form per tab)          │   │ │
│         │ │ │                                       │   │ │
│         │ │ │ Live validation errors shown inline   │   │ │
│         │ │ └──────────────────────────────────────┘   │ │
│         │ │                                              │ │
│         │ │ Provenance: Explicit ● | Cached ● | Infer ● │ │
│         │ │                                              │ │
│         │ │ [Save] [Validate] [Smart Init ✨]           │ │
│         │ └────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

**`ManifestEditor`:**

- **Form View:** Structured form with sections: App Config (type, rootUrl, apiUrl), Services (add/remove/edit cards per service with kind dropdown, ports, readiness config, env vars), Fixtures (step list with drag-to-reorder), Auth (mode selector, credentials).
- **YAML View:** Monaco editor with YAML language support, syntax highlighting, error squiggles from `POST /config/validate`. Real-time validation on keystroke (debounced 500ms).
- Toggle between form and YAML preserves content. Changes in one view reflect in the other.

**`RequirementsEditor`:**

- **Form View:** Cards per requirement: description textarea, priority dropdown (Required/Important/NiceToHave), category dropdown, verifier spec sub-forms (per verifier type). Add/remove requirements. Drag-to-reorder.
- **YAML View:** Monaco with `requirements.yaml` schema.

**`BudgetEditor`:**

- Sliders + number inputs for: maxAttempts (1–10), runTimeoutMs, attemptTimeoutMs, maxPatchLines, maxArtifactDiskBytes
- Toggle switches: allowGitPush, allowDbDrop
- Inputs: allowedHosts list, browserAllowedOrigins list

**`ProvenanceView`:**

- Shows `ConfigProvenance`: each config value annotated with its source (Explicit=blue, Cached=purple, Inferred=orange, Default=gray)
- Visual diff between what the user wrote and what Demiurge resolved

**`SmartInitWizard`:**

- Step 1: Select repo path (file dialog)
- Step 2: Optional task hint textarea
- Step 3: Agent runs → show real-time transcript (reuses `TranscriptStream` from Agent tab)
- Step 4: Review generated config in ManifestEditor (form + YAML toggle)
- Step 5: Confirm → writes `demiurge.yaml` and `requirements.yaml`

### 9.10 New Run Dialog

Modal dialog, opened from Dashboard or Cmd+K.

```
┌────────────────────────────────────────────┐
│ New Run                              [✕]   │
│                                            │
│ Repo: [/Users/steve/Desktop/app     ] [📁] │
│                                            │
│ Task: [                                  ] │
│       [Fix the health endpoint to return ] │
│       [200 instead of 500                ] │
│                                            │
│ Mode: ○ Full  ○ Build  ○ PlanOnly          │
│       ○ VerifyOnly  ○ InspectOnly          │
│                                            │
│ ▶ Budget Overrides (optional)              │
│   Max Attempts: [5]                        │
│   Run Timeout:  [30m]                      │
│   Agent Backend: [claude-agent-sdk ▾]      │
│                                            │
│ ▶ Git Options (optional)                   │
│   Branch name: [__________]                │
│   □ Open PR after success                  │
│   □ Skip confirmation                      │
│                                            │
│ [Cancel]                     [Start Run ▶] │
└────────────────────────────────────────────┘
```

Maps to `POST /runs` body:
```json
{
  "repoPath": "/Users/steve/Desktop/app",
  "task": "Fix the health endpoint...",
  "mode": "Full",
  "maxAttempts": 5,
  "runTimeoutMs": 1800000,
  "agentBackend": "claude-agent-sdk",
  "branch": "fix/health-endpoint",
  "openPr": false,
  "skipConfirmation": true
}
```

### 9.11 Settings Screen

**Route:** `/settings`

Sections:

- **API Keys**: ANTHROPIC_API_KEY input (masked, stored in Tauri secure store), validation check button
- **Agent Backend**: Dropdown (claude-agent-sdk / legacy / none), path to claude executable override
- **Paths**: Default repo path (file dialog), worker path override, artifact storage location
- **Appearance**: Theme (system/light/dark), font size slider, log line buffer limit
- **Notifications**: Toggle OS notifications for run complete/failure, system tray behavior
- **Advanced**: Backend port override, log level, debug mode toggle

### 9.12 Failure Analysis Panel

Available when an attempt has a `FailurePacket`. Can be a tab or an expandable section within the Verification tab.

```
┌────────────────────────────────────────────────────────┐
│ Failure Analysis — Attempt 1                            │
│                                                         │
│ Primary: BackendContractFailure                         │
│ Secondary: AuthConfigurationMissing                     │
│                                                         │
│ Summary:                                                │
│ The API returns 401 on authenticated endpoints.          │
│ No auth token is being sent with requests.               │
│                                                         │
│ Suspected Root Causes:                                  │
│ ┌──────────────────────────────────────────────────┐   │
│ │ 1. Missing auth middleware (conf: 0.85)           │   │
│ │    Files: src/middleware/auth.js                   │   │
│ │    Components: auth-module                        │   │
│ │ 2. JWT secret not configured (conf: 0.60)         │   │
│ │    Files: .env, src/config.js                     │   │
│ └──────────────────────────────────────────────────┘   │
│                                                         │
│ Reproduction Steps:                                     │
│ 1. Send GET /api/me without auth header                 │
│ 2. Observe 401 response                                 │
│                                                         │
│ Repair Scope:                                           │
│ Files: src/middleware/auth.js, src/config.js             │
│ Services: api-server                                    │
│ Requires Env Rebuild: No                                │
│                                                         │
│ Blockers: Hard: none  Soft: none                        │
└────────────────────────────────────────────────────────┘
```

Fields from `FailurePacket`: `primaryFailureClass`, `secondaryFailureClasses`, `summary`, `suspectedRootCauses` (with confidence bars), `reproductionSteps`, `recommendedRepairScope`, `hardBlockers`, `softBlockers`.

---

## §10. Component Library

### 10.1 Domain-Specific Components

These components encode Demiurge's domain model into reusable visual elements. Each maps directly to a backend enum or DTO.

#### `StatusBadge`

Renders a colored badge for any status enum. Used across runs, attempts, verdicts, services.

```typescript
type StatusBadgeProps = {
  status: RunStatus | AttemptStatus | VerdictStatus | ServiceStatus;
  size?: 'sm' | 'md' | 'lg';
  animated?: boolean;  // pulse animation for active states
};
```

**Color mapping:**

| Status Category | Color | Examples |
|----------------|-------|---------|
| Active/In-Progress | Blue (pulse) | `Verifying`, `Repairing`, `Starting` |
| Success/Healthy | Green | `Succeeded`, `Pass`, `RunningHealthy` |
| Failure/Error | Red | `Exhausted`, `Fail`, `EnvironmentFailed` |
| Warning/Partial | Yellow | `Flake`, `Inconclusive`, `RepairFailed` |
| Neutral/Pending | Gray | `Created`, `Pending`, `Blocked` |
| Cancelled/Stopped | Slate | `Cancelled`, `Interrupted`, `Stopped` |

#### `PriorityIndicator`

```typescript
type PriorityIndicatorProps = {
  priority: 'Required' | 'Important' | 'NiceToHave';
};
```

- `Required` → Red dot + "Required" label
- `Important` → Yellow dot + "Important" label
- `NiceToHave` → Gray dot + "Nice to Have" label

#### `VerifierTypeIcon`

Maps each of the 9 `VerifierType` values to a Lucide icon:

| VerifierType | Icon | Color |
|---|---|---|
| `EnvironmentReadiness` | `ServerCog` | Blue |
| `HttpApiContract` | `Globe` | Green |
| `BrowserFlow` | `Monitor` | Purple |
| `StateAssertion` | `Database` | Orange |
| `QueueJob` | `Layers` | Teal |
| `ConsoleLogSanity` | `Terminal` | Gray |
| `NetworkExpectation` | `Wifi` | Indigo |
| `PersistenceReload` | `RefreshCw` | Amber |
| `TargetedRegression` | `GitBranch` | Red |

#### `ServiceKindIcon`

Maps `ServiceKind` to icons:

| ServiceKind | Icon |
|---|---|
| `Frontend` | `Layout` |
| `Api` | `Server` |
| `Database` | `Database` |
| `Cache` | `Zap` |
| `Queue` | `Layers` |
| `Worker` | `Cog` |

#### `ArtifactTypeIcon`

Maps 24 `ArtifactType` values to icons. Grouped by category:

| Category | Types | Icon |
|---|---|---|
| Plans | `Plan`, `FeaturePlan` | `FileText` |
| Logs | `ServiceLog`, `StdoutExcerpt`, `StderrExcerpt`, `ConsoleLog` | `Terminal` |
| Browser | `Screenshot`, `BrowserTrace`, `DomSnapshot`, `AccessibilitySnapshot` | `Camera` / `Monitor` |
| Network | `NetworkSummary`, `ApiRequestResponse` | `Globe` |
| Data | `DbQueryResult`, `QueueObservation` | `Database` |
| Code | `PatchDiff` | `GitBranch` |
| Verdicts | `StructuredVerdict`, `FailurePacketArtifact` | `CheckCircle` / `AlertTriangle` |
| Reports | `FinalReport`, `AttemptReport` | `BarChart` |
| LLM | `RepairTranscript`, `InferenceLog`, `PromptPackage` | `Brain` |
| Config | `RepoInspectionArtifact`, `AuthStorageState` | `Settings` |
| Timeline | `StartupTimeline` | `Clock` |

#### `FailureClassBadge`

Renders `FailureClass` enum values with semantic colors:

| FailureClass | Color | Description |
|---|---|---|
| `FrontendRenderFailure` | Purple | UI rendering issue |
| `BackendContractFailure` | Red | API contract violation |
| `AuthenticationFailure` | Orange | Auth/session issue |
| `DataIntegrityFailure` | Amber | Database/state issue |
| `EnvironmentFailure` | Blue | Service/infra issue |
| `NetworkFailure` | Indigo | Network/timeout issue |
| `PerformanceFailure` | Yellow | Latency/throughput issue |
| `RegressionFailure` | Red | Previously passing now fails |
| `UnknownFailure` | Gray | Unclassified |

#### `ConfidenceBar`

Horizontal bar showing 0.0–1.0 confidence. Color gradient: red (<0.3) → yellow (0.3–0.7) → green (>0.7). Tooltip shows exact value.

#### `ElapsedTimer`

Live-updating elapsed time display. Input: `startedAt` timestamp. Shows `Xm Ys` format. Updates every second via `requestAnimationFrame`. Stops when `endedAt` is provided.

#### `CostDisplay`

Formats USD cost with token breakdown. Example: "$0.42 (45k in / 12k out)". Color codes by magnitude: green (<$1), yellow ($1-$5), red (>$5).

### 10.2 Layout Components

#### `Sidebar`

Fixed left sidebar with navigation links. Sections:
- **Navigation**: Dashboard, Active Run (if any), Config, Settings
- **Recent Runs**: Last 5 runs with status dots (quick switch)
- **Footer**: Backend status indicator, version

Collapsible to icon-only mode. Stores collapsed state in preferences.

#### `CommandPalette`

Cmd+K triggered modal. Fuzzy search across:
- **Actions**: New Run, Build Feature, Smart Init, Cancel Run, Resume Run, Open Settings
- **Navigation**: Go to Dashboard, Go to Run {id}, Go to Config
- **Runs**: Search by task text, run ID
- **Artifacts**: Search by artifact name, type

Uses shadcn/ui `CommandDialog` (which wraps `cmdk`).

### 10.3 shadcn/ui Primitives Used

The following shadcn/ui components are used as the base layer:

`Button`, `Card`, `Dialog`, `DropdownMenu`, `Input`, `Label`, `Select`, `Separator`, `Sheet`, `Skeleton`, `Slider`, `Switch`, `Table`, `Tabs`, `Textarea`, `Toast`, `Tooltip`, `Badge`, `Collapsible`, `Command`, `Progress`, `ScrollArea`, `Accordion`, `AlertDialog`, `Avatar`, `Popover`, `ResizablePanel`

---

## §11. Data Flow Diagrams

### 11.1 New Run Flow

```
User clicks "New Run"
  │
  ▼
NewRunDialog (form)
  │ fills: repo, task, mode, budget, git options
  ▼
POST /runs { repoPath, task, mode, ... }
  │
  ▼
Backend: RunCommand.execute()
  │ Creates TaskRun in SQLite
  │ Starts LocalApiServer if not running
  │ Starts RunOrchestrator on new thread
  │ Returns { runId }
  │
  ▼
Frontend:
  │ setActiveRun(runId)
  │ Navigate to /runs/{runId}
  │ Subscribe SSE: GET /runs/{runId}/events
  │ Open WS subscriptions as needed
  │
  ▼
SSE events flow → RunStore.handleEvent()
  │ → PipelineStepper updates
  │ → TanStack Query invalidations
  │ → OS notifications on completion
```

### 11.2 Service Log Tailing Flow

```
User clicks service in ServiceTopology
  │
  ▼
ServiceCard renders, LogTailer mounts
  │
  ▼
LogsStore.subscribe("api-server")
  │
  ▼
WS → { type: "subscribe_logs", runId, serviceId: "api-server", lines: 500 }
  │
  ▼
Backend: LogStreamManager
  │ Reads ring buffer (last 500 lines)
  │ Sends: { type: "log_backfill", serviceId, lines: [...] }
  │ Registers this client for live broadcast
  │
  ▼
Frontend: LogsStore.backfill("api-server", lines)
  │ xterm.js writes initial content
  │
  ▼
Live loop:
  ServiceProcessManager stdout/stderr
    → LogStreamManager.appendLine()
      → Ring buffer update
      → WS broadcast: { type: "log_line", serviceId, line, timestamp }
        → LogsStore.appendLine("api-server", line)
          → xterm.js.write(line)
            → Auto-scroll (unless paused)
```

### 11.3 Agent Transcript Flow

```
Orchestrator enters Repairing state
  │
  ▼
SSE: { eventType: "repair_started", payload: { attemptNumber: 1, backend: "claude-agent-sdk" } }
  │
  ▼
Frontend: AgentStore.reset() + AgentPanel becomes active
  │ WS → { type: "subscribe_agent", runId }
  │
  ▼
Backend (concurrent):
  ClaudeAgentBackend → WorkerProcessManager → agent/execute JSON-RPC
    → TypeScript worker → Agent SDK query()
      │
      │ For each SDK message:
      │   message.type === 'assistant' + block.type === 'tool_use'
      │     → server.sendNotification('agent/toolUse', { toolName, inputSummary })
      │       → AgentToolRpcHandlers receives notification
      │         → LogStreamManager.broadcastAgentMessage()
      │           → WS: { type: "agent_message", messageType: "tool_use", data: {...} }
      │             → AgentStore.appendToolCall()
      │               → TranscriptStream renders ToolCallCard
      │
      │   block.type === 'text'
      │     → server.sendNotification('agent/progress', { text })
      │       → WS: { type: "agent_message", messageType: "text", data: { text } }
      │         → AgentStore.appendMessage()
      │           → TranscriptStream renders chat bubble
      │
      │   Demiurge MCP tool call (e.g., verify_requirements)
      │     → Worker → demiurge.callback.response notification
      │       → AgentToolRpcHandlers handles, sends result back
      │       → Also broadcasts to WS for UI display
      │
      │   message.type === 'result'
      │     → AgentExecuteResult returned via JSON-RPC
      │       → Backend updates cost, files changed
      │         → SSE: { eventType: "agent_completed", payload: { costUsd, filesChanged } }
      │           → AgentStore.updateCost()
```

### 11.4 Config Editing Flow

```
User navigates to /config
  │
  ▼
GET /config?repo=/path/to/repo
  │ Returns: ResolvedConfig + ConfigProvenance
  │
  ▼
ConfigScreen renders tabs:
  │ ManifestEditor: loads demiurge.yaml (raw YAML from disk via Tauri FS plugin)
  │ RequirementsEditor: loads requirements.yaml
  │ BudgetEditor: loads from ResolvedConfig.policies
  │
  ▼
User edits YAML in Monaco
  │ (debounced 500ms)
  ▼
POST /config/validate { manifest: "...", requirements: "..." }
  │ Returns: { valid: true/false, errors: [...], warnings: [...] }
  │
  ▼
ManifestEditor: shows error squiggles in Monaco via markers API
  │
  ▼
User clicks [Save]
  │
  ▼
PUT /config/manifest { body: raw YAML }
  │ Backend: validates, writes to <repo>/demiurge.yaml
  │ Returns: { success: true, path: "..." }
  │
  ▼
TanStack Query invalidates config.resolved(repoPath)
  │ ProvenanceView updates to show new sources
```

---

## §12. Tauri Shell Integration

### 12.1 Sidecar Configuration

In `tauri.conf.json`:

```json
{
  "bundle": {
    "externalBin": ["binaries/demiurge-server"]
  }
}
```

The `demiurge-server` binary is the Scala backend packaged as a fat JAR with a launcher script (or native image via GraalVM). Tauri bundles it alongside the app.

### 12.2 Sidecar Lifecycle (`sidecar.rs`)

```rust
// Pseudocode for sidecar management
struct SidecarManager {
    child: Option<CommandChild>,
    port: u16,
    ws_port: u16,
}

impl SidecarManager {
    // Start the JVM backend as a sidecar process
    fn start(&mut self, db_path: &str) -> Result<()> {
        let (mut rx, child) = Command::new_sidecar("demiurge-server")?
            .args(["serve", "--port", &self.port.to_string(),
                   "--ws-port", &self.ws_port.to_string(),
                   "--db", db_path])
            .spawn()?;

        self.child = Some(child);

        // Monitor stdout/stderr for startup confirmation
        tauri::async_runtime::spawn(async move {
            while let Some(event) = rx.recv().await {
                match event {
                    CommandEvent::Stdout(line) => { /* log */ },
                    CommandEvent::Stderr(line) => { /* log */ },
                    CommandEvent::Terminated(payload) => { /* handle crash, restart */ },
                    _ => {}
                }
            }
        });

        Ok(())
    }

    // Health check with retry
    async fn wait_for_ready(&self, timeout_ms: u64) -> bool {
        // Poll GET /health every 500ms until 200 or timeout
    }

    // Graceful shutdown
    fn stop(&mut self) -> Result<()> {
        if let Some(child) = self.child.take() {
            child.kill()?;
        }
        Ok(())
    }
}
```

### 12.3 Sidecar Crash Recovery

If the sidecar process exits unexpectedly:
1. Tauri detects via `CommandEvent::Terminated`
2. Wait 2 seconds
3. Attempt restart (up to 3 retries)
4. If all retries fail → show error banner in UI: "Backend crashed. [Restart] [Open Logs]"
5. Backend logs captured to `~/.demiurge/desktop.log` for debugging

### 12.4 System Tray (`tray.rs`)

```
Tray Icon: Demiurge logo (changes color based on state)
  - Gray: Idle (no active run)
  - Blue: Run in progress
  - Green: Last run succeeded
  - Red: Last run failed

Tray Menu:
  ├── Show Demiurge          (bring window to front)
  ├── ─────────────
  ├── Active Run: "Fix..."   (if running, click to navigate)
  │     Status: Verifying
  │     ⏱ 2m 14s
  ├── ─────────────
  ├── New Run...             (opens NewRunDialog)
  ├── Recent Runs ▶
  │     ├── Fix health endpoint (Pass)
  │     ├── Add auth middleware (Exhausted)
  │     └── ...
  ├── ─────────────
  ├── Settings...
  └── Quit Demiurge          (stops sidecar + quits)
```

### 12.5 Window Management

| Window | Type | Size | Notes |
|--------|------|------|-------|
| Main | Primary | 1200×800 min, resizable | Remembers position via `window-state` plugin |
| Detached Log | Secondary | 800×600 | Created by "Detach" button in LogTailer |
| Smart Init Wizard | Dialog | 700×500 | Modal on top of main |
| New Run Dialog | Dialog | 600×700 | Modal on top of main |

### 12.6 Tauri IPC Commands (`commands.rs`)

These are Tauri commands (Rust → Frontend) used only for native OS operations:

```rust
#[tauri::command]
async fn start_backend(app: AppHandle) -> Result<(), String>;

#[tauri::command]
async fn stop_backend(app: AppHandle) -> Result<(), String>;

#[tauri::command]
async fn restart_backend(app: AppHandle) -> Result<(), String>;

#[tauri::command]
async fn get_backend_status(app: AppHandle) -> Result<BackendStatus, String>;

#[tauri::command]
async fn open_file_in_editor(path: String) -> Result<(), String>;
// Opens file in system default editor (VS Code, etc.)

#[tauri::command]
async fn open_folder_dialog() -> Result<Option<String>, String>;
// Native folder picker for repo selection

#[tauri::command]
async fn open_url_in_browser(url: String) -> Result<(), String>;
// Open service URL in system browser

#[tauri::command]
async fn get_secure_key(key: String) -> Result<Option<String>, String>;
// Read from Tauri secure store (API keys)

#[tauri::command]
async fn set_secure_key(key: String, value: String) -> Result<(), String>;
// Write to Tauri secure store
```

### 12.7 Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Cmd+K` | Open command palette |
| `Cmd+N` | New run dialog |
| `Cmd+B` | Build feature dialog |
| `Cmd+,` | Open settings |
| `Cmd+1-6` | Switch tabs in run detail (Verification/Agent/Env/Artifacts/Inspection/Events) |
| `Cmd+R` | Resume interrupted run |
| `Cmd+.` | Cancel active run |
| `Cmd+L` | Focus log search |
| `Cmd+Shift+L` | Toggle log auto-scroll |
| `Escape` | Close modal/palette |

---

## §13. Packaging & Distribution

### 13.1 Build Artifacts

| Platform | Format | Size Estimate |
|----------|--------|---------------|
| macOS (Apple Silicon) | `.dmg` (contains `.app`) | ~15MB (Tauri) + ~50MB (JVM sidecar) |
| macOS (Intel) | `.dmg` | ~15MB + ~55MB |
| Linux (x86_64) | `.AppImage` + `.deb` | ~12MB + ~50MB |
| Windows | `.msi` + `.nsis` | ~12MB + ~55MB |

### 13.2 JVM Sidecar Packaging Options

**Option A: Fat JAR + bundled JRE (recommended for v1)**

- Package the Scala backend as a single fat JAR via `sbt assembly` or Bazel `java_binary`
- Bundle a minimal JRE (OpenJDK 21, jlink-trimmed, ~40MB)
- Launcher script: `java -jar demiurge-server.jar serve --port 19440`
- Pros: Simple, reliable, works everywhere
- Cons: 40MB+ for JRE

**Option B: GraalVM native image (future optimization)**

- Compile Scala backend to native binary via GraalVM
- No JRE needed, ~15MB binary
- Pros: Fast startup (<100ms), small footprint
- Cons: GraalVM compatibility issues with reflection-heavy Scala libraries; significant build complexity
- Recommendation: Defer to v2 after validating the app works with fat JAR

**Option C: Detect system JVM**

- Check if `java` is on PATH and version ≥ 21
- If yes, use system JVM; if no, prompt to install or fall back to bundled JRE
- Pros: Smallest bundle for users who have Java
- Cons: Version compatibility issues, bad UX for non-developers

**Recommendation:** Option A for v1, with Option B as a stretch goal.

### 13.3 Auto-Update

Tauri v2's `updater` plugin supports:
- Update manifest hosted on GitHub Releases (or custom URL)
- Differential updates (only changed files)
- Signature verification
- User prompt before update

Update flow:
1. App checks for updates on launch (configurable interval)
2. If update available → shows non-intrusive banner: "Update available: v1.2.0 [Install Now] [Later]"
3. On "Install Now" → downloads update, restarts app
4. Sidecar binary updated alongside frontend

### 13.4 First-Run Experience

On first launch:
1. **Welcome screen** → brief tour of key features (3 slides)
2. **Backend startup** → sidecar starts, health check, show progress
3. **Prerequisites check** → `GET /system/doctor` → show results, guide user to fix any issues
4. **API key setup** → prompt for ANTHROPIC_API_KEY if not set
5. **Repo selection** → file dialog to pick first project
6. **Optional: Smart Init** → offer to auto-configure the selected repo

---

## §14. Implementation Phases

### Phase 1: Foundation + Read-Only Dashboard (~3 weeks)

**Goal:** Tauri scaffold running, backend sidecar managed, dashboard showing run history, run detail with live pipeline stepper.

#### 14.1.1 Tauri + React Scaffold

- Initialize `desktop/` with `create-tauri-app` (React + TypeScript + Vite)
- Configure `tauri.conf.json` (window, permissions, sidecar declaration)
- Install dependencies: Tailwind CSS 4, shadcn/ui, Zustand, TanStack Query, TanStack Router, Lucide React, date-fns, Framer Motion
- Set up `src-tauri/` Rust code: `main.rs`, `sidecar.rs`, `tray.rs`, `commands.rs`
- Implement sidecar lifecycle: start on launch, health check polling, crash recovery
- Implement basic system tray (Show/Quit)
- **LOC estimate:** ~500 (Rust) + ~300 (React scaffold)

#### 14.1.2 API Client Layer

- `api/client.ts` — fetch wrapper with base URL, error handling, `ApiEnvelope` unwrap
- `api/sse.ts` — `EventSource` wrapper with reconnection logic
- `api/types.ts` — TypeScript types mirroring all Scala DTOs (TaskRun, Attempt, RequirementVerdict, SystemEvent, ArtifactRecord, etc.)
- `api/endpoints.ts` — typed endpoint functions for all existing + new REST endpoints
- **LOC estimate:** ~800

#### 14.1.3 State Management Setup

- `stores/app.store.ts`, `stores/run.store.ts`, `stores/preferences.store.ts`
- TanStack Query provider + query key factory
- `hooks/useSSE.ts` — SSE subscription hook with store integration
- `hooks/useBackendHealth.ts` — backend health polling
- **LOC estimate:** ~400

#### 14.1.4 Dashboard Screen

- `Sidebar` component with navigation
- `DashboardScreen` with `SystemHealthWidget`, `QuickActions`, `RunHistoryTable`
- `StatusBadge`, `ElapsedTimer` shared components
- **LOC estimate:** ~600

#### 14.1.5 Run Detail Screen (Read-Only)

- `RunDetailScreen` with `PipelineStepper` (SSE-driven live updates)
- `AttemptTabs` with attempt switcher
- `RunTimers` with live elapsed time
- `RunActions` (Cancel/Resume buttons — call existing API endpoints)
- **LOC estimate:** ~500

#### 14.1.6 Backend: CORS + Run List Endpoint

- Add CORS middleware wrapper to `LocalApiServer`
- Add `GET /runs` endpoint with pagination/sorting (new `Routes.scala` handler)
- Add `GET /runs/active` endpoint
- **LOC estimate:** ~150 (Scala)

**Phase 1 Total: ~3,250 LOC**

**Milestone:** App launches, starts backend, shows run history, clicking a run shows live-updating pipeline stepper. Cancel/Resume work from UI.

---

### Phase 2: Verification + Artifacts + Inspection (~3 weeks)

**Goal:** Full verification panel with verdict details, artifact browser with content viewers, inspection panel.

#### 14.2.1 Verification Panel

- `VerificationPanel`, `VerifierMatrix`, `VerdictCard`, `AggregateBar`
- `PriorityIndicator`, `VerifierTypeIcon`, `ConfidenceBar`, `FailureClassBadge`
- `RequirementGraph` modal with ReactFlow (install ReactFlow)
- **LOC estimate:** ~1,200

#### 14.2.2 Artifact Browser

- `ArtifactBrowser`, `ArtifactTree`, `ContentViewer`
- Content renderers: `JsonViewer`, `DiffViewer` (react-diff-viewer-continued), `ScreenshotGallery` (image lightbox)
- Basic renderers for logs (monospace pre), reports (markdown via react-markdown)
- **LOC estimate:** ~1,000

#### 14.2.3 Inspection Panel

- `InspectionPanel`, `RepoOverview`, `InferenceTable`, `ImpactMap`
- **LOC estimate:** ~500

#### 14.2.4 Events Tab

- `EventsPanel` — chronological event list with filters, severity badges, payload expand
- **LOC estimate:** ~300

#### 14.2.5 Backend: Inspection + Graph + Failure Endpoints

- `InspectionRoutes.scala` — `/runs/{id}/inspection`, `/runs/{id}/requirement-graph`, `/runs/{id}/feature-plan`
- `FailureRoutes.scala` — `/runs/{id}/attempts/{n}/failure-packet`, `/runs/{id}/attempts/{n}/patches`
- **LOC estimate:** ~250 (Scala)

**Phase 2 Total: ~3,250 LOC**

**Milestone:** Complete read-only view of all run data. User can browse verification results, artifacts (with rendered content), inspection reports, and events.

---

### Phase 3: Live Monitoring — Logs + Agent (~3 weeks)

**Goal:** Real-time service log tailing, agent transcript streaming, WebSocket infrastructure.

#### 14.3.1 Backend: WebSocket Server + Log Streaming

- `WebSocketServer.scala` — Java-WebSocket server on `:19441`
- `LogStreamManager.scala` — ring buffer per service, broadcast hook into `ServiceProcessManager`
- Agent message forwarding from `AgentToolRpcHandlers` to WS
- **LOC estimate:** ~500 (Scala)

#### 14.3.2 Frontend: WebSocket Infrastructure

- `api/websocket.ts` — WebSocket connection manager with reconnection
- `stores/logs.store.ts` — per-service ring buffers
- `stores/agent.store.ts` — agent transcript state
- `hooks/useWebSocket.ts`, `hooks/useServiceLogs.ts`, `hooks/useAgentTranscript.ts`
- **LOC estimate:** ~600

#### 14.3.3 Environment Panel

- `EnvironmentPanel`, `ServiceTopology` (ReactFlow), `ServiceCard`
- `LogTailer` (install xterm.js + @xterm/addon-fit + @xterm/addon-search)
- `BootTimeline` visualization
- **LOC estimate:** ~1,000

#### 14.3.4 Agent Panel

- `AgentPanel`, `TranscriptStream`, `ToolCallCard`, `AgentDiffViewer`, `AgentCostTracker`
- `CostDisplay` shared component
- **LOC estimate:** ~900

#### 14.3.5 Failure Analysis Panel

- `FailureAnalysisPanel` — renders `FailurePacket` with root causes, reproduction steps, repair scope
- **LOC estimate:** ~300

#### 14.3.6 Backend: Environment + Agent Endpoints

- `EnvironmentRoutes.scala` — `/runs/{id}/environment`, `/runs/{id}/services`, service log SSE, service restart
- `AgentRoutes.scala` — `/runs/{id}/agent/transcript`, `/runs/{id}/agent/cost`
- New SSE event types: `verification_started`, `verdict_produced`, `service_status_changed`, `agent_tool_use`, `agent_progress`, `agent_completed`, `artifact_created`, `repair_started`, `repair_completed`, `boot_progress`
- **LOC estimate:** ~400 (Scala)

**Phase 3 Total: ~3,700 LOC**

**Milestone:** Full real-time monitoring. Users can tail service logs, watch agent transcripts, see service topology with live health status. Detached log windows work.

---

### Phase 4: Interactive Controls — New Run, Config, Settings (~2 weeks)

**Goal:** Users can start new runs, edit configuration, manage settings entirely from UI.

#### 14.4.1 New Run / Build Dialogs

- `NewRunDialog`, `BuildDialog`, `ModeSelector`, `BudgetOverrides`
- Backend: extend `POST /runs` to accept full JSON body (currently wired from CLI args)
- **LOC estimate:** ~600

#### 14.4.2 Config Screen

- `ConfigScreen`, `ManifestEditor` (install Monaco: `@monaco-editor/react`)
- `RequirementsEditor` (form view + YAML view toggle)
- `BudgetEditor`, `ProvenanceView`
- **LOC estimate:** ~1,200

#### 14.4.3 Smart Init Wizard

- `SmartInitWizard` — multi-step wizard, reuses `TranscriptStream` for agent progress
- **LOC estimate:** ~400

#### 14.4.4 Settings Screen

- `SettingsScreen` — all sections (API keys, backend, paths, appearance, notifications, advanced)
- Tauri secure store integration for API key
- **LOC estimate:** ~500

#### 14.4.5 Command Palette

- `CommandPalette` — Cmd+K, fuzzy search actions/navigation/runs
- Install `cmdk` (shadcn wraps it)
- **LOC estimate:** ~300

#### 14.4.6 Backend: Config + System Endpoints

- `ConfigRoutes.scala` — `/config`, `/config/manifest`, `/config/requirements`, `/config/validate`, `/config/init-smart`
- `SystemRoutes.scala` — `/system/doctor`, `/system/preferences`, `/system/repos`
- **LOC estimate:** ~400 (Scala)

**Phase 4 Total: ~3,400 LOC**

**Milestone:** Full CLI parity. Users never need the terminal. All commands have UI equivalents.

---

### Phase 5: Polish + Packaging (~2 weeks)

**Goal:** Production-ready packaging, auto-update, OS notifications, detached windows, first-run experience.

#### 14.5.1 Packaging

- Configure Tauri bundler for macOS `.dmg`, Linux `.AppImage`/`.deb`, Windows `.msi`
- JVM sidecar packaging (fat JAR + jlink-trimmed JRE)
- Launcher script per platform
- CI/CD pipeline for building + signing releases (GitHub Actions)
- **LOC estimate:** ~300 (config/scripts)

#### 14.5.2 System Tray Enhancement

- Dynamic tray icon color based on run state
- Tray menu with active run info, recent runs, quick actions
- **LOC estimate:** ~200 (Rust)

#### 14.5.3 OS Notifications

- Notify on: run completed (success/failure), backend crash, update available
- Uses `@tauri-apps/plugin-notification`
- **LOC estimate:** ~100

#### 14.5.4 Detached Log Windows

- "Detach" button creates new Tauri window with dedicated LogTailer
- Window remembers size/position independently
- **LOC estimate:** ~200

#### 14.5.5 First-Run Experience

- Welcome screen with feature tour
- Guided setup (prerequisites, API key, repo selection)
- **LOC estimate:** ~400

#### 14.5.6 Content Viewer Enhancements

- xterm.js for ANSI log artifacts (vs basic monospace)
- Playwright trace viewer integration (if feasible — iframe to trace.playwright.dev)
- Screenshot diff between attempts (side-by-side comparison)
- **LOC estimate:** ~500

**Phase 5 Total: ~1,700 LOC**

**Milestone:** Shippable v1.0. Packages for all platforms. Auto-update works. Professional first-run experience.

---

### Implementation Summary

| Phase | Focus | Duration | Frontend LOC | Backend LOC | Total |
|-------|-------|----------|-------------|-------------|-------|
| 1 | Foundation + Dashboard | 3 weeks | ~2,600 | ~650 | ~3,250 |
| 2 | Verification + Artifacts | 3 weeks | ~3,000 | ~250 | ~3,250 |
| 3 | Live Monitoring | 3 weeks | ~2,800 | ~900 | ~3,700 |
| 4 | Interactive Controls | 2 weeks | ~3,000 | ~400 | ~3,400 |
| 5 | Polish + Packaging | 2 weeks | ~1,200 | ~500 | ~1,700 |
| **Total** | | **~13 weeks** | **~12,600** | **~2,700** | **~15,300** |

---

## §15. Testing Strategy

### 15.1 Frontend Tests

| Layer | Tool | What to Test |
|-------|------|-------------|
| Components | Vitest + React Testing Library | Render correct data, status colors, icons, interactions |
| Hooks | Vitest | SSE event handling, WebSocket message processing, store updates |
| Stores | Vitest | State transitions, ring buffer behavior, event → state mapping |
| API Client | Vitest + MSW (Mock Service Worker) | Request/response handling, error cases, retry logic |
| Integration | Playwright (Tauri mode) | Full user flows: start run → watch pipeline → browse artifacts |

**Key test scenarios:**

1. **PipelineStepper** renders correct step states for each `RunStatus`
2. **StatusBadge** shows correct color for every enum value (parametric test over all values)
3. **SSE handler** correctly dispatches all 11 event types to the right store
4. **WebSocket reconnection** restores subscriptions after disconnect
5. **LogTailer** handles high-throughput log lines without dropped frames
6. **ContentViewer** renders correct component for each `ArtifactType`
7. **RunHistoryTable** sorts and filters correctly
8. **Config validation** shows errors in Monaco editor

### 15.2 Backend Tests

New Scala tests for the extended API endpoints:

| Module | Test File | What to Test |
|--------|-----------|-------------|
| `ConfigRoutes` | `ConfigRoutesSuite.scala` | CRUD operations, validation errors, YAML parsing |
| `InspectionRoutes` | `InspectionRoutesSuite.scala` | Inspection report retrieval, requirement graph JSON |
| `EnvironmentRoutes` | `EnvironmentRoutesSuite.scala` | Service list, log retrieval, restart handling |
| `AgentRoutes` | `AgentRoutesSuite.scala` | Transcript retrieval, cost tracking |
| `SystemRoutes` | `SystemRoutesSuite.scala` | Doctor check, preferences CRUD |
| `WebSocketServer` | `WebSocketServerSuite.scala` | Subscribe/unsubscribe, log broadcasting, heartbeat |
| `LogStreamManager` | `LogStreamManagerSuite.scala` | Ring buffer behavior, concurrent broadcast |
| `CORS` | `CorsMiddlewareSuite.scala` | Correct headers for allowed/disallowed origins |

**Estimated test LOC:** ~2,000 (Scala) + ~3,000 (TypeScript)

### 15.3 E2E Tests

Using Playwright with Tauri's WebDriver integration:

1. **App Launch** — sidecar starts, health check passes, dashboard loads
2. **New Run Flow** — fill dialog → start → watch pipeline progress → run completes
3. **Artifact Browsing** — navigate to completed run → browse artifact tree → view content
4. **Config Editing** — edit YAML → validate → save → verify written to disk
5. **Log Tailing** — start run → open environment tab → verify log lines appear in real-time
6. **Agent Transcript** — start run with agent repair → verify transcript messages appear

### 15.4 Visual Regression Tests

Consider Chromatic or Percy for visual regression on key screens:
- Dashboard (empty, with runs, with active run)
- Pipeline stepper (each state combination)
- Verification panel (pass/fail/mixed)
- Agent transcript (in-progress, completed)

---

## §16. CLI Migration & Coexistence

### 16.1 Shared Infrastructure

The CLI and desktop app share:
- **SQLite database** — `.demiurge/demiurge.db` (WAL mode supports concurrent readers)
- **Artifact directory** — `.demiurge/artifacts/`
- **Config files** — `demiurge.yaml`, `requirements.yaml`
- **Lock file** — `.demiurge/locks/` (prevents concurrent runs)

### 16.2 CLI-to-Desktop Visibility

A run started from CLI is visible in the desktop app:
1. CLI starts `LocalApiServer` on `:19440` (already does this)
2. Desktop app detects a running backend via `GET /health`
3. If desktop app's own sidecar isn't running, it connects to the CLI-started backend
4. SSE subscription picks up events from the CLI-started run
5. All data is in the shared SQLite — historical runs from CLI are browsable

### 16.3 Desktop-to-CLI Visibility

A run started from the desktop app:
1. Sidecar is the backend (same binary as CLI, running in `serve` mode)
2. CLI `demiurge status` reads from the shared SQLite → sees the desktop-started run
3. CLI `demiurge cancel <runId>` → posts to the sidecar's API → cancels the run
4. All artifacts are in the shared directory

### 16.4 Concurrent Access Rules

| Scenario | Behavior |
|----------|----------|
| Desktop viewing + CLI starts run | Desktop detects new run via polling `GET /runs/active`, auto-navigates |
| Desktop active run + CLI `cancel` | SSE delivers `Cancelled` state transition, UI updates |
| Both try to start runs simultaneously | `LockManager` file lock prevents second run. UI shows error: "Another run is already in progress" |
| Desktop browsing history + CLI modifies DB | SQLite WAL allows concurrent read + single write. No conflict. |

### 16.5 New Backend `serve` Mode

The CLI currently starts `LocalApiServer` only during a run (in `RunCommand`). For the desktop app, we need a persistent server mode:

```
demiurge serve [--port 19440] [--ws-port 19441] [--db <path>]
```

This new CLI command:
1. Opens the database
2. Starts `LocalApiServer` + `WebSocketServer`
3. Accepts `POST /runs` to start runs (delegates to `RunCommand.execute()` on a new thread)
4. Runs until killed (SIGTERM or API shutdown endpoint)

This is what the Tauri sidecar invokes. It's also useful standalone (e.g., for remote access from a browser during development).

---

## §17. Performance Budget

### 17.1 Startup Time

| Metric | Target | Notes |
|--------|--------|-------|
| Tauri window visible | <500ms | System webview cold start |
| Frontend rendered (skeleton) | <1s | Vite bundle, React hydration |
| Backend health check passes | <5s | JVM cold start + SQLite open |
| Dashboard fully loaded | <2s | After backend ready; first API call |

Optimization levers:
- Show skeleton UI while backend starts (no blocking)
- Preload SQLite on sidecar start (eager connection)
- Lazy-load heavy components (Monaco, ReactFlow, xterm.js) via React.lazy + Suspense

### 17.2 Runtime Memory

| Component | Target | Measurement |
|-----------|--------|-------------|
| Tauri shell + webview | <80MB | macOS WebKit typically 40-60MB |
| React app (idle) | <30MB | JS heap, measured via DevTools |
| React app (active run, all tabs open) | <100MB | Including log buffers, transcript |
| JVM sidecar (idle) | <150MB | JVM heap + metaspace |
| JVM sidecar (active run) | <300MB | + worker process, orchestrator threads |
| Total (idle) | <260MB | |
| Total (active run) | <500MB | |

Memory management:
- Log ring buffers capped at `logLineLimit` (default 10,000 lines per service, ~2MB)
- Agent transcript capped at 5,000 messages (~1MB)
- TanStack Query `gcTime` (garbage collection) set to 5 minutes for non-active data
- Artifact content loaded on demand, not preloaded
- xterm.js uses virtual scrolling (renders only visible lines)

### 17.3 Bundle Size

| Asset | Target | Notes |
|-------|--------|-------|
| Vite JS bundle (gzipped) | <500KB | Code splitting by route |
| CSS (Tailwind, purged) | <30KB | Only used utilities |
| Monaco Editor (lazy) | ~2MB | Only loaded on Config screen |
| xterm.js (lazy) | ~200KB | Only loaded on Environment/Artifacts |
| ReactFlow (lazy) | ~300KB | Only loaded on Verification/Environment |
| Total initial load | <600KB | Excluding lazy chunks |

Strategy: Aggressive code splitting. Each screen is a lazy-loaded route chunk. Heavy libraries (Monaco, xterm, ReactFlow) are in separate chunks loaded on first visit.

### 17.4 Network Performance

| Operation | Target | Notes |
|-----------|--------|-------|
| REST API response | <50ms | Local HTTP, no network latency |
| SSE event delivery | <100ms | From `EventStream.publish()` to UI update |
| WS log line delivery | <50ms | From `ServiceProcessManager` to xterm render |
| Artifact content load (1MB) | <200ms | Local file read + HTTP transfer |
| Artifact content load (10MB) | <1s | Consider Tauri FS plugin for large files |

### 17.5 Rendering Performance

| Scenario | Target | Notes |
|----------|--------|-------|
| Log tailing (100 lines/sec) | 60fps | xterm.js batches writes |
| Pipeline stepper animation | 60fps | Framer Motion, GPU-accelerated |
| Run history table (1000 rows) | 60fps scroll | Virtual scrolling with TanStack Virtual if needed |
| Artifact tree (500 nodes) | 60fps | Virtualized tree with collapse |
| Requirement graph (50 nodes) | 60fps | ReactFlow handles this natively |

Mitigation for log throughput: Batch WS `log_line` messages — frontend buffers incoming lines and flushes to xterm.js every 16ms (one frame) instead of writing each line individually.

---

## §18. Accessibility

### 18.1 Standards

Target WCAG 2.1 AA compliance for all screens.

### 18.2 Requirements

- **Keyboard navigation**: All interactive elements reachable via Tab/Shift+Tab. shadcn/ui components provide this by default.
- **Focus management**: Modal dialogs trap focus. Screen transitions move focus to main content.
- **Color contrast**: All text meets 4.5:1 ratio. Status colors supplemented with icons/text (not color-only).
- **Screen reader support**: ARIA labels on all custom components. Status changes announced via `aria-live` regions.
- **Reduced motion**: Respect `prefers-reduced-motion`. Disable pulse animations, use instant transitions.
- **Font scaling**: Respect system font size preference. UI tested at 150% zoom.
- **Keyboard shortcuts**: All shortcuts documented and discoverable via Command Palette.

### 18.3 Specific Considerations

| Component | Accessibility Note |
|-----------|-------------------|
| `StatusBadge` | Include `aria-label` with full status text (not just color) |
| `PipelineStepper` | Use `role="progressbar"` with `aria-valuetext` describing current step |
| `LogTailer` (xterm.js) | xterm.js has limited a11y; provide text-based log view alternative |
| `ServiceTopology` (ReactFlow) | Provide table-based service list as alternative to graph |
| `RequirementGraph` (ReactFlow) | Provide list-based requirement view as alternative to DAG |
| `ConfidenceBar` | Include `aria-valuenow` and `aria-valuetext` |
| `ScreenshotGallery` | Alt text from artifact metadata |

---

## §19. Future Considerations

These are explicitly out of scope for v1 but inform architectural decisions.

### 19.1 Multi-Repo Support

The current design assumes one active repo at a time. Future: sidebar shows multiple repos, each with independent run history. The backend already supports this (repo path is per-run), but the UI would need a repo switcher.

### 19.2 Remote Backend

The HTTP API architecture means the frontend could connect to a remote backend (e.g., Demiurge running on a CI server). Would need:
- Authentication layer on the API
- TLS for remote connections
- Backend discovery/registration

### 19.3 Collaboration Features

- Share run results via link (generate HTML report)
- Export run as standalone artifact bundle
- Team activity feed

### 19.4 Custom Dashboards

- Configurable dashboard widgets
- Saved filter presets for run history
- Custom metric tracking across runs

### 19.5 Plugin System

- Custom artifact renderers (register new `ContentViewer` types)
- Custom verifier UIs
- Webhook integrations

### 19.6 Performance Analytics

- Cross-run metrics: average repair time, success rate, cost per run
- Verifier reliability tracking (which verifiers flake most?)
- Regression detection across runs

### 19.7 GraalVM Native Image

Replace fat JAR + JRE with a single native binary (~15MB). This would:
- Reduce app bundle by ~40MB
- Reduce startup time from ~5s to <100ms
- Reduce idle memory from ~150MB to ~30MB

Requires resolving GraalVM reflection issues with Scala libraries (circe, etc.). Worth investigating after v1 stability.

### 19.8 Web-Only Mode

Since the frontend communicates via HTTP, a `demiurge serve` + browser could replace Tauri entirely for users who don't want a desktop app. The frontend would be served as static files from the backend.

---

## Appendix A: CLI Command → UI Mapping

| CLI Command | UI Equivalent | Screen |
|-------------|--------------|--------|
| `demiurge run <task>` | New Run Dialog → Start Run | Dashboard → New Run modal |
| `demiurge build <task>` | Build Dialog → Start Build | Dashboard → Build modal |
| `demiurge plan <task>` | New Run Dialog (mode=PlanOnly) | Dashboard → New Run modal |
| `demiurge resume <runId>` | Resume button on Run Detail | Run Detail → RunActions |
| `demiurge status [runId]` | Run Detail screen | Run Detail |
| `demiurge inspect-run <runId>` | Inspection tab on Run Detail | Run Detail → Inspection tab |
| `demiurge open-artifact <artifactId>` | Artifact Browser → ContentViewer | Run Detail → Artifacts tab |
| `demiurge explain-failure <runId>` | Failure Analysis panel | Run Detail → Failure tab |
| `demiurge cancel <runId>` | Cancel button on Run Detail | Run Detail → RunActions |
| `demiurge clean [runId]` | Cleanup action (future) | Settings or Run context menu |
| `demiurge doctor` | System Health Widget | Dashboard |
| `demiurge init [--smart]` | Config Screen → Smart Init Wizard | Config → Smart Init |

## Appendix B: TypeScript Type Definitions (api/types.ts)

Key types that mirror Scala DTOs. The full list includes 79+ types; the critical ones are:

```typescript
// Run status — mirrors RunStatus enum (21 values)
type RunStatus =
  | 'Created' | 'InspectingRepo' | 'CompilingRequirements'
  | 'PlanningEnvironment' | 'BootstrappingEnvironment' | 'EnvironmentFailed'
  | 'SeedingFixtures' | 'BootstrappingAuth' | 'ReadyToVerify'
  | 'Verifying' | 'AnalyzingFailure' | 'PlanningRepair'
  | 'Repairing' | 'RepairFailed' | 'PlanningRerun'
  | 'SoftResettingEnvironment' | 'RebuildingEnvironment'
  | 'Succeeded' | 'Exhausted' | 'Cancelled' | 'Interrupted'
  | 'PlanningFeature' | 'GeneratingCode';

// Verdict status — mirrors VerdictStatus enum
type VerdictStatus = 'Pass' | 'Fail' | 'Inconclusive' | 'Blocked' | 'Timeout' | 'Flake';

// Run mode — mirrors RunMode enum
type RunMode = 'Full' | 'Build' | 'PlanOnly' | 'VerifyOnly' | 'InspectOnly';

// Service status — mirrors ServiceStatus enum
type ServiceStatus =
  | 'Pending' | 'Starting' | 'RunningHealthy' | 'RunningUnhealthy'
  | 'Degraded' | 'Stopped' | 'Failed';

// Verifier type — mirrors VerifierType enum (9 values)
type VerifierType =
  | 'EnvironmentReadiness' | 'HttpApiContract' | 'BrowserFlow'
  | 'StateAssertion' | 'QueueJob' | 'ConsoleLogSanity'
  | 'NetworkExpectation' | 'PersistenceReload' | 'TargetedRegression';

// Artifact type — mirrors ArtifactType enum (24 values)
type ArtifactType =
  | 'Plan' | 'ServiceLog' | 'StartupTimeline' | 'StdoutExcerpt'
  | 'StderrExcerpt' | 'BrowserTrace' | 'Screenshot' | 'DomSnapshot'
  | 'AccessibilitySnapshot' | 'ConsoleLog' | 'NetworkSummary'
  | 'ApiRequestResponse' | 'DbQueryResult' | 'QueueObservation'
  | 'PatchDiff' | 'StructuredVerdict' | 'FailurePacketArtifact'
  | 'FinalReport' | 'RepairTranscript' | 'InferenceLog'
  | 'RepoInspectionArtifact' | 'AuthStorageState' | 'PromptPackage'
  | 'AttemptReport';

// Core DTOs
interface TaskRun {
  runId: string;
  repoPath: string;
  worktreePath: string;
  gitRef: string | null;
  taskText: string;
  changedFiles: string[] | null;
  status: RunStatus;
  runMode: RunMode;
  createdAt: string;     // ISO 8601
  startedAt: string | null;
  endedAt: string | null;
  maxAttempts: number;
  attemptCount: number;
  envBootAttempts: number;
  currentAttemptId: string | null;
  finalVerdict: VerdictStatus | null;
  finalSummary: string | null;
  policySnapshotId: string;
}

interface Attempt {
  attemptId: string;
  runId: string;
  attemptNumber: number;
  status: string;
  startedAt: string;
  endedAt: string | null;
  repairBackend: string | null;
  verdictSummary: VerdictSummary | null;
}

interface RequirementVerdict {
  verdictId: string;
  runId: string;
  attemptNumber: number;
  requirementId: string;
  verifierId: string;
  status: VerdictStatus;
  executionDurationMs: number;
  retryCount: number;
  observations: Observation[];
  evidenceRefs: string[];
  failureClass: string | null;
  failureMessage: string | null;
  confidence: number;
  producedAt: string;
}

interface SystemEvent {
  eventId: string;
  runId: string;
  attemptNumber: number | null;
  eventType: string;
  component: string;
  severity: string;
  timestamp: string;
  correlationFields: Record<string, string>;
  payload: Record<string, unknown>;
  humanMessage: string;
}

interface ArtifactRecord {
  artifactId: string;
  runId: string;
  attemptNumber: number | null;
  artifactType: ArtifactType;
  producerComponent: string;
  logicalScope: string | null;
  relativePath: string;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
}

// ... (RequirementGraph, RequirementNode, FailurePacket, RepoInspectionReport,
//      RuntimePlan, ServiceSpec, ResolvedConfig, FeaturePlan, etc.)
```

## Appendix C: Backend `serve` Command Implementation Sketch

```scala
// New: modules/cli/src/main/scala/demiurge/cli/Commands/ServeCommand.scala

object ServeCommand {
  case class ServeCmd(
    port: Int = 19440,
    wsPort: Int = 19441,
    dbPath: Option[String] = None,
  )

  def execute(cmd: ServeCmd, global: GlobalOpts): Int = {
    val dbPath = cmd.dbPath
      .map(Paths.get(_))
      .getOrElse(Paths.get(global.repoPath, ".demiurge", "demiurge.db"))

    val conn = Database.open(dbPath)
    Database.migrate(conn)

    // Start HTTP API server
    val apiServer = LocalApiServer.start(
      port = cmd.port,
      dbPath = dbPath,
      artifactRootResolver = runId => {
        Some(Paths.get(global.repoPath, ".demiurge", "artifacts", runId))
      },
    )

    // Start WebSocket server
    val wsServer = WebSocketServer.start(cmd.wsPort)

    // Register run-starter callback so POST /runs can start orchestration
    Routes.setRunStarter { request =>
      // Spawn orchestration on a new thread, return runId
      val runId = RunCommand.startFromApi(request, global, conn)
      runId
    }

    println(s"Demiurge server listening on :${cmd.port} (HTTP) and :${cmd.wsPort} (WS)")
    println(s"Database: $dbPath")
    println("Press Ctrl+C to stop")

    // Block until SIGTERM
    val latch = new java.util.concurrent.CountDownLatch(1)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      apiServer.stop(0)
      wsServer.stop()
      conn.close()
      latch.countDown()
    }))
    latch.await()

    ExitCodes.Success
  }
}
```

---

*End of design specification.*
