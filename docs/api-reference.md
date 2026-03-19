# API Reference

Demiurge runs a local HTTP API server during active runs. The server binds to `127.0.0.1:19440` — localhost only, no authentication required.

The server starts automatically with `demiurge run` and stops when the run completes.

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
