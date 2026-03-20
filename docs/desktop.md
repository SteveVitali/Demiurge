# Desktop Application

Demiurge includes a native desktop application for monitoring runs, inspecting verdicts and artifacts, and controlling the orchestration pipeline through a graphical interface.

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Native shell | Tauri 2 (Rust) |
| Frontend framework | React 19 |
| Build tool | Vite 6 |
| Routing | TanStack Router |
| Server state | TanStack Query 5 |
| Client state | Zustand 5 |
| Styling | Tailwind CSS 4 |
| Animations | Framer Motion 11 |
| Icons | Lucide React |

## Architecture

The desktop app is a Tauri 2 application with a React frontend. It communicates with the Demiurge Scala backend via the local HTTP API (`127.0.0.1:19440`).

```
┌──────────────────────────────────┐
│       Tauri 2 Window             │
│  ┌────────────────────────────┐  │
│  │     React Frontend         │  │
│  │  TanStack Query + Zustand  │  │
│  └────────────┬───────────────┘  │
│               │ HTTP + SSE       │
│  ┌────────────┴───────────────┐  │
│  │   Tauri Rust Backend       │  │
│  │  SidecarManager · Tray     │  │
│  └────────────────────────────┘  │
└──────────────────────────────────┘
         │
         │ HTTP (127.0.0.1:19440)
         ▼
┌──────────────────────────────────┐
│   Demiurge Scala Backend         │
│   Local API Server               │
└──────────────────────────────────┘
```

### Rust Backend (src-tauri)

The Tauri Rust backend provides:

- **SidecarManager** — manages the lifecycle of the Scala backend process (start, stop, restart, status)
- **Tauri Commands** — `start_backend`, `stop_backend`, `restart_backend`, `get_backend_status`, `open_folder_dialog`
- **System Tray** — tray icon with quick access menu
- **Plugins** — shell (process spawning), dialog (file/folder pickers), notification, store (persistent preferences), window-state (remember size/position)

### React Frontend (src)

The frontend is structured as:

- **`api/`** — HTTP client, SSE event stream handler, TypeScript API types
- **`components/`** — reusable UI components organized by feature:
  - `layout/` — `AppLayout`, `Sidebar` (navigation)
  - `dashboard/` — `QuickActions`, `RunHistoryTable`, `SystemHealthWidget`
  - `run-detail/` — `PipelineStepper`, `AttemptTabs`, `RunActions`, `RunTimers`
  - `shared/` — `StatusBadge`, `PriorityIndicator`, `LoadingSpinner`, `EmptyState`, `ErrorState`, `ElapsedTimer`
- **`hooks/`** — `useBackendHealth` (health polling), `useSSE` (SSE event subscription)
- **`screens/`** — `DashboardScreen`, `RunDetailScreen`, `PlaceholderScreen`
- **`stores/`** — Zustand stores for app state, preferences, and run data
- **`lib/`** — routes, utilities, constants, query keys, run status helpers

## Screens

### Dashboard

The main screen showing:

- **Run History Table** — paginated list of all runs with status badges, timestamps, and quick actions
- **Quick Actions** — buttons to start a new run, open a repository, or check system health
- **System Health Widget** — backend connection status and health check indicator

### Run Detail

Detailed view of a single run:

- **Pipeline Stepper** — visual representation of the run state machine progression
- **Attempt Tabs** — tab interface for switching between verification attempts
- **Run Actions** — resume, cancel, and other run control buttons
- **Run Timers** — elapsed time tracking for the run and current attempt
- **Verdicts** — structured verdict display with status, confidence, and observations
- **Artifacts** — browseable list of run artifacts (screenshots, logs, reports)

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

The desktop app communicates with the Scala backend through:

1. **REST endpoints** — `GET /runs`, `GET /runs/active`, `GET /runs/{id}`, `POST /runs`, etc.
2. **SSE streaming** — `GET /runs/{id}/events` for real-time run status updates
3. **Health checks** — periodic `GET /health` polling to detect backend availability

All API requests go through a centralized client (`api/client.ts`) with error handling and the TanStack Query cache layer.
