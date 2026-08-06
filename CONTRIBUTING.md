# Contributing to Demiurge

Thanks for your interest in contributing. This guide covers repository setup,
testing, and the expectations for pull requests. For deeper build details and
module structure, see the [Development Guide](docs/development.md).

## Repository Setup

Prerequisites (checked by `demiurge doctor`):

- **Bazel 9.0+** (Bzlmod) — Java 17 is fetched automatically via `remotejdk_17`
- **Node.js >= 18** — for `worker/`, `desktop/`, and `web/`
- **Git >= 2.20** — for worktree isolation
- **Rust toolchain** — only for desktop (Tauri) development
- **Docker + Compose V2** — optional, for compose-based target services

```bash
git clone https://github.com/SteveVitali/Demiurge.git
cd Demiurge
bazel build //...          # Scala backend (48 targets)
cd worker && npm install   # TypeScript worker
```

## Testing

Run the suites relevant to your change before opening a PR:

| Component | Command | Run from |
|-----------|---------|----------|
| Scala backend | `bazel test //...` | repo root |
| Worker | `npm test` | `worker/` |
| Desktop | `npx tsc --noEmit` (the CI gate; there are no desktop unit tests) | `desktop/` |
| Web | `npm test` and `npm run build` (**not run in CI** — run locally) | `web/` |

To run a single Scala test class:

```bash
bazel test //modules/orchestrator:orchestrator-tests --test_output=all --test_filter="*SignalHandler*"
```

## Conventions

- **No linter or formatter configs exist** — match the style of surrounding
  code exactly.
- **Scala** — 2.13, sealed trait + case object enums, circe semiauto JSON
  codecs, MUnit tests. Test classes **must end in `Suite`** or Bazel silently
  skips them (`scala_junit_test` sets `suffixes = ["Suite"]`).
- **Maven dependencies** are declared only in `MODULE.bazel`
  (`maven.install` block), then referenced as `@maven//:...` labels in each
  module's `BUILD.bazel`.
- **Worker logging** — the worker's stdout is a JSON-RPC wire protocol; log
  via stderr only, never `console.log`.
- **Database migrations** are append-only: add a new
  `V00N__*.sql` under `modules/persistence/src/main/resources/migrations/`,
  never edit an applied one.
- **New Scala modules** — follow the `BUILD.bazel` template in
  [docs/development.md](docs/development.md#adding-a-new-module).

## Pull Requests

- Target the `main` branch. CI runs on PRs to `main`: Bazel build + tests,
  worker Jest tests (with Playwright Chromium), and a desktop TypeScript
  check (`npx tsc --noEmit` with strict flags).
- The `web/` vitest suite is **not** part of CI — run it locally when
  touching `web/`.
- Keep `docs/*.md` in sync when changing CLI flags, API endpoints, or the
  manifest schema — `docs/` is published to demiurge.dev at build time.

## License

Demiurge is licensed under the [Business Source License 1.1](LICENSE).
By contributing, you agree that your contributions will be licensed under
the same terms.
