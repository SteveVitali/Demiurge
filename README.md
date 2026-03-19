# Demiurge

**Last-Mile Web Development Automation Platform** — a verifier-first system that automates closing the gap between code generation and verified completion of full-stack web tasks.

Demiurge orchestrates environment setup, requirement verification, failure analysis, and automated repair for web applications. It boots your app, runs structured verifiers (HTTP, TCP, browser flows, state assertions), and when things fail, it analyzes the failure and attempts LLM-powered repair — all in an isolated git worktree.

## Key Features

- **Verifier-first architecture** — requirements are compiled into executable verifiers (HTTP, TCP, exec, log, state, browser flow) that produce structured verdicts
- **Automated environment management** — boots services via scripts or Docker Compose, runs readiness probes, seeds fixtures
- **Browser automation** — Playwright-based browser worker (TypeScript) for UI flow verification, screenshot capture, DOM/accessibility snapshots
- **Agentic repair via Claude Code** — when verification fails, Demiurge delegates to the Claude Code agent (multi-turn, with file editing and shell access) to fix the issue; legacy single-shot LLM patch repair available as fallback
- **Auto-configuration** — when no `demiurge.yaml` exists, `demiurge run` automatically invokes the Claude Code agent to generate configuration files tailored to your project
- **Isolated execution** — each run operates in a dedicated git worktree with its own SQLite database, artifacts, and lock file
- **Structured observability** — JSON event stream, SSE-capable local API, structured logging, full artifact capture
- **Smart resume** — interrupted runs resume from the last completed phase, skipping already-persisted work; attempt counters continue where they left off
- **Signal handling** — SIGINT/SIGTERM are handled gracefully with state persistence
- **CI/CD** — GitHub Actions workflow builds all targets and runs all tests on every push and PR
- **Desktop GUI** — native Tauri v2 + React desktop application with full CLI parity, real-time pipeline observability, artifact browsing, and interactive configuration editing

## Architecture Overview

Demiurge is a **Scala 2.13 + TypeScript + Rust** triple-stack project built with **Bazel** (backend) and **Tauri v2** (desktop).

```
┌─────────────────────────────────────────────────────┐
│                    CLI (demiurge)                    │
│  run · plan · resume · status · inspect-run · ...   │
├─────────────────────────────────────────────────────┤
│                   Orchestrator                       │
│  RunOrchestrator · RunTransitionManager · Signals   │
├──────────┬──────────┬───────────┬───────────────────┤
│  Repo    │ Require- │ Environ-  │   Verification    │
│Inspector │  ment    │   ment    │     Engine        │
│          │ Compiler │  Planner  │ (HTTP/TCP/Browser) │
├──────────┴──────────┴───────────┼───────────────────┤
│         Runtime Supervisor      │  Worker Protocol  │
│   (boot, teardown, fixtures)    │  (JSON-RPC 2.0)   │
├─────────────────────────────────┼───────────────────┤
│  Agent Backend · Repair API     │  Browser Worker   │
│  Failure Analysis · Inference   │  (TypeScript/     │
│  Artifact Store · Evidence      │   Playwright)     │
├─────────────────────────────────┴───────────────────┤
│              Persistence (SQLite WAL)                │
│  TaskRun · Attempt · Verdict · Event · Artifact     │
└─────────────────────────────────────────────────────┘
```

### Module Map

| Module | Language | Purpose |
|--------|----------|---------|
| `modules/core-model` | Scala | 22 enums, 79+ case classes, JSON codecs (circe) |
| `modules/persistence` | Scala | SQLite WAL, migrations, repos (TaskRun, Attempt, Verdict, Event, Artifact, etc.) |
| `modules/orchestrator` | Scala | Run state machine, transition manager, signal handler, timeout enforcer, resume manager |
| `modules/cli` | Scala | 13 CLI commands, arg parsing, output formatting (human/JSON) |
| `modules/local-api` | Scala | HTTP server (127.0.0.1:19440), REST + SSE event streaming |
| `modules/manifest` | Scala | `demiurge.yaml` parser (SnakeYAML) |
| `modules/config-resolver` | Scala | Layered config resolution (explicit YAML → cached inference), `ResolvedConfig` DTOs |
| `modules/repo-inspector` | Scala | Repository analysis, changed-file impact mapping |
| `modules/requirement-compiler` | Scala | Requirements + selectors → RequirementGraph with VerifierSpecs |
| `modules/agent-backend` | Scala | Agent SDK integration — bridges orchestrator to TypeScript worker for agentic operations |
| `modules/requirements` | Scala | `requirements.yaml` parser and validator |
| `modules/selectors` | Scala | `selectors.yaml` parser (CSS/XPath/test-id strategies) |
| `modules/environment-planner` | Scala | RuntimePlan generation from inspection + requirements |
| `modules/runtime-supervisor` | Scala | Service boot/teardown, readiness probes, fixture execution |
| `modules/verification-engine` | Scala | Verifier generation, execution, verdict aggregation |
| `modules/failure-analysis` | Scala | LLM-backed + rule-based failure analysis |
| `modules/inference` | Scala | LLM inference gateway with budget, cache, timeout, replay |
| `modules/repair-api` | Scala | FailurePacket builder, PatchProposal DTOs, PatchApplier |
| `modules/repair-claude` | Scala | Claude API client, prompt builder, repair backend |
| `modules/artifact-store` | Scala | Artifact sink (temp-then-rename, SHA-256, gzip), evidence collector |
| `modules/worker-protocol` | Scala | JSON-RPC 2.0 client, WorkerProcessManager, message types |
| `modules/policy` | Scala | Policy enforcement (stub) |
| `worker/` | TypeScript | Playwright browser worker — stdio JSON-RPC 2.0 bridge, Claude Agent SDK integration |
| `desktop/` | TypeScript + Rust | Tauri v2 desktop GUI — React frontend, Zustand state, sidecar management |

## Prerequisites

- **Java 17** (provided by Bazel via `remotejdk_17`)
- **Bazel 9.0+** (with Bzlmod)
- **Node.js >= 18** (for browser worker)
- **Git >= 2.20**
- **Docker + Docker Compose V2** (optional, for compose-based services)
- **Playwright browsers** (optional, for browser flow verification)

Run `demiurge doctor` to check all prerequisites.

## Quick Start

### Build

```bash
bazel build //...
```

### Test

```bash
# Scala tests (20+ test targets)
bazel test //...

# TypeScript worker tests
cd worker && npm install && npm test
```

### Run

```bash
# Execute a verification run
demiurge run --task "Verify the login flow works end-to-end"

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
- [Development Guide](docs/development.md) — building, testing, contributing, module structure
- [Desktop App Design](docs/design-desktop-app.md) — desktop GUI architecture, screens, component library, packaging

## License

[MIT](LICENSE) — Copyright (c) 2026 Steven Vitali
