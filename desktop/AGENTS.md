# AGENTS.md — desktop/ (Tauri GUI)

## Purpose

Tauri 2 desktop app (React 19 + Vite 6 frontend, Rust core) with full CLI parity.
It spawns the Scala backend as a fat-JAR sidecar and talks to it over the local API
(REST/SSE on `127.0.0.1:19440`, WebSocket on `127.0.0.1:19441` — see
`src/lib/constants.ts`).

## Key Files

| File | Lines | Purpose |
|---|---|---|
| `src-tauri/src/sidecar.rs` | ~350 | Spawns/monitors/stops the backend sidecar process |
| `src-tauri/src/tray.rs` | ~250 | System tray menu, synced to run state |
| `src-tauri/src/commands.rs` | ~100 | Tauri `invoke` commands exposed to the frontend |
| `src/api/types.ts` | ~450 | TS mirror of every backend DTO — keep in sync with `modules/core-model` |
| `src/api/endpoints.ts` | ~200 | All REST calls (TanStack Query fetchers) |
| `src/api/websocket.ts` | ~200 | WS client with reconnect/heartbeat |
| `src/screens/SettingsScreen.tsx` | ~450 | Largest screen; settings + API key persistence |
| `src/lib/constants.ts` | ~20 | API/WS URLs, poll intervals, page sizes |
| `src-tauri/tauri.conf.json` | — | Window, tray, updater endpoint, `externalBin` sidecar |
| `scripts/package-sidecar.sh` | — | Builds `demiurge_deploy.jar` via Bazel, writes launcher into `src-tauri/binaries/` |

## Build & Test

```bash
npm install
npx tsc --noEmit                # THE CI gate — no unit tests exist
npm run tauri dev               # Vite on localhost:1420 + Tauri window (needs Rust toolchain)
npm run dev                     # frontend only, no Tauri shell
bash scripts/package-sidecar.sh && npm run tauri build   # production bundle
```

## Code Conventions

- **State**: zustand stores in `src/stores/*.store.ts` (6 stores: app, run, agent,
  auth, logs, preferences); server state via TanStack Query
  (`src/lib/query-client.ts`, keys in `src/lib/query-keys.ts`).
- **Screens vs components**: 7 top-level screens in `src/screens/`; reusable pieces
  grouped by domain in `src/components/<domain>/` (15 domain dirs).
- **Styling**: Tailwind CSS 4 via `@tailwindcss/vite`; class merging with
  `clsx` + `tailwind-merge` (`src/lib/utils.ts`).
- **Backend access**: never fetch ad hoc — add the endpoint to `src/api/endpoints.ts`
  and its DTO to `src/api/types.ts`.

## Critical Gotchas

1. **Strictest tsconfig in the repo**: `noUnusedLocals`, `noUnusedParameters`,
   `noUncheckedIndexedAccess`, `noFallthroughCasesInSwitch`. An unused import or
   `arr[i]` without an undefined-check fails the CI gate (`npx tsc --noEmit`).
2. **`src-tauri/binaries/` is gitignored** — `npm run tauri build` fails or ships a
   broken app unless `scripts/package-sidecar.sh` ran first (it names the launcher
   with the Tauri target triple, e.g. `demiurge-sidecar-aarch64-apple-darwin`).
3. **In dev mode the sidecar placeholder is skipped** — the app uses a Bazel wrapper
   instead of a packaged JAR (see `sidecar.rs`); don't assume dev == release spawn path.
4. **`src/api/types.ts` drifts silently** — the backend's circe DTOs are the source
   of truth; a Scala field rename won't fail any desktop build until runtime.
5. **Updater/signing config in `tauri.conf.json` is release-critical** — the
   `plugins.updater` endpoint and pubkey pair with `TAURI_SIGNING_PRIVATE_KEY`
   secrets in `release.yml`. Ask before touching.
6. **Deep links use the `demiurge://` scheme** (`plugin-deep-link`) for the
   web→desktop auth callback; changing it breaks login from demiurge.dev.

## Source Layout

```
desktop/
├── src/
│   ├── main.tsx              # entry
│   ├── api/                  # client, endpoints, sse, websocket, types (5 files)
│   ├── screens/              # 7 screens (Dashboard, RunDetail, Config, Settings, Auth, AuthCallback, DetachedLog)
│   ├── components/           # 15 domain dirs + PlanTierBadge.tsx
│   ├── stores/               # 6 zustand stores (*.store.ts)
│   ├── hooks/                # 10 hooks (SSE, WS, transcript, notifications, …)
│   └── lib/                  # constants, query-client, query-keys, routes, utils
├── src-tauri/
│   ├── src/                  # main.rs, lib.rs, commands.rs, sidecar.rs, tray.rs
│   └── tauri.conf.json       # window/tray/updater/externalBin config
└── scripts/package-sidecar.sh
```
