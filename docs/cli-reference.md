# CLI Reference

The `demiurge` CLI is the primary interface for interacting with Demiurge.

```
demiurge [global-flags] <command> [command-flags]
```

## Global Flags

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | Current directory | Repository path |
| `--format <mode>` | `human` | Output format: `human` or `json` |
| `--verbose` | `false` | Verbose output |
| `--quiet` | `false` | Suppress non-essential output |
| `--config <path>` | None | Configuration file path |
| `--help`, `-h` | | Show help |

## Commands

### `run`

Execute a full verification run.

```
demiurge run --task <description> [flags]
```

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--task <text>` | Yes | | Task description |
| `--max-attempts <n>` | No | 5 | Maximum verification/repair attempts |
| `--run-timeout <duration>` | No | From budget | Overall run timeout |
| `--attempt-timeout <duration>` | No | From budget | Per-attempt timeout |
| `--verifier-timeout <duration>` | No | From budget | Per-verifier timeout |
| `--repair-timeout <duration>` | No | From budget | Repair step timeout |
| `--inference-timeout <duration>` | No | From budget | LLM inference timeout |
| `--max-patch-lines <n>` | No | From budget | Maximum lines in a repair patch |
| `--max-artifact-disk <size>` | No | From budget | Maximum artifact disk usage |
| `--max-repair-tokens <n>` | No | From budget | Maximum LLM tokens for repair |
| `--max-exploratory-steps <n>` | No | From budget | Maximum exploratory repair steps |
| `--changed-files <list>` | No | None | Comma-separated list of changed files |
| `--git-ref <ref>` | No | HEAD | Git ref to check out in worktree |
| `--mode <mode>` | No | `Full` | Run mode: `Full`, `PlanOnly`, `VerifyOnly`, `RepairOnly` |
| `--run-id <id>` | No | Auto-generated | Custom run ID |
| `--replay-inference` | No | `false` | Replay cached inference (no live API calls) |
| `--headless` | No | `true` | Run browser in headless mode |
| `--no-headless` | No | | Run browser with visible UI |

**Duration format:** Plain number (seconds), or suffixed: `30s`, `5m`, `1h`, `500ms`.

**Size format:** Plain number (bytes), or suffixed: `100KB`, `50MB`, `1GB`.

**What happens:**
1. Checks for concurrent active run (exits with code 5 if conflict)
2. Creates isolated git worktree
3. Acquires file lock
4. Persists `TaskRun` to SQLite
5. Starts local API server on `127.0.0.1:19440`
6. Runs orchestration pipeline (inspect → compile → plan → boot → verify → repair if needed)
7. Writes final report artifact
8. Cleans up (worker shutdown, lock release)

### `plan`

Plan without executing — runs inspection and requirement compilation only.

```
demiurge plan --task <description> [flags]
```

| Flag | Required | Description |
|------|----------|-------------|
| `--task <text>` | Yes | Task description |
| `--changed-files <list>` | No | Comma-separated changed files |
| `--git-ref <ref>` | No | Git ref for inspection |

### `resume`

Resume an interrupted run.

```
demiurge resume --run-id <id>
```

| Flag | Required | Description |
|------|----------|-------------|
| `--run-id <id>` | Yes | ID of the run to resume |

**Resumable statuses:** `Interrupted`, `ReadyToVerify`, `AnalyzingFailure`, `PlanningRepair`.

Uses `ResumeManager` to determine the optimal resume point based on the last persisted state transition. Phases that completed successfully before interruption are skipped — persisted inspection reports, requirement graphs, runtime plans, and patch history are loaded from the database. The attempt counter continues from where it left off. The existing worktree is reused.

### `status`

Show run status or list recent runs.

```
demiurge status [--run-id <id>]
```

| Flag | Required | Description |
|------|----------|-------------|
| `--run-id <id>` | No | Specific run ID (omit to list recent runs) |

Without `--run-id`, lists the 20 most recent runs.

### `inspect-run`

Detailed run inspection.

```
demiurge inspect-run --run-id <id> [flags]
```

| Flag | Required | Description |
|------|----------|-------------|
| `--run-id <id>` | Yes | Run ID |
| `--attempt <n>` | No | Filter to specific attempt number |
| `--show-verdicts` | No | Include verdict details |
| `--show-artifacts` | No | Include artifact listing |

### `open-artifact`

Access run artifacts.

```
demiurge open-artifact --run-id <id> [flags]
```

| Flag | Required | Description |
|------|----------|-------------|
| `--run-id <id>` | Yes | Run ID |
| `--artifact-id <id>` | No | Specific artifact ID |
| `--type <type>` | No | Filter by artifact type |
| `--attempt <n>` | No | Filter by attempt number |
| `--print-path` | No | Print file path instead of content |

### `explain-failure`

Explain verification failures for a run.

```
demiurge explain-failure --run-id <id> [flags]
```

| Flag | Required | Description |
|------|----------|-------------|
| `--run-id <id>` | Yes | Run ID |
| `--attempt <n>` | No | Specific attempt number |

Outputs a structured failure explanation including failure class, message, and observations for each failed requirement.

### `cancel`

Cancel an active run.

```
demiurge cancel [--run-id <id>]
```

| Flag | Required | Description |
|------|----------|-------------|
| `--run-id <id>` | No | Run ID to cancel |

### `clean`

Clean up old runs and artifacts.

```
demiurge clean [flags]
```

| Flag | Required | Description |
|------|----------|-------------|
| `--run-id <id>` | No* | Specific run to clean |
| `--all` | No* | Clean all runs |
| `--max-age <duration>` | No* | Clean runs older than duration |
| `--include-artifacts` | No | Also delete artifact files |
| `--include-db` | No | Also delete database records |
| `--dry-run` | No | Show what would be cleaned without doing it |

*At least one of `--run-id`, `--all`, or `--max-age` is required.

### `doctor`

Check system prerequisites.

```
demiurge doctor
```

Checks:
- **Node.js** >= 18 (required)
- **Package manager** — npm, yarn, or pnpm (required)
- **Docker** (optional, for compose services)
- **Docker Compose V2** (optional)
- **Playwright browsers** (optional, for browser flows)
- **Git** >= 2.20 (required)
- **Disk space** >= 1 GB (required)
- **ANTHROPIC_API_KEY** env var (optional, for repair)
- **Port 19440** availability (optional)

Exit code 1 if any required check fails.

### `init`

Generate `demiurge.yaml` and `requirements.yaml` configuration files.

```
demiurge init [flags]
```

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--output <path>` | No | `demiurge.yaml` | Output file path |
| `--force` | No | `false` | Overwrite existing files |
| `--smart` | No | `false` | Use Claude Code CLI for agentic config generation |

**Without `--smart`:** Performs deterministic repo inspection (no LLM) to detect app type (frontend, api, fullstack), services, ports, database dependencies, and Docker Compose presence. Generates scaffold YAML files with TODO markers for manual refinement.

**With `--smart`:** Launches an agentic session via the TypeScript worker and Claude Code CLI. The agent inspects the repository, understands its structure, and generates complete `demiurge.yaml` and `requirements.yaml` files tailored to the project. Requires `DEMIURGE_WORKER_PATH` environment variable or the Demiurge worker to be installed.

If existing config is found (explicit or cached), the deterministic path loads and displays it instead of regenerating. Use `--force` to regenerate.

## Exit Codes

### Run-lifecycle commands (`run`, `resume`)

| Code | Meaning |
|------|---------|
| 0 | Success — all verifiers passed |
| 1 | Exhausted — verification failed after all attempts |
| 2 | Cancelled — run was cancelled or interrupted |
| 3 | Errored — unexpected error during execution |
| 4 | Input error — invalid arguments or missing required flags |
| 5 | Concurrent run conflict — another run is active for this repo |
| 10 | Resume failed — run is not in a resumable state |

### Non-run commands (`status`, `inspect-run`, `doctor`, etc.)

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Command-specific failure (e.g., doctor found required check failures) |
| 4 | Not found or input error |

## Output Formats

### Human (default)

Human-readable text output. Example:

```
Run: abc-123
Status: Succeeded
Task: Verify login flow
Mode: Full
Attempts: 1/5
```

### JSON (`--format json`)

Machine-readable JSON output. Example:

```json
{"runId":"abc-123","status":"Succeeded","taskText":"Verify login flow","runMode":"Full","attemptCount":1,"maxAttempts":5}
```

Error responses in JSON mode:

```json
{"ok":false,"error":"Run not found: abc-123"}
```
