# AGENTS.md — worker/ (browser worker)

## Purpose

Node process spawned by the Scala backend (via `WorkerProcessManager`, path from
`DEMIURGE_WORKER_PATH`). Speaks newline-delimited JSON-RPC 2.0 on stdin/stdout and
executes Playwright browser flows, API requests, auth bootstrap, and Claude Agent
SDK sessions (repair, init, agentic browser verification).

## Key Files

| File | Lines | Purpose |
|---|---|---|
| `src/index.ts` | ~50 | Entry point; registers every RPC method |
| `src/rpc/server.ts` | ~150 | JSON-RPC 2.0 server over stdin/stdout |
| `src/methods/executeBrowserFlow.ts` | ~450 | Scripted browser-flow verifier execution |
| `src/methods/agentExecute.ts` | ~400 | Claude Agent SDK `query()` sessions + verdict parsing |
| `src/methods/executeAuthBootstrap.ts` | ~250 | Form-login / token auth bootstrap |
| `src/methods/demiurgeMcpTools.ts` | ~250 | In-process MCP server: verify/restart/logs tools that call back to Scala |
| `src/artifacts/browserArtifactCollector.ts` | ~200 | Screenshots, a11y trees, console/network logs, verdict JSON |
| `src/artifacts/writer.ts` | ~100 | Artifact file writer (checksummed) |
| `src/methods/executeApiRequest.ts` | ~100 | HTTP verifier execution |
| `src/browser/manager.ts` | ~100 | Chromium lifecycle (Playwright) |

## RPC Methods

`initialize`, `shutdown`, `cancel`, `executeBrowserFlow`, `executeAuthBootstrap`,
`executeApiRequest`, `capturePageSnapshot`, `agent/execute`, `ping` — all registered
in `src/index.ts`. Renaming one breaks the Scala client in
`modules/worker-protocol/` silently at runtime; change both sides together.

## Build & Test

```bash
npm install
npm run build          # tsc → dist/
npm run lint           # tsc --noEmit
npm test               # jest --forceExit --detectOpenHandles
npx jest test/rpc.spec.ts        # single suite
npx playwright install chromium  # needed once before browser tests (CI: --with-deps)
```

Four Jest suites live in `test/`: `rpc.spec.ts`, `browserflow.spec.ts`,
`auth.spec.ts`, `browserVerification.spec.ts`. A standalone E2E script lives at
`../test/e2e-browser-verification.mjs` (repo root `test/`, not here).

## Code Conventions

- CommonJS output, ES2022 target, `strict: true` (`tsconfig.json`).
- `test/` is excluded from `tsconfig.json` — tests compile via ts-jest only, so
  `npm run build` passing does not mean tests type-check; run `npm test`.
- MCP tools in `demiurgeMcpTools.ts` use the SDK's `createSdkMcpServer()` /
  `tool(name, description, zodRawShape, handler)` signature; handlers return
  `CallToolResult` objects, not strings.

## Critical Gotchas

1. **stdout is the protocol channel.** Any `console.log` corrupts JSON-RPC framing
   and desyncs the Scala client. Use `process.stderr.write` (grep `src/` — there are
   zero `console.log` calls; keep it that way).
2. **`playwright` is pinned to exactly `1.42.1`** (no `^`). Bumping it changes the
   browser binary contract with installed Chromium versions.
3. **`@anthropic-ai/claude-code` must stay `^1.x`** — the 2.x npm package ships no
   SDK exports (`sdk.mjs` removed). `agentExecute.ts` accepts
   `pathToClaudeCodeExecutable` from Scala to use an external CLI binary.
4. **Jest needs `--forceExit`** (already in `npm test`) — Playwright/SDK sessions
   leave open handles. If a test hangs locally, you likely spawned a browser or
   session without closing it in `afterEach`.
5. **Build output is gitignored** — the Scala side runs the compiled entry point
   produced by `npm run build`; rebuild after editing `src/` or your changes won't
   be picked up by runs.

## Source Layout

```
worker/
├── src/
│   ├── index.ts          # entry + method registry
│   ├── rpc/              # server.ts, types.ts
│   ├── browser/          # manager.ts (Chromium lifecycle)
│   ├── methods/          # one file per RPC method + MCP tools (9 files)
│   ├── artifacts/        # writer.ts, browserArtifactCollector.ts
│   └── utils/            # checksum.ts
├── test/                 # 4 Jest suites (*.spec.ts)
├── jest.config.js        # ts-jest, roots=test/, 30s timeout
└── tsconfig.json         # strict, CommonJS, excludes test/
```
