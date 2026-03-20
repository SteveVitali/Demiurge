# API Reference

Demiurge runs a local HTTP API server during active runs. The server binds to `127.0.0.1:19440` — localhost only, no authentication required.

The server starts automatically with `demiurge run` and stops when the run completes.

All endpoints support CORS (Cross-Origin Resource Sharing) to allow requests from the desktop application.

## Response Envelope

All JSON responses use a standard envelope:

**Success:**
```json
{
  "ok": true,
  "data": { ... }
}
```

**Error:**
```json
{
  "ok": false,
  "error": {
    "code": 404,
    "message": "Run not found: abc-123"
  }
}
```

## Endpoints

### `GET /health`

Health check.

**Response:**
```json
{
  "ok": true,
  "data": {
    "status": "ok"
  }
}
```

### `GET /runs`

List all runs with pagination, sorting, and optional status filtering.

**Query parameters:**

| Parameter | Default | Description |
|-----------|---------|-------------|
| `offset` | `0` | Pagination offset |
| `limit` | `20` | Page size |
| `sort` | `created_at` | Sort field |
| `order` | `desc` | Sort order: `asc` or `desc` |
| `status` | None | Filter by run status (e.g., `Succeeded`, `Verifying`) |

**Response:**
```json
{
  "ok": true,
  "data": {
    "items": [ ... ],
    "total": 42,
    "offset": 0,
    "limit": 20
  }
}
```

### `GET /runs/active`

Get the currently active run (if any).

**Response (success):**
```json
{
  "ok": true,
  "data": {
    "runId": "abc-123",
    "status": "Verifying",
    "taskText": "Verify the login flow works",
    ...
  }
}
```

**Errors:** `404` if no active run.

### `POST /runs`

Start a new verification run.

**Request body:**
```json
{
  "task": "Verify the login flow works"
}
```

**Response:**
```json
{
  "ok": true,
  "data": {
    "runId": "abc-123",
    "task": "Verify the login flow works",
    "status": "started"
  }
}
```

If no run-starter callback is wired (e.g., during plan-only mode), returns `"status": "accepted"` without a `runId`.

### `GET /runs/{runId}`

Get run details.

**Response:**
```json
{
  "ok": true,
  "data": {
    "runId": "abc-123",
    "status": "Verifying",
    "taskText": "Verify the login flow works",
    "runMode": "Full",
    "repoPath": "/path/to/repo",
    "createdAt": "2026-03-13T22:00:00Z",
    "startedAt": "2026-03-13T22:00:01Z",
    "attemptCount": 1,
    "maxAttempts": 5
  }
}
```

**Errors:** `404` if run not found.

### `GET /runs/{runId}/plan`

Get plan artifacts for a run.

**Response:**
```json
{
  "ok": true,
  "data": [
    {
      "artifactId": "art-001",
      "artifactType": "Plan",
      "runId": "abc-123",
      "relativePath": "plan.json",
      "sizeBytes": 1234
    }
  ]
}
```

**Errors:** `404` if run not found.

### `GET /runs/{runId}/attempts`

List verification attempts for a run.

**Response:**
```json
{
  "ok": true,
  "data": [
    {
      "attemptId": "att-001",
      "runId": "abc-123",
      "attemptNumber": 1,
      "status": "VerificationPassed",
      "verdictSummary": {
        "passCount": 5,
        "failCount": 0,
        "totalRequired": 5
      }
    }
  ]
}
```

**Errors:** `404` if run not found.

### `GET /runs/{runId}/attempts/{attemptNumber}/verdicts`

Get verdicts for a specific attempt.

**Response:**
```json
{
  "ok": true,
  "data": [
    {
      "verdictId": "v-001",
      "runId": "abc-123",
      "attemptNumber": 1,
      "requirementId": "health-check",
      "status": "Pass",
      "executionDurationMs": 150,
      "confidence": 1.0
    }
  ]
}
```

**Errors:** `400` if attempt number is invalid.

### `GET /runs/{runId}/artifacts`

List artifacts for a run. Supports pagination and filtering.

**Query parameters:**

| Parameter | Default | Description |
|-----------|---------|-------------|
| `offset` | `0` | Pagination offset |
| `limit` | `50` | Page size |
| `type` | None | Filter by artifact type (e.g., `Screenshot`, `FinalReport`) |
| `attempt` | None | Filter by attempt number |

**Response:**
```json
{
  "ok": true,
  "data": {
    "items": [ ... ],
    "total": 12,
    "offset": 0,
    "limit": 50
  }
}
```

**Errors:** `404` if run not found.

### `GET /runs/{runId}/artifacts/{artifactId}/content`

Download artifact file content.

Returns the raw file content with the appropriate `Content-Type` header (as recorded when the artifact was written).

**Errors:**
- `404` if artifact not found, artifact file missing on disk, or artifact root cannot be resolved

### `POST /runs/{runId}/resume`

Resume an interrupted run.

**Resumable statuses:** `Interrupted`, `ReadyToVerify`, `AnalyzingFailure`, `PlanningRepair`.

**Response (success):**
```json
{
  "ok": true,
  "data": {
    "runId": "abc-123",
    "status": "resuming"
  }
}
```

**Errors:**
- `404` if run not found
- `409` if run is not in a resumable status

### `POST /runs/{runId}/cancel`

Cancel an active run.

**Response (success):**
```json
{
  "ok": true,
  "data": {
    "runId": "abc-123",
    "status": "cancelled"
  }
}
```

**Errors:**
- `404` if run not found
- `409` if run is already in a terminal state (`Succeeded`, `Exhausted`, `Cancelled`, `Interrupted`)

### `GET /runs/{runId}/events` (SSE)

Server-Sent Events stream for real-time run events.

**Headers:**
```
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

**Event format:**
```
data: {"eventId":"evt-001","runId":"abc-123","eventType":"state_transition","component":"orchestrator","severity":"info","timestamp":"2026-03-13T22:00:01Z","humanMessage":"Run abc-123 transitioned from Created to InspectingRepo"}

```

Each event is a JSON-serialized `SystemEvent` object. The stream remains open until the run ends or the client disconnects.

**Terminal event:**
```
event: done
data: {}

```

## Artifact Types

The following artifact types may appear in artifact listings:

| Type | Description |
|------|-------------|
| `Plan` | Runtime plan artifact |
| `ServiceLog` | Service stdout/stderr logs |
| `Screenshot` | Browser screenshot (PNG) |
| `DomSnapshot` | Page DOM snapshot |
| `AccessibilitySnapshot` | Accessibility tree snapshot |
| `ConsoleLog` | Browser console output |
| `NetworkSummary` | Network request summary |
| `BrowserTrace` | Playwright trace file |
| `PatchDiff` | Repair patch diff |
| `StructuredVerdict` | Verification verdict JSON |
| `FailurePacketArtifact` | Failure analysis packet |
| `FinalReport` | Run completion report |
| `InferenceLog` | LLM inference audit log |
| `RepoInspectionArtifact` | Repository inspection report |
| `PromptPackage` | Assembled prompt package for LLM |
| `AttemptReport` | Per-attempt summary report |

## Desktop API Extensions

The following endpoints were added to support the desktop application. They follow the same JSON envelope convention.

### `GET /runs`

Paginated list of all runs.

**Query parameters:**

| Parameter | Default | Description |
|-----------|---------|-------------|
| `offset` | `0` | Pagination offset |
| `limit` | `20` | Page size |
| `sort` | `created_at` | Sort field |
| `order` | `desc` | Sort order: `asc` or `desc` |
| `status` | None | Filter by run status |

### `GET /runs/active`

Get the currently active run (if any). Returns `404` if no run is active.

### `GET /runs/{runId}/environment`

Get the environment snapshot for a run, including all service statuses.

### `GET /runs/{runId}/services`

List service snapshots for a run (service ID, status, PID, container ID, log line count, startup mode).

### `POST /runs/{runId}/services/{serviceId}/restart`

Trigger a best-effort restart of a specific service.

### `GET /runs/{runId}/agent/transcript`

Get the agent transcript for the current or most recent agent session. Returns an array of transcript message objects.

### `GET /runs/{runId}/agent/cost`

Get agent cost/usage data (input tokens, output tokens, cost USD, number of turns, duration).

### `GET /runs/{runId}/inspection`

Get the repository inspection report for a run.

### `GET /runs/{runId}/requirement-graph`

Get the requirement graph (nodes and edges) for a run.

### `GET /runs/{runId}/feature-plan`

Get the feature plan for a build-mode run. Returns `404` if no plan exists.

### `GET /runs/{runId}/attempts/{attemptNumber}/failure-packet`

Get the failure analysis packet for a specific attempt.

### `GET /runs/{runId}/attempts/{attemptNumber}/patches`

List repair patches applied during a specific attempt.

### `GET /config?repo=<path>`

Get configuration files (manifest + requirements YAML) for a repository path, with provenance metadata.

### `PUT /config/manifest`

Write/update `demiurge.yaml`. **Request body:** `{ "repoPath": "<path>", "yaml": "<content>" }`

### `PUT /config/requirements`

Write/update `requirements.yaml`. **Request body:** `{ "repoPath": "<path>", "yaml": "<content>" }`

### `POST /config/validate`

Validate manifest and/or requirements YAML. **Request body:** `{ "manifest?": "<yaml>", "requirements?": "<yaml>" }`. Returns `{ "valid": bool, "errors": [...], "warnings": [...] }`.

### `POST /config/init-smart`

Trigger agent-based smart init. Returns `202 Accepted` immediately; progress is streamed via WebSocket. **Request body:** `{ "repoPath": "<path>", "taskHint?": "<text>" }`

### `GET /system/doctor`

Run prerequisite checks (git, node, docker, API key, SQLite) and return results.

### `GET /system/preferences`

Get stored user preferences (theme, font size, log line limit, default repo path, etc.).

### `PUT /system/preferences`

Update user preferences. **Request body:** JSON object with preference key/value pairs.

### `GET /system/repos`

List known repository paths from the task_runs table (most recent 20).

## WebSocket Server

In `serve` mode (desktop sidecar), a WebSocket server runs alongside the HTTP API (default port `19441`). It provides:

- **Real-time event streaming** — same `SystemEvent` objects as SSE, broadcast to all connected clients
- **Agent transcript streaming** — live agent progress, tool calls, and cost updates during active sessions
- **Service log streaming** — live log lines from managed services via `LogStreamManager`

Clients subscribe by connecting to `ws://127.0.0.1:19441`. Messages are JSON-encoded.

## Event Types

Events streamed via SSE have these fields:

| Field | Type | Description |
|-------|------|-------------|
| `eventId` | string | Unique event ID |
| `runId` | string | Associated run ID |
| `attemptNumber` | int? | Attempt number (if applicable) |
| `eventType` | string | Event type (e.g., `state_transition`) |
| `component` | string | Source component (e.g., `orchestrator`) |
| `severity` | string | `info`, `warn`, `error` |
| `timestamp` | ISO 8601 | Event timestamp |
| `humanMessage` | string | Human-readable event description |
| `payload` | object | Structured event payload |
