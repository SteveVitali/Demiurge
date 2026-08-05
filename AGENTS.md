# AGENTS.md — Demiurge

## Purpose

Demiurge is a verifier-first web-task automation platform: it boots a target app in
an isolated git worktree, compiles requirements into executable verifiers (HTTP, TCP,
exec, log, state, browser flow, agentic browser), and on failure runs an agentic
repair loop via the Claude Code SDK until required verifiers pass.

## Architecture

Four deliverables share this monorepo:

| Component | Stack | Talks to |
|---|---|---|
| `modules/` (Scala backend) | Scala 2.13.18, Bazel, SQLite WAL | Spawns `worker/` over stdio JSON-RPC 2.0; serves local API on `127.0.0.1:19440` (REST/SSE) and `:19441` (WS) |
| `worker/` (browser worker) | TypeScript, Node ≥ 18, Playwright | Driven by Scala via newline-delimited JSON-RPC on stdin/stdout; runs Claude Agent SDK sessions |
| `desktop/` (GUI) | Tauri 2 (Rust) + React 19 + Vite 6 | Consumes the local API; bundles the Scala CLI as a fat-JAR sidecar |
| `web/` (demiurge.dev) | Next.js 15 App Router, Vercel | Clerk auth, Stripe billing, Keygen licensing; CLI/desktop hit its `/api/license/*` + device-auth endpoints |

Nested docs (nearest file wins): `modules/AGENTS.md`, `worker/AGENTS.md`,
`desktop/AGENTS.md`, `web/AGENTS.md`.

## Run Lifecycle

A run walks the `RunStatus` state machine (23 states, defined in
`modules/core-model/src/main/scala/demiurge/model/enums.scala`; driven by
`RunOrchestrator`):

```
Created → InspectingRepo → CompilingRequirements → PlanningEnvironment
  ├─ build mode:  → PlanningFeature → GeneratingCode → BootstrappingEnvironment
  └─ verify mode: → BootstrappingEnvironment
→ SeedingFixtures → BootstrappingAuth → ReadyToVerify → Verifying
  ├─ all Required verifiers pass → Succeeded
  └─ failure → AnalyzingFailure → PlanningRepair → Repairing
       → SoftResettingEnvironment → ReadyToVerify (loop, until Exhausted)
Any state → Cancelled (cancel cmd/API) or Interrupted (SIGINT/SIGTERM, resumable)
```

Each transition is persisted to SQLite BEFORE its side effect runs — that is what
makes `demiurge resume` crash-safe. Full diagram: `docs/architecture.md`.

## Module Layout

| Path | Contents |
|---|---|
| `modules/` | 23 Scala Bazel modules (CLI, orchestrator, persistence, verification…) — see `modules/AGENTS.md` |
| `worker/` | Playwright + agent-SDK worker, Jest tests in `worker/test/` |
| `desktop/` | React frontend (`src/`), Tauri Rust core (`src-tauri/`), sidecar packaging script |
| `web/` | Marketing site + licensing/billing backend, vitest tests in `web/src/lib/__tests__/` |
| `docs/` | Human-facing docs (architecture, CLI/API reference, configuration, development) |
| `test/fixtures/` | Integration fixtures: `simple-node-http/`, `compose-app/` |
| `test/e2e-browser-verification.mjs` | Standalone worker E2E script (spawns worker, sends JSON-RPC) |
| `homebrew/` | Homebrew cask + formula for distribution |
| `.github/workflows/` | `ci.yml` (Bazel + worker tests, desktop type-check), `release.yml` (tag-triggered sidecar + Tauri builds) |

## Key Files

| File | Lines | Purpose |
|---|---|---|
| `modules/orchestrator/src/main/scala/demiurge/orchestrator/RunOrchestrator.scala` | ~950 | Run state machine — the heart of the system |
| `modules/cli/src/main/scala/demiurge/cli/CommandParsers.scala` | ~500 | All CLI flag/command parsing (16 commands) |
| `modules/cli/src/main/scala/demiurge/cli/CliApp.scala` | ~150 | CLI dispatch; `Main.scala` is a 9-line shim over it |
| `modules/verification-engine/src/main/scala/demiurge/verification/VerificationEngine.scala` | ~400 | Verifier execution + priority-aware verdict aggregation |
| `modules/local-api/src/main/scala/demiurge/api/LocalApiServer.scala` | ~200 | Local HTTP API (JDK `com.sun.net.httpserver`, no framework) |
| `worker/src/index.ts` | ~50 | Worker entry point; registers all JSON-RPC methods |
| `desktop/src-tauri/src/sidecar.rs` | ~350 | Spawns/monitors the Scala backend sidecar |
| `web/src/lib/keygen.ts` | ~300 | Keygen license API client (validate/activate/usage) |
| `MODULE.bazel` | ~50 | Bazel deps — the ONLY place Maven artifacts are declared |

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Bazel | 9.0+ (Bzlmod) | Scala backend — JDK 17 fetched automatically (`remotejdk_17`) |
| Node.js | ≥ 18 (CI: 20) | `worker/`, `desktop/`, `web/` |
| Git | ≥ 2.20 | Worktree isolation |
| Rust toolchain | stable | Desktop (`npm run tauri dev/build`) only |
| Docker + Compose V2 | optional | Compose-based target services |

`bazel run //modules/cli:demiurge -- doctor` checks all of these.

## Build & Test

```bash
# Scala: build all 48 targets / run all 23 test targets
bazel build //...
bazel test //...

# Single module / single test class
bazel test //modules/orchestrator:orchestrator-tests --test_output=all --test_filter="*SignalHandler*"

# Run the CLI from source; build the fat JAR (used by desktop sidecar + releases)
bazel run //modules/cli:demiurge -- doctor
bazel build //modules/cli:demiurge_deploy.jar

# Worker (from worker/): build, type-check, test
npm install && npm run build && npm run lint && npm test

# Desktop (from desktop/): type-check (the CI gate), dev, production build
npx tsc --noEmit
npm run tauri dev                      # Vite dev server on localhost:1420
bash scripts/package-sidecar.sh && npm run tauri build   # script: desktop/scripts/package-sidecar.sh

# Web (from web/): dev, test, lint, build
npm run dev && npm test && npm run lint && npm run build
```

## CLI Surface

16 commands, parsed in `modules/cli/src/main/scala/demiurge/cli/CommandParsers.scala`,
handled one-file-per-command in `modules/cli/src/main/scala/demiurge/cli/Commands/`:

| Group | Commands |
|---|---|
| Run lifecycle | `run`, `build`, `plan`, `resume`, `cancel`, `serve` |
| Inspection | `status`, `inspect-run`, `open-artifact`, `explain-failure` |
| Setup/maintenance | `init` (`--smart` for agentic generation), `doctor`, `clean`, `config` |
| Account | `login`, `logout` |

Flags, exit codes, and output formats: `docs/cli-reference.md`. When adding or
changing a command, update that doc — it is published to demiurge.dev.

## Environment Variables

| Variable | Purpose |
|---|---|
| `ANTHROPIC_API_KEY` | Enables the agent backend (repair, `init --smart`, browser verification) |
| `DEMIURGE_WORKER_PATH` | Path to the worker entry point; read in `OrchestrationRunner.scala` |
| `DEMIURGE_AGENT_BACKEND` | `none` disables the agent backend (falls back to legacy patch repair) |
| `CLAUDE_CODE_EXECUTABLE` | Path to the `claude` CLI; read in `modules/agent-backend/src/main/scala/demiurge/agent/AgentConfig.scala` |

## Code Conventions

- **No linter/formatter configs exist** (no eslint, prettier, scalafmt, editorconfig).
  Match the style of surrounding code exactly; nothing will auto-fix you.
- **Scala enums** — sealed trait + case object (Scala 2 style), all in
  `modules/core-model/src/main/scala/demiurge/model/enums.scala` and sibling
  `*_types.scala` files.
- **JSON** — circe semiauto derivation for every DTO; codecs live beside the types.
- **Scala packages** are short names (`demiurge.model`, `demiurge.agent`), NOT the
  module directory name — see the mapping table in `modules/AGENTS.md`.
- **TypeScript** — `strict: true` in all three TS projects; desktop additionally has
  `noUnusedLocals`, `noUnusedParameters`, `noUncheckedIndexedAccess`.
- **State machine invariant** — persist to SQLite before executing any side effect.
  Never reorder a transition write after its side effect.

## Critical Gotchas

1. **Scala test classes MUST end in `Suite`** — every `scala_junit_test` target sets
   `suffixes = ["Suite"]`. A test named `FooTest` compiles fine and silently never runs.
2. **Worker stdout is the wire protocol** — the worker speaks newline-delimited
   JSON-RPC on stdout. A stray `console.log` corrupts the protocol. Log with
   `process.stderr.write` only (see `worker/src/index.ts`).
3. **Playwright is pinned exactly** (`"playwright": "1.42.1"`, no caret) and
   `@anthropic-ai/claude-code` must stay `^1.x` — the 2.x package is CLI-only with no
   SDK exports. Don't bump either casually.
4. **Maven deps go only in `MODULE.bazel`** (`maven.install` block), then referenced
   as `@maven//:io_circe_circe_core_2_13` in each `BUILD.bazel`. Adding a dep in one
   place without the other fails at build time.
5. **Desktop CI gate is `npx tsc --noEmit`** with the strictest flags — an unused
   import or unchecked index access fails CI. There are no desktop unit tests.
6. **`desktop/src-tauri/binaries/` is gitignored** — `npm run tauri build` needs
   `desktop/scripts/package-sidecar.sh` run first to place the sidecar launcher
   (`externalBin: binaries/demiurge-sidecar` in `tauri.conf.json`).
7. **`MODULE.bazel.lock` is gitignored** — don't commit it, and don't rely on it
   being present. `bazel-*` symlinks and `worker/dist/` are also gitignored.
8. **Web vitest suite is NOT in CI** — `ci.yml` never enters `web/`. Run
   `npm test` in `web/` manually before merging web changes.
9. **`web/src/lib/docs.ts` reads `../docs/*.md` at build time** — renaming or moving
   files in `docs/` silently drops pages from demiurge.dev.
10. **Runs mutate the target repo's `.demiurge/`** — SQLite DB, artifacts, and lock
    file are created there; `demiurge clean --all` removes them.

## Data & Artifacts

Running Demiurge against a repo creates `<target-repo>/.demiurge/`:

```
.demiurge/
├── demiurge.db          # SQLite (WAL) — 17 tables, schema in modules/persistence migrations
├── artifacts/<runId>/   # screenshots, logs, verdicts, transcripts (SHA-256 named)
└── run.lock             # active-run lock
```

Inspect with `sqlite3 .demiurge/demiurge.db` — and note this repo's own
`.demiurge/` (from self-runs) is gitignored, like `.env` and `.internal-docs/`.

## Terminology

| Term | Meaning |
|---|---|
| Verifier | Executable check compiled from a requirement (HTTP, TCP, exec, log, state, browser flow, agent browser) |
| Verdict | Structured pass/fail/flake result of a verifier; only `Required` priority failures block success |
| Manifest | `demiurge.yaml` in the target repo — services, fixtures, auth, budgets |
| Requirement graph | Compiled `requirements.yaml` + selectors → `VerifierSpec`s |
| Run / Attempt | One `demiurge run` invocation / one verify-repair cycle within it |
| Build mode | `demiurge build` — plan + generate a feature from a task string, then verify |
| Agent backend | Scala→worker bridge that runs Claude Code SDK sessions for repair/init/browser verification |
| Worker | The `worker/` Node process, spawned per run via `DEMIURGE_WORKER_PATH` |
| Sidecar | The `demiurge_deploy.jar` bundled inside the desktop app |
| Worktree isolation | Each run executes in a dedicated git worktree, not your checkout |

## Do

- Run `bazel test //...` and `worker/ npm test` before committing cross-cutting changes.
- Name new Scala test classes `*Suite` and new zustand stores `*.store.ts`.
- Keep `docs/*.md` in sync when changing CLI flags, API endpoints, or manifest schema.
- Use `demiurge doctor` (`bazel run //modules/cli:demiurge -- doctor`) to check prerequisites.

## Don't

- Don't `console.log` in `worker/src/` — stderr only.
- Don't hand-edit `package-lock.json`, `MODULE.bazel.lock`, `worker/dist/`, or
  `desktop/src-tauri/gen/`.
- Don't add an HTTP framework to `modules/local-api` — it deliberately uses the JDK
  built-in server.
- Don't reference `.internal-docs/` — it is gitignored scratch/spec space, not part
  of the shipped codebase.
- Don't commit `.env` or any `sk_`/`whsec_`/API-key material (see `web/.env.example`).

## Boundaries

- **Safe**: anything under `modules/`, `worker/src`, `worker/test`, `desktop/src`,
  `web/src`, `docs/` following the conventions above.
- **Ask first**: `release.yml`, `tauri.conf.json` (updater/signing config),
  `homebrew/`, Stripe/Keygen/Clerk integration logic in `web/src/lib/`.
- **Never**: commit secrets; weaken the persist-before-side-effects invariant;
  delete or rename migrations in `modules/persistence/src/main/resources/migrations/`
  (append `V00N__*.sql` instead).
