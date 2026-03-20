# Desktop Application

Demiurge includes a native desktop application for monitoring runs, inspecting verdicts and artifacts, and controlling the orchestration pipeline through a graphical interface.

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Native shell | Tauri 2 (Rust) |
| Frontend framework | React 19 |
| Build tool | Vite 6 |
| Routing | TanStack Router 1 |
| Server state | TanStack Query 5 |
| Client state | Zustand 5 |
| Styling | Tailwind CSS 4 |
| Animations | Framer Motion 11 |
| Icons | Lucide React |
| Code editor | Monaco Editor |
| Flow diagrams | React Flow (@xyflow/react) |
| Terminal | xterm.js (@xterm/xterm) |
| Diff viewer | react-diff-viewer-continued |
| Markdown | react-markdown + remark-gfm |
| Command palette | cmdk |

## Architecture

The desktop app is a Tauri 2 application with a React frontend. It communicates with the Demiurge Scala backend via the local HTTP API (`127.0.0.1:19440`).

```
Tauri v2 Application
├── React Frontend (system WebView)
│   ├── Dashboard — run history, quick actions, system health
│   ├── Run Detail — live pipeline stepper, attempt tabs, timers
│   ├── Config — manifest editor (Monaco), requirements editor, budget editor
│   ├── Settings — preferences, API keys, account info
│   ├── Auth / AuthCallback — license authentication flow
│   └── DetachedLog — pop-out service log windows
├── Tauri Rust Core (thin layer)
│   ├── SidecarManager — spawn/manage JVM backend process
│   ├── System tray — status indicator, quick actions
│   ├── Window management — detached log windows
│   └── Tauri plugins — shell, dialog, notification, store, window-state,
│                        deep-link, process, updater
└── Scala Backend Sidecar
    └── `demiurge serve` — persistent HTTP (:19440) + WebSocket (:19441)
```

### Rust Backend (src-tauri)

The Tauri Rust backend provides:

- **SidecarManager** — manages the lifecycle of the Scala backend sidecar process (start, stop, restart, status)
- **Tauri Commands** — `start_backend`, `stop_backend`, `restart_backend`, `get_backend_status`, `open_folder_dialog`
- **System Tray** — tray icon with status indicator and quick actions
- **Sidecar Packaging** — `desktop/scripts/package-sidecar.sh` builds a fat JAR and places it in `src-tauri/binaries/` with the Tauri target triple naming convention
- **Plugins** — shell, dialog, notification, store, window-state, deep-link, process, updater

### React Frontend (src)

The frontend is structured as:

- **`api/`** — HTTP client (`client.ts`), SSE handler (`sse.ts`), WebSocket handler (`websocket.ts`), typed endpoints (`endpoints.ts`), TypeScript API types (`types.ts`)
- **`components/`** — 15 component groups organized by feature:
  - `account/` — account info, subscription status
  - `agent/` — `AgentPanel`, `TranscriptStream`, `ToolCallCard`, `AgentCostTracker`, `AgentDiffViewer`
  - `artifacts/` — `ArtifactBrowser`, `ArtifactTree`, `ContentViewer`, `ScreenshotGallery`, `JsonViewer`, `LogRenderer`, `MarkdownRenderer`, `DiffViewer`
  - `config/` — `ManifestEditor` (Monaco), `RequirementsEditor`, `BudgetEditor`, `ProvenanceView`
  - `dashboard/` — `QuickActions`, `RunHistoryTable`, `SystemHealthWidget`
  - `dialogs/` — modal dialogs
  - `environment/` — service topology, boot timeline, log tailing
  - `events/` — event stream display
  - `failure/` — failure analysis display
  - `inspection/` — `InspectionPanel`, `RepoOverview`, `ImpactMap`, `InferenceTable`
  - `layout/` — `AppLayout`, `AuthLayout`, `Sidebar`
  - `onboarding/` — first-run wizard
  - `run-detail/` — `PipelineStepper`, `AttemptTabs`, `RunActions`, `RunTimers`
  - `shared/` — `StatusBadge`, `PriorityIndicator`, `ConfidenceBar`, `CostDisplay`, `VerifierTypeIcon`, `FailureClassBadge`, `ServiceKindIcon`, `ArtifactTypeIcon`, `YamlEditorPanel`, `CollapsibleSection`, `DialogOverlay`, `RepoPathField`, etc.
  - `verification/` — `VerdictCard`, `VerifierMatrix`, `AggregateBar`
- **`hooks/`** — 10 custom hooks: `useBackendHealth`, `useSSE`, `useWebSocket`, `useAgentTranscript`, `useServiceLogs`, `useNotifications`, `useKeyboardShortcuts`, `useAutoUpdate`, `useTraySync`, `useUsage`
- **`screens/`** — 7 screens: `DashboardScreen`, `RunDetailScreen`, `ConfigScreen`, `SettingsScreen`, `AuthScreen`, `AuthCallbackScreen`, `DetachedLogScreen`
- **`stores/`** — 6 Zustand stores: `app.store` (global state), `run.store` (active run), `agent.store` (agent session), `logs.store` (service logs), `preferences.store` (user prefs), `auth.store` (authentication)
- **`lib/`** — routes, utilities, constants, query keys, run status helpers

## Screens

### Dashboard

The main screen showing:

- **Run History Table** — paginated list of all runs with status badges, timestamps, and quick actions
- **Quick Actions** — buttons to start a new run, open a repository, or check system health
- **System Health Widget** — backend connection status and health check indicator

### Run Detail

Detailed view of a single run with tabbed sub-views:

- **Pipeline Stepper** — visual representation of the run state machine progression
- **Attempt Tabs** — tab interface for switching between verification attempts
- **Run Actions** — resume, cancel, and other run control buttons
- **Run Timers** — elapsed time tracking for the run and current attempt
- **Verdicts** — structured verdict display with status, confidence, and observations
- **Agent Panel** — live agent transcript stream, tool call cards, cost tracker, diff viewer
- **Environment** — service topology (React Flow), boot timeline, live log tailing (xterm.js)
- **Inspection** — repo overview, impact map, inference table
- **Artifacts** — tree browser with content viewers (JSON, diff, markdown, logs, screenshots)
- **Failure** — failure analysis packets, repair patches

### Config

Configuration editor:

- **Manifest Editor** — Monaco-based YAML editor for `demiurge.yaml` with validation
- **Requirements Editor** — Monaco-based YAML editor for `requirements.yaml`
- **Budget Editor** — form-based budget parameter editing
- **Provenance View** — shows config source (explicit, cached, inferred) and last modified

### Settings

- API key management (BYOK)
- Theme preferences
- Backend connection settings
- Account / subscription info

### Auth / AuthCallback

License authentication flow — handles browser-based login and callback redirect.

### DetachedLog

Pop-out window for service log tailing via xterm.js terminal emulator.

## Prerequisites

- **Node.js >= 18** — for the React frontend build
- **Rust toolchain** — for the Tauri backend ([install](https://www.rust-lang.org/tools/install))
- **Platform-specific dependencies** — see [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/)

## Development

### Setup

```bash
cd desktop
npm install
```

### Development mode (hot reload)

```bash
npm run tauri dev
```

This starts the Vite dev server on `http://localhost:1420` with hot module replacement and launches the Tauri desktop window. The Scala backend must be running separately (or the SidecarManager will attempt to start it).

### Build for production

```bash
npm run tauri build
```

This produces a platform-specific installer in `desktop/src-tauri/target/release/bundle/`.

### Frontend only (no Tauri)

```bash
npm run dev
```

Runs just the Vite dev server for rapid frontend iteration without the Tauri shell. Useful when the Scala backend is already running independently.

## Configuration

The desktop app stores user preferences via the Tauri store plugin at the platform-specific app data directory. Preferences include:

- Backend API URL (default: `http://127.0.0.1:19440`)
- Theme preferences
- Window state (size, position — restored automatically)

## API Communication

The desktop app communicates with the Scala backend sidecar (`demiurge serve`) through:

1. **REST endpoints** — runs, config, system, agent, environment, inspection, failure, usage (see [api-reference.md](api-reference.md))
2. **SSE streaming** — `GET /runs/{id}/events` for real-time run status updates
3. **WebSocket** — `ws://127.0.0.1:19441` for real-time event broadcast, agent transcript streaming, and live service log streaming
4. **Health checks** — periodic `GET /health` polling to detect backend availability

All API requests go through a centralized client (`api/client.ts`) with error handling and the TanStack Query cache layer.
