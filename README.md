# Demiurge

**Last-Mile Web Development Automation Platform** — a verifier-first system that automates closing the gap between code generation and verified completion of full-stack web tasks.

Demiurge orchestrates environment setup, requirement verification, failure analysis, and automated repair for web applications. It boots your app, runs structured verifiers (HTTP, TCP, browser flows, state assertions), and when things fail, it analyzes the failure and attempts LLM-powered repair — all in an isolated git worktree.

## Key Features

- **Verifier-first architecture** — requirements are compiled into executable verifiers (HTTP, TCP, exec, log, state, browser flow, agentic browser) that produce structured verdicts
- **Automated environment management** — boots services via scripts or Docker Compose, runs readiness probes, seeds fixtures
- **Browser automation** — Playwright-based browser worker (TypeScript) for UI flow verification, screenshot capture, DOM/accessibility snapshots
- **Agentic browser verification** — Claude Agent SDK + Playwright MCP server for LLM-driven UI verification with visual taste judgment, multi-viewport testing, and structured observation capture
- **Agentic repair via Claude Code** — when verification fails, Demiurge delegates to the Claude Code agent (multi-turn, with file editing and shell access) to fix the issue; legacy single-shot LLM patch repair available as fallback
- **Build mode** — autonomous feature generation from a task description: plans implementation, generates code, boots environment, verifies, and repairs in a loop until all verifiers pass
- **Auto-configuration** — when no `demiurge.yaml` exists, `demiurge run` automatically invokes the Claude Code agent to generate configuration files tailored to your project
- **Desktop application** — Tauri 2 + React desktop GUI with full CLI parity: real-time pipeline monitoring, agent transcript streaming, artifact browsing, configuration editing, and environment management
- **License management** — `demiurge login` / `logout` for authentication, usage tracking, and plan-based limits
- **Isolated execution** — each run operates in a dedicated git worktree with its own SQLite database, artifacts, and lock file
- **Structured observability** — JSON event stream, SSE-capable local API, structured logging, full artifact capture
- **Smart resume** — interrupted runs resume from the last completed phase, skipping already-persisted work; attempt counters continue where they left off
- **Signal handling** — SIGINT/SIGTERM are handled gracefully with state persistence
- **CI/CD** — GitHub Actions workflow builds all Bazel targets and runs Scala + worker tests on pushes and PRs to `main` (desktop is type-checked; `web/` tests run locally)

## Architecture Overview

Demiurge is a **Scala 2.13 + TypeScript + Rust** multi-stack project built with **Bazel** (Scala backend) and **Tauri** (desktop app).

```
┌─────────────────────────────────────────────────────────┐
│              Desktop App (Tauri 2 + React)               │
│    Dashboard · Run Detail · Verdicts · Artifacts        │
├─────────────────────────────────────────────────────────┤
│                     CLI (demiurge)                       │
│  run · build · plan · resume · status · inspect-run · … │
├─────────────────────────────────────────────────────────┤
│              Local API (127.0.0.1:19440)                 │
│    REST + SSE + WebSocket · CORS · Paginated Endpoints   │
├─────────────────────────────────────────────────────────┤
│                      Orchestrator                        │
│  RunOrchestrator · RunTransitionManager · Signals        │
├──────────┬──────────┬───────────┬───────────────────────┤
│  Repo    │ Require- │ Environ-  │    Verification       │
│Inspector │  ment    │   ment    │      Engine           │
│          │ Compiler │  Planner  │ HTTP/TCP/Browser/Agent │
├──────────┴──────────┴───────────┼───────────────────────┤
│       Runtime Supervisor        │   Worker Protocol     │
│  (boot, teardown, fixtures)     │   (JSON-RPC 2.0)      │
├─────────────────────────────────┼───────────────────────┤
│  Agent Backend · Repair API     │   Browser Worker      │
│  Failure Analysis · Inference   │   (TypeScript/        │
│  Artifact Store · Evidence      │    Playwright)        │
├─────────────────────────────────┴───────────────────────┤
│               Persistence (SQLite WAL)                   │
│   TaskRun · Attempt · Verdict · Event · Artifact         │
└─────────────────────────────────────────────────────────┘
```

### Module Map

| Module | Language | Purpose |
|--------|----------|---------|
| `modules/core-model` | Scala | 27 enum-like sealed traits, 116+ case classes, JSON codecs (circe) |
| `modules/persistence` | Scala | SQLite WAL, migrations, repos (TaskRun, Attempt, Verdict, Event, Artifact, etc.) |
| `modules/orchestrator` | Scala | Run state machine, transition manager, signal handler, timeout enforcer, resume manager |
| `modules/cli` | Scala | 16 CLI commands, arg parsing, output formatting (human/JSON) |
| `modules/local-api` | Scala | HTTP + WebSocket server (127.0.0.1:19440), REST + SSE + WS, CORS, config/system/agent routes |
| `modules/manifest` | Scala | `demiurge.yaml` parser (SnakeYAML) |
| `modules/config-resolver` | Scala | Layered config resolution (explicit YAML → cached inference), `ResolvedConfig` DTOs |
| `modules/repo-inspector` | Scala | Repository analysis, changed-file impact mapping |
| `modules/requirement-compiler` | Scala | Requirements + selectors → RequirementGraph with VerifierSpecs |
| `modules/agent-backend` | Scala | Agent SDK integration — bridges orchestrator to TypeScript worker for agentic repair, build, and browser verification |
| `modules/requirements` | Scala | `requirements.yaml` parser and validator |
| `modules/selectors` | Scala | `selectors.yaml` parser (CSS/XPath/test-id strategies) |
| `modules/environment-planner` | Scala | RuntimePlan generation from inspection + requirements |
| `modules/runtime-supervisor` | Scala | Service boot/teardown, readiness probes, fixture execution |
| `modules/verification-engine` | Scala | Verifier generation, execution, verdict aggregation (priority-aware) |
| `modules/failure-analysis` | Scala | LLM-backed + rule-based failure analysis |
| `modules/inference` | Scala | LLM inference gateway with budget, cache, timeout, replay |
| `modules/repair-api` | Scala | FailurePacket builder, PatchProposal DTOs, PatchApplier |
| `modules/repair-claude` | Scala | Claude API client, prompt builder, repair backend |
| `modules/artifact-store` | Scala | Artifact sink (temp-then-rename, SHA-256, gzip), evidence collector |
| `modules/worker-protocol` | Scala | JSON-RPC 2.0 client, WorkerProcessManager, message types |
| `modules/license` | Scala | License validation, credential storage, usage tracking, cloud API client |
| `modules/policy` | Scala | Policy enforcement (filesystem, network, browser, tool, destructive action) |
| `worker/` | TypeScript | Playwright browser worker — stdio JSON-RPC 2.0 bridge, agent SDK execution |
| `desktop/` | TypeScript + Rust | Tauri 2 desktop application — 7 screens, 15 component groups, sidecar management |

## Prerequisites

- **Java 17** (provided by Bazel via `remotejdk_17`)
- **Bazel 9.0+** (with Bzlmod)
- **Node.js >= 18** (for browser worker and desktop app)
- **Git >= 2.20**
- **Docker + Docker Compose V2** (optional, for compose-based services)
- **Playwright browsers** (optional, for browser flow verification)
- **Rust toolchain** (optional, for desktop app development)

Run `demiurge doctor` to check all prerequisites.

## Quick Start

### Build

```bash
# Build all Scala modules (48 targets)
bazel build //...
```

### Test

```bash
# Scala tests (23 test targets)
bazel test //...

# TypeScript worker tests (4 test suites)
cd worker && npm install && npm test
```

### Run

```bash
# Execute a verification run
demiurge run --task "Verify the login flow works end-to-end"

# Build mode — generate code from a task description
demiurge build --task "Add user registration with email/password"

# Plan without executing
demiurge plan --task "Add user registration" --changed-files "src/auth.ts,src/pages/register.tsx"

# Check run status
demiurge status --run-id <run-id>

# Resume an interrupted run
demiurge resume --run-id <run-id>

# Check system prerequisites
demiurge doctor
```

## Configuration

Demiurge is configured via a `demiurge.yaml` manifest in your repository root. Generate configuration:

```bash
# Deterministic scaffold (no LLM)
demiurge init

# Agentic generation via Claude Code CLI (recommended)
demiurge init --smart
```

See [docs/configuration.md](docs/configuration.md) for the full manifest schema reference.

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `ANTHROPIC_API_KEY` | For repair/init | Claude API key — enables agent backend (Claude Code) for repair and auto-config generation |
| `DEMIURGE_WORKER_PATH` | For agent backend | Path to the Demiurge TypeScript worker entry point (auto-detected if installed) |
| `DEMIURGE_AGENT_BACKEND` | No | Set to `none` to disable agent backend and use legacy LLM patch repair |
| `CLAUDE_CODE_EXECUTABLE` | No | Path to the `claude` CLI binary (auto-detected via `which claude`) |

## Documentation

- [Architecture](docs/architecture.md) — system design, module responsibilities, state machine, data flow
- [CLI Reference](docs/cli-reference.md) — all commands, flags, exit codes, output formats
- [API Reference](docs/api-reference.md) — local HTTP API endpoints, SSE event streaming
- [Configuration](docs/configuration.md) — `demiurge.yaml` manifest, `requirements.yaml`, `selectors.yaml`
- [Desktop Application](docs/desktop.md) — Tauri desktop GUI, setup, and development
- [Development Guide](docs/development.md) — building, testing, contributing, module structure
- [Contributing](CONTRIBUTING.md) — how to set up, test, and submit changes

## License

[Business Source License 1.1](LICENSE) — Copyright (c) 2026 Steven Vitali
