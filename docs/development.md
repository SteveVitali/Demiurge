# Development Guide

This guide covers building, testing, and contributing to the Demiurge project.

## Prerequisites

- **Bazel 9.0+** — build system ([install guide](https://bazel.build/install))
- **Java 17** — provided automatically by Bazel via `remotejdk_17`
- **Node.js >= 18** — for the TypeScript browser worker
- **npm** — for worker dependency management
- **Git >= 2.20** — for worktree operations

## Project Structure

```
Demiurge/
├── modules/                    # Scala modules (Bazel)
│   ├── core-model/             # DTOs, enums, JSON codecs
│   ├── persistence/            # SQLite repos, migrations
│   ├── orchestrator/           # Run state machine, transitions
│   ├── cli/                    # CLI commands, arg parsing
│   ├── local-api/              # HTTP server, SSE streaming
│   ├── manifest/               # lastmile.yaml parser
│   ├── repo-inspector/         # Repository analysis
│   ├── requirement-compiler/   # Requirements → RequirementGraph
│   ├── requirements/           # requirements.yaml parser
│   ├── selectors/              # selectors.yaml parser
│   ├── environment-planner/    # RuntimePlan generation
│   ├── runtime-supervisor/     # Service boot/teardown
│   ├── verification-engine/    # Verifier gen/exec/aggregation
│   ├── failure-analysis/       # LLM + rule-based failure analysis
│   ├── inference/              # LLM inference gateway
│   ├── repair-api/             # Repair DTOs, patch applier
│   ├── repair-claude/          # Claude repair backend
│   ├── artifact-store/         # Artifact sink, evidence collector
│   ├── worker-protocol/        # JSON-RPC 2.0 worker client
│   └── policy/                 # Policy enforcement (stub)
├── worker/                     # TypeScript browser worker
│   ├── src/                    # Source files
│   │   ├── rpc/                # JSON-RPC 2.0 server
│   │   ├── browser/            # BrowserManager (Playwright)
│   │   ├── artifacts/          # ArtifactWriter
│   │   ├── methods/            # RPC method handlers
│   │   ├── utils/              # Checksum utilities
│   │   └── index.ts            # Entry point
│   └── test/                   # Jest test suites
├── test/
│   └── fixtures/               # Integration test fixtures
│       ├── simple-node-http/   # Simple Node.js API fixture
│       └── compose-app/        # Docker Compose fullstack fixture
├── MODULE.bazel                # Bazel module definition
├── .bazelrc                    # Bazel build settings
├── BUILD.bazel                 # Root BUILD file
└── LICENSE                     # MIT License
```

## Building

### Build all Scala modules

```bash
bazel build //...
```

This builds all 20+ Scala modules. Bazel handles dependency resolution, Scala compilation, and Java runtime automatically.

### Build a specific module

```bash
bazel build //modules/orchestrator
bazel build //modules/cli
bazel build //modules/verification-engine
```

### Build the TypeScript worker

```bash
cd worker
npm install
npm run build
```

The worker compiles to `worker/dist/`.

## Testing

### Run all Scala tests

```bash
bazel test //...
```

This runs 19+ test targets across all modules. Tests use MUnit 1.0.3.

### Run tests for a specific module

```bash
bazel test //modules/core-model:core-model-tests
bazel test //modules/persistence:persistence-tests
bazel test //modules/orchestrator:orchestrator-tests
bazel test //modules/cli:cli-tests
bazel test //modules/verification-engine:verification-engine-tests
bazel test //modules/repair-api:repair-api-tests
bazel test //modules/repair-claude:repair-claude-tests
bazel test //modules/worker-protocol:worker-protocol-tests
bazel test //modules/artifact-store:artifact-store-tests
bazel test //modules/requirements:requirements-tests
bazel test //modules/selectors:selectors-tests
bazel test //modules/manifest:manifest-tests
bazel test //modules/local-api:local-api-tests
```

### Run TypeScript worker tests

```bash
cd worker
npm install
npm test
```

Worker tests use Jest with ts-jest. Three test suites: `rpc.spec.ts`, `browserflow.spec.ts`, `auth.spec.ts`.

### View test output

```bash
# Show all test output (not just failures)
bazel test //... --test_output=all

# Show streamed output during test execution
bazel test //... --test_output=streamed
```

## Module Dependency Graph

```
core-model (no deps)
  ├── persistence (core-model, sqlite-jdbc)
  ├── manifest (core-model, snakeyaml)
  ├── requirements (core-model, snakeyaml)
  ├── selectors (core-model, snakeyaml)
  ├── repo-inspector (core-model)
  ├── inference (core-model)
  ├── failure-analysis (core-model, inference)
  ├── environment-planner (core-model)
  ├── runtime-supervisor (core-model)
  ├── verification-engine (core-model)
  ├── repair-api (core-model)
  ├── repair-claude (core-model, repair-api)
  ├── artifact-store (core-model, persistence)
  ├── worker-protocol (core-model)
  ├── policy (core-model)
  ├── requirement-compiler (core-model, requirements, selectors)
  ├── local-api (core-model, persistence)
  └── orchestrator (core-model, persistence, repo-inspector, requirement-compiler,
  │                  environment-planner, runtime-supervisor, verification-engine,
  │                  repair-api, worker-protocol, artifact-store, inference,
  │                  failure-analysis)
  └── cli (core-model, persistence, orchestrator, local-api, manifest,
           repo-inspector, requirement-compiler, requirements, selectors,
           environment-planner, runtime-supervisor, verification-engine,
           repair-api, repair-claude, artifact-store, worker-protocol,
           inference, failure-analysis)
```

## Coding Conventions

### Scala

- **Scala 2.13.18** with Java 17 target
- **Enums** — sealed trait + case object pattern (Scala 2 style)
- **JSON** — circe semiauto derivation for all DTOs
- **Testing** — MUnit test framework
- **Package structure** — `lastmile.<module>` (e.g., `lastmile.model`, `lastmile.orchestrator`, `lastmile.persistence`)
- **State machine invariant** — persist-before-side-effects for all state transitions
- **No external HTTP library** — API server uses JDK built-in `com.sun.net.httpserver`

### TypeScript

- **ES2022** target with CommonJS modules
- **Strict mode** enabled
- **Playwright 1.42.1** for browser automation
- **Jest + ts-jest** for testing
- **stdio JSON-RPC 2.0** for inter-process communication

### Build System

- **Bazel 9.0+** with Bzlmod (`MODULE.bazel`)
- **rules_scala 7.2.4** for Scala compilation
- **rules_jvm_external 6.7** for Maven dependencies
- Each module has its own `BUILD.bazel` with `scala_library` and `scala_junit_test` targets
- All modules have `visibility = ["//visibility:public"]`

## Adding a New Module

1. Create directory: `modules/<name>/src/main/scala/lastmile/<name>/`
2. Create test directory: `modules/<name>/src/test/scala/lastmile/<name>/`
3. Create `modules/<name>/BUILD.bazel`:

```python
load("@rules_scala//scala:scala.bzl", "scala_junit_test", "scala_library")

scala_library(
    name = "<name>",
    srcs = glob(["src/main/scala/**/*.scala"], allow_empty = True),
    visibility = ["//visibility:public"],
    deps = [
        "//modules/core-model",
        # Add other deps as needed
    ],
)

scala_junit_test(
    name = "<name>-tests",
    srcs = glob(["src/test/scala/**/*.scala"], allow_empty = True),
    suffixes = ["Suite"],
    deps = [
        ":<name>",
        "//modules/core-model",
        "@maven//:org_scalameta_munit_2_13",
        "@maven//:org_scalameta_munit_diff_2_13",
    ],
)
```

4. Add the module as a dependency in any consuming modules' `BUILD.bazel` files.

## Maven Dependencies

All Maven dependencies are declared in `MODULE.bazel`:

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `circe-core`, `circe-generic`, `circe-parser` | 0.14.10 | JSON serialization |
| `shapeless` | 2.3.12 | Generic derivation (used by circe) |
| `cats-core`, `cats-kernel` | 2.12.0 | Functional abstractions |
| `sqlite-jdbc` | 3.45.3.0 | SQLite database driver |
| `munit`, `munit-diff` | 1.0.3 | Test framework |
| `snakeyaml` | 2.2 | YAML parsing |

To add a new Maven dependency, add it to the `maven.install` block in `MODULE.bazel`.

## Data Storage

During development, Demiurge creates the following under `<repo>/.lastmile/`:

- `lastmile.db` — SQLite database (WAL mode)
- `artifacts/<runId>/` — Run artifact directories
- `run.lock` — Active run lock file

These are created automatically and can be cleaned with `lastmile clean --all`.

## Test Fixtures

Two integration test fixtures are provided in `test/fixtures/`:

### `simple-node-http`

Minimal Node.js HTTP server with health check. Files:
- `server.js` — HTTP server on port 3456
- `lastmile.yaml` — manifest with script startup, static token auth, mock inference
- `requirements.yaml` — health check + root endpoint HTTP requirements
- `package.json` — minimal Node.js project

### `compose-app`

Full-stack Docker Compose application. Files:
- `docker-compose.yml` — frontend, API, PostgreSQL
- `lastmile.yaml` — manifest with compose startup, browser form login, Anthropic inference
- `requirements.yaml` — frontend, API health, DB reachability requirements

## Debugging

### View Bazel build logs

```bash
bazel build //... --verbose_failures
```

### Run a single test with output

```bash
bazel test //modules/orchestrator:orchestrator-tests --test_output=all --test_filter="*SignalHandler*"
```

### Check worker JSON-RPC communication

The worker communicates via newline-delimited JSON on stdin/stdout. To debug:

```bash
cd worker
echo '{"jsonrpc":"2.0","method":"ping","params":{},"id":1}' | node dist/index.js
```

### Inspect SQLite database

```bash
sqlite3 .lastmile/lastmile.db
.tables
SELECT * FROM task_runs ORDER BY created_at DESC LIMIT 5;
```
