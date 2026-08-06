# AGENTS.md — modules/ (Scala backend)

## Purpose

All 23 Bazel-built Scala 2.13.18 modules: the CLI, run orchestrator, verification
engine, persistence layer, and every supporting library. This is the system's core;
`worker/`, `desktop/`, and `web/` orbit it.

## Module Map

Scala package names do NOT match directory names. Always import by package.

| Module dir | Package (`demiurge.*`) | Purpose |
|---|---|---|
| `core-model` | `model` | Sealed-trait enums + ~117 case class DTOs + circe codecs; depended on by everything |
| `persistence` | `persistence` | SQLite WAL `Database`, `Migrator`, `TransactionManager`, 13 repos |
| `orchestrator` | `orchestrator` | `RunOrchestrator` state machine, attempt/repair/resume/lock/worktree managers |
| `cli` | `cli` | 16 commands, arg parsing, output formatting; produces the `demiurge` binary |
| `local-api` | `api` | REST + SSE server on `127.0.0.1:19440` (JDK httpserver, no framework) |
| `manifest` | `manifest` | `demiurge.yaml` parser (SnakeYAML) |
| `requirements` | `requirements` | `requirements.yaml` parser/validator |
| `selectors` | `selectors` | Selector config parser (CSS/XPath/test-id strategies) |
| `config-resolver` | `config` | Layered config resolution (explicit YAML → cached inference) |
| `repo-inspector` | `inspector` | Repository analysis, changed-file impact mapping |
| `requirement-compiler` | `compiler` | Requirements + selectors → `RequirementGraph` of `VerifierSpec`s |
| `environment-planner` | `planner` | `RuntimePlan` generation from inspection + manifest |
| `runtime-supervisor` | `runtime` | Service boot/teardown, readiness probes, fixtures |
| `verification-engine` | `verification` | Verifier generation/execution, priority-aware aggregation |
| `agent-backend` | `agent` | Claude Agent SDK bridge to the worker (repair, init, browser verification) |
| `failure-analysis` | `analysis` | LLM + rule-based failure analysis |
| `inference` | `inference` | LLM gateway: budget, cache, timeout, replay |
| `repair-api` | `repair` | `FailurePacket` builder, `PatchProposal` DTOs, `PatchApplier` |
| `repair-claude` | `repair` | Legacy single-shot Claude patch repair (fallback backend) |
| `artifact-store` | `artifact` | Artifact sink (temp-then-rename, SHA-256, gzip), evidence collector |
| `worker-protocol` | `worker` | JSON-RPC 2.0 client + `WorkerProcessManager` |
| `license` | `license` | License validation, credential storage, usage tracking, cloud API client |
| `policy` | `policy` | Filesystem/network/browser/tool/destructive-action policy enforcement |

Note: `repair-api` and `repair-claude` share the `demiurge.repair` package.

## Key Files

| File | Lines | Purpose |
|---|---|---|
| `orchestrator/src/main/scala/demiurge/orchestrator/RunOrchestrator.scala` | ~950 | State machine; every phase transition lives here |
| `cli/src/main/scala/demiurge/cli/CommandParsers.scala` | ~500 | Flag parsing for all 16 commands |
| `verification-engine/src/main/scala/demiurge/verification/VerificationEngine.scala` | ~400 | Verifier exec + verdict aggregation |
| `agent-backend/src/main/scala/demiurge/agent/AgentToolRpcHandlers.scala` | ~350 | Scala-side handlers for the worker's MCP tool callbacks |
| `local-api/src/main/scala/demiurge/api/LocalApiServer.scala` | ~200 | HTTP server + route wiring |
| `core-model/src/main/scala/demiurge/model/enums.scala` | — | ALL sealed-trait enums (`RunStatus` has 23 values) |
| `persistence/src/main/resources/migrations/V001__initial.sql`, `V002__build_mode.sql` | — | Schema: 17 tables |

## Build & Test

```bash
bazel build //...                 # all modules
bazel test //...                  # all 23 test targets
bazel build //modules/cli:demiurge_deploy.jar   # fat JAR (sidecar/releases)
bazel run //modules/cli:demiurge -- run --task "..." --repo <path>

# One module (note orchestrator's library target is orchestrator-lib):
bazel test //modules/orchestrator:orchestrator-tests
bazel build //modules/orchestrator:orchestrator-lib

# One test class:
bazel test //modules/cli:cli-tests --test_output=all --test_filter="*RunCommand*"
```

## Code Conventions

- **Enums**: `sealed trait Foo` + `case object` members in an `object Foo` companion
  — CORRECT ✓. Scala 3 `enum` syntax — WRONG ✗ (this is Scala 2.13).
- **JSON**: circe `semiauto` derivation. New DTOs get codecs in the same file or
  `core-model`'s `json.scala` — never hand-rolled string JSON.
- **Tests**: MUnit 1.0.3, classes end in `Suite`, mirrored under
  `src/test/scala/demiurge/<pkg>/`.
- **New module**: follow the `BUILD.bazel` template in `docs/development.md`
  ("Adding a New Module") — `scala_library` + `scala_junit_test` with
  `suffixes = ["Suite"]` and `allow_empty = True` globs.

## Critical Gotchas

1. **Test classes not ending in `Suite` never run** — no error, no skip report;
   `scala_junit_test(suffixes = ["Suite"])` simply ignores them.
2. **`//modules/orchestrator` has no default target** — library is
   `:orchestrator-lib`, binary is `:orchestrator`, tests are `:orchestrator-tests`.
   Every other module uses `:<dirname>` for its library.
3. **Maven artifacts must exist in `MODULE.bazel`'s `maven.install`** before a
   `@maven//:...` label resolves. Version bumps happen there, nowhere else.
4. **Migrations are append-only** — `Migrator` applies `V00N__*.sql` in order from
   `persistence/src/main/resources/migrations/`. Editing an applied migration breaks
   existing `.demiurge/demiurge.db` files; add a new `V00N` instead.
5. **`docs/development.md`'s dependency graph is stale** — trust each module's
   `BUILD.bazel` `deps`, not the doc (e.g. orchestrator also deps on `license`,
   `manifest`, `agent-backend`, `config-resolver`, `policy`).
6. **Persist-before-side-effects** — in `RunOrchestrator`, the SQLite write for a
   transition must precede its side effect. Crash-resume correctness depends on it.
7. **Agent env vars flow through Scala, not the worker**: `ANTHROPIC_API_KEY`,
   `DEMIURGE_WORKER_PATH`, `DEMIURGE_AGENT_BACKEND`, `CLAUDE_CODE_EXECUTABLE` are
   read in `cli`/`agent-backend` (`AgentConfig.scala`) and passed down.

## Source Layout

```
modules/<name>/
├── BUILD.bazel                      # scala_library + scala_junit_test (+ scala_binary in cli, orchestrator)
└── src/
    ├── main/scala/demiurge/<pkg>/   # see package mapping table above
    └── test/scala/demiurge/<pkg>/   # *Suite.scala only
```
