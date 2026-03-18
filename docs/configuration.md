# Configuration

Demiurge is configured through YAML files in your repository root. The primary configuration file is `demiurge.yaml` (the manifest), with optional `requirements.yaml` and `selectors.yaml` files.

Generate configuration files with `demiurge init` (deterministic scaffold) or `demiurge init --smart` (agentic generation via Claude Code CLI).

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `ANTHROPIC_API_KEY` | For repair/inference | Claude API key for LLM-powered failure analysis and repair |

## `demiurge.yaml` — Manifest

The manifest describes your application, its services, authentication, verification settings, inference configuration, policies, and observability.

### `version`

```yaml
version: 1
```

Required. Must be `1`.

### `app`

Top-level application metadata.

```yaml
app:
  type: fullstack          # api | frontend | fullstack
  root_url: http://localhost:3000
  api_url: http://localhost:4000   # optional, for fullstack/api apps
```

| Field | Required | Description |
|-------|----------|-------------|
| `type` | Yes | Application type: `api`, `frontend`, or `fullstack` |
| `root_url` | Yes | Primary application URL |
| `api_url` | No | API base URL (if separate from root) |

### `services`

Defines the services that make up your application environment.

```yaml
services:
  frontend:
    kind: frontend
    startup_mode: compose
    compose_target: frontend
    ports:
      - host: 3000
        container: 3000
    depends_on:
      - api
    readiness:
      probe_type: http
      target: http://localhost:3000/
      interval_ms: 2000
      timeout_ms: 5000
      max_failures: 15
    required: true

  api:
    kind: api
    startup_mode: script
    startup_command: npm start
    ports:
      - host: 4000
        container: 4000
    readiness:
      probe_type: http
      target: http://localhost:4000/health
    required: true

  db:
    kind: db
    startup_mode: compose
    compose_target: postgres
    ports:
      - host: 5432
        container: 5432
    readiness:
      probe_type: tcp
      target: localhost:5432
    required: true
```

#### Service fields

| Field | Required | Description |
|-------|----------|-------------|
| `kind` | Yes | Service kind: `frontend`, `api`, `db`, `cache`, `queue`, `worker`, `external_mock` |
| `startup_mode` | Yes | How to start: `script` (native process), `compose` (Docker Compose target) |
| `startup_command` | For `script` | Shell command to start the service |
| `compose_target` | For `compose` | Docker Compose service name |
| `ports` | No | Port mappings (`host` and `container`) |
| `depends_on` | No | List of service IDs this service depends on (boot order) |
| `readiness` | No | Readiness probe configuration |
| `required` | No | Whether this service must be healthy for the environment to be ready (default: `false`) |

#### Readiness probe fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `probe_type` | Yes | | `http` or `tcp` |
| `target` | Yes | | Probe target URL (http) or `host:port` (tcp) |
| `interval_ms` | No | `1000` | Probe interval in milliseconds |
| `timeout_ms` | No | `3000` | Individual probe timeout |
| `max_failures` | No | `10` | Max consecutive failures before declaring unhealthy |

### `fixtures`

Fixture seeding configuration for database migrations, test data, etc.

```yaml
fixtures:
  reset_strategy: soft       # soft | hard | full_rebuild
  seed_steps:
    - step_id: migrate
      command: npm run migrate
      cwd: api
      timeout_ms: 30000
      run_on_init_only: true
    - step_id: seed
      command: npm run seed
      cwd: api
      timeout_ms: 30000
      run_on_reset: true
```

| Field | Required | Description |
|-------|----------|-------------|
| `reset_strategy` | No | Strategy for environment resets between attempts: `soft`, `hard`, `full_rebuild` |
| `seed_steps` | No | Ordered list of fixture steps |

#### Seed step fields

| Field | Required | Description |
|-------|----------|-------------|
| `step_id` | Yes | Unique step identifier |
| `command` | Yes | Shell command to execute |
| `cwd` | No | Working directory relative to repo root |
| `timeout_ms` | No | Step timeout in milliseconds |
| `run_on_init_only` | No | Only run during initial environment boot |
| `run_on_reset` | No | Run during environment resets between attempts |

### `auth`

Authentication bootstrap configuration.

```yaml
# Browser form login
auth:
  mode: browser_form_login
  login_url: http://localhost:3000/login
  credentials:
    username: admin@test.com
    password: testpass123
  storage_state_output: auth-state.json
```

```yaml
# Static test token
auth:
  mode: static_test_token
  static_token: test-token-123
```

| Field | Required | Description |
|-------|----------|-------------|
| `mode` | Yes | Auth mode: `browser_form_login`, `api_login`, `static_test_token`, `seeded_local_session`, `dev_bypass_header` |
| `login_url` | For `browser_form_login` | URL of the login page |
| `credentials` | For login modes | `username` and `password` |
| `static_token` | For `static_test_token` | Token value |
| `storage_state_output` | No | File to save Playwright storage state |

### `verification`

Verification engine settings.

```yaml
verification:
  default_verifier_timeout_ms: 60000
  default_browser_action_timeout_ms: 15000
  max_retries: 2
  retry_delay_ms: 1000
  screenshot_on_failure: true
  screenshot_on_complete: true
  trace_enabled: true
```

| Field | Default | Description |
|-------|---------|-------------|
| `default_verifier_timeout_ms` | `30000` | Default timeout per verifier |
| `default_browser_action_timeout_ms` | `15000` | Default timeout for browser actions |
| `max_retries` | `1` | Maximum retries per verifier |
| `retry_delay_ms` | `1000` | Delay between retries |
| `screenshot_on_failure` | `false` | Capture screenshot when a browser verifier fails |
| `screenshot_on_complete` | `false` | Capture screenshot after every browser verifier |
| `trace_enabled` | `false` | Enable Playwright trace capture |

### `inference`

LLM inference configuration.

```yaml
inference:
  default_provider: anthropic    # anthropic | openai | local | mock
  models:
    requirement_compiler: claude-sonnet-4-20250514
    failure_analyzer: claude-sonnet-4-20250514
```

| Field | Default | Description |
|-------|---------|-------------|
| `default_provider` | `mock` | Default inference provider |
| `models` | | Per-component model overrides |

### `policies`

Execution policy limits and safety constraints.

```yaml
policies:
  max_attempts: 5
  run_timeout_ms: 3600000
  attempt_timeout_ms: 900000
  max_patch_lines: 2000
  max_artifact_disk_bytes: 536870912
  allowed_hosts:
    - localhost
    - 127.0.0.1
  browser_allowed_origins:
    - "http://localhost:*"
  allow_git_push: false
  allow_db_drop: false
```

| Field | Default | Description |
|-------|---------|-------------|
| `max_attempts` | `5` | Maximum verification/repair attempts |
| `run_timeout_ms` | `3600000` (1h) | Overall run timeout |
| `attempt_timeout_ms` | `900000` (15m) | Per-attempt timeout |
| `max_patch_lines` | `2000` | Maximum lines in a repair patch |
| `max_artifact_disk_bytes` | `536870912` (512MB) | Maximum artifact disk usage |
| `allowed_hosts` | `[localhost, 127.0.0.1]` | Hosts the worker is allowed to connect to |
| `browser_allowed_origins` | `[http://localhost:*]` | Origins the browser is allowed to navigate to |
| `allow_git_push` | `false` | Whether repair is allowed to push to remote |
| `allow_db_drop` | `false` | Whether repair is allowed to drop databases |

### `observability`

Observability hooks for log tailing and custom queries.

```yaml
observability:
  log_queries:
    - id: api_errors
      service_id: api
      query: "grep -c ERROR"
      description: Count API errors
  taps:
    - tap_id: api-log
      service_id: api
      tap_type: log_tail
```

| Field | Description |
|-------|-------------|
| `log_queries` | Named log queries to run against service logs |
| `taps` | Log tailing taps attached to services |

## `requirements.yaml`

Defines verification requirements for your application.

```yaml
requirements:
  - id: health-check
    type: http
    description: Health endpoint returns 200 OK
    expected: http://localhost:3456/health
    timeout_ms: 5000
    retry: 2
    severity: required

  - id: db-reachable
    type: tcp
    description: Database is reachable
    expected: localhost:5432
    timeout_ms: 10000
    severity: required
```

### Requirement fields

| Field | Required | Description |
|-------|----------|-------------|
| `id` | Yes | Unique requirement identifier |
| `type` | Yes | Verifier type: `http`, `tcp`, `exec`, `log`, `state`, `browser_flow` |
| `description` | Yes | Human-readable description |
| `expected` | Yes | Expected outcome (URL for http, host:port for tcp, command for exec, etc.) |
| `timeout_ms` | No | Verification timeout |
| `retry` | No | Number of retries |
| `severity` | No | `required` (must pass), `important`, or `nice_to_have` |

### Requirement types

- **`http`** — HTTP GET request, expects 2xx status. `expected` is the URL.
- **`tcp`** — TCP connection check. `expected` is `host:port`.
- **`exec`** — Shell command execution. `expected` is the command. Passes if exit code is 0.
- **`log`** — Log content check. Searches for a pattern in service logs.
- **`state`** — State assertion (custom logic).
- **`browser_flow`** — Playwright browser flow with actions and assertions (requires `browserFlowSpec` in the requirement graph).

## `selectors.yaml`

Defines CSS/XPath selectors used by browser flow verifiers, with strategy metadata.

```yaml
selectors:
  - id: login-button
    value: "[data-testid='login-btn']"
    strategy: test-id
    description: Login form submit button

  - id: email-input
    value: "input[name='email']"
    strategy: css
    description: Email input field
```

### Selector fields

| Field | Required | Description |
|-------|----------|-------------|
| `id` | Yes | Unique selector identifier |
| `value` | Yes | CSS selector, XPath, or test-id value |
| `strategy` | Yes | Selector strategy: `css`, `xpath`, `test-id` |
| `description` | No | Human-readable description |

## File Locations

| File | Location | Purpose |
|------|----------|---------|
| `demiurge.yaml` | Repo root | Main manifest |
| `requirements.yaml` | Repo root | Verification requirements |
| `selectors.yaml` | Repo root | Browser selectors |
| `.demiurge/inferred/demiurge.yaml` | Repo root | Cached inferred config (auto-created by ConfigResolver) |
| `.demiurge/demiurge.db` | Repo root | SQLite database (auto-created) |
| `.demiurge/artifacts/<runId>/` | Repo root | Run artifacts (auto-created) |
| `.demiurge/run.lock` | Repo root | Active run lock file (auto-created) |

## Config Resolution Order

When Demiurge runs, `ConfigResolver` loads configuration using a layered approach:

1. **Explicit YAML** — `demiurge.yaml` in the repo root (highest priority)
2. **Cached Inference** — `.demiurge/inferred/demiurge.yaml` (previously generated config)
3. **Error** — if neither is found, Demiurge exits with an error directing the user to run `demiurge init --smart`

Runtime heuristic inference has been removed. All configuration must come from explicit YAML files. Use `demiurge init --smart` to generate these files for a new project.

## Example: Simple Node.js API

```yaml
# demiurge.yaml
version: 1

app:
  type: api
  root_url: http://localhost:3456

services:
  node-api:
    kind: api
    startup_mode: script
    startup_command: node server.js
    ports:
      - host: 3456
        container: 3456
    readiness:
      probe_type: http
      target: http://localhost:3456/health
    required: true

auth:
  mode: static_test_token
  static_token: test-token-123

inference:
  default_provider: mock

policies:
  max_attempts: 3
  run_timeout_ms: 60000
```

## Example: Full-Stack Compose App

```yaml
# demiurge.yaml
version: 1

app:
  type: fullstack
  root_url: http://localhost:3000
  api_url: http://localhost:4000

services:
  frontend:
    kind: frontend
    startup_mode: compose
    compose_target: frontend
    ports:
      - host: 3000
        container: 3000
    readiness:
      probe_type: http
      target: http://localhost:3000/
    required: true

  api:
    kind: api
    startup_mode: compose
    compose_target: api
    ports:
      - host: 4000
        container: 4000
    depends_on:
      - db
    readiness:
      probe_type: http
      target: http://localhost:4000/health
    required: true

  db:
    kind: db
    startup_mode: compose
    compose_target: postgres
    ports:
      - host: 5432
        container: 5432
    readiness:
      probe_type: tcp
      target: localhost:5432
    required: true

fixtures:
  reset_strategy: soft
  seed_steps:
    - step_id: migrate
      command: npm run migrate
      cwd: api
      timeout_ms: 30000
      run_on_init_only: true
    - step_id: seed
      command: npm run seed
      cwd: api
      timeout_ms: 30000
      run_on_reset: true

auth:
  mode: browser_form_login
  login_url: http://localhost:3000/login
  credentials:
    username: admin@test.com
    password: testpass123
  storage_state_output: auth-state.json

verification:
  default_verifier_timeout_ms: 60000
  max_retries: 2
  screenshot_on_failure: true
  trace_enabled: true

inference:
  default_provider: anthropic
  models:
    requirement_compiler: claude-sonnet-4-20250514
    failure_analyzer: claude-sonnet-4-20250514

policies:
  max_attempts: 5
  run_timeout_ms: 3600000
  attempt_timeout_ms: 900000
```
