# Spec 05: Usage Metering & Credits

> **Parent document:** [plan-go-to-market.md](./plan-go-to-market.md)
> **Phase:** 5 of 5 — Depends on Specs 01 (cloud backend) and 02 (license integration).
> **Estimated effort:** 3–4 days.

---

## 1. Overview

This spec defines how Demiurge tracks usage (runs and agent tokens), enforces plan limits, reports usage to the cloud backend, and displays usage information to the user. After this spec:

- Every `demiurge run` / `demiurge build` increments the run counter on the Keygen license
- Agent token consumption is tracked locally and reported to the cloud
- Users see clear usage feedback in both CLI and desktop app
- Users hitting limits see actionable upgrade prompts
- The foundation exists for future "managed credits" (optional API key proxy)

---

## 2. Metering Model

### 2.1 What We Meter

| Metric | Unit | Where Tracked | Enforcement |
|--------|------|---------------|-------------|
| **Runs** | Count of `run` / `build` commands that reach `BootstrappingEnvironment` | Keygen license `uses` field | Hard limit — run blocked if `uses >= maxUses` |
| **Agent tokens** | Sum of input + output tokens consumed by the agent backend during a run | Local SQLite (per-run) + reported to cloud | Soft limit — warning at 80%, logged at 100%, not blocked |

### 2.2 What We Do NOT Meter (Yet)

- Individual LLM API calls (BYOK — the user's API provider meters this)
- Disk usage for artifacts
- Number of services booted
- Duration of runs

### 2.3 Billing Period

- Keygen license `uses` is reset to 0 on each subscription renewal (via Stripe `invoice.paid` webhook → Keygen `resetLicenseUsage`, defined in Spec 01 §8.2)
- Agent token tracking resets at the same time (the cloud backend stores the reset timestamp)

---

## 3. Run Counting

### 3.1 When to Increment

A run is counted when it **successfully transitions past `Created` state**. Specifically, increment the counter when `RunOrchestrator` transitions to `InspectingRepo` (the first real work state). This ensures:

- Runs that fail due to input validation (bad YAML, missing files) are NOT counted
- Runs that are immediately cancelled before doing work are NOT counted
- Resumed runs are NOT counted again (they were already counted on initial start)

### 3.2 Implementation: RunOrchestrator Change

**File:** `modules/orchestrator/src/main/scala/demiurge/orchestrator/RunOrchestrator.scala`

Add a usage increment call at the point where the run transitions to `InspectingRepo`:

```scala
// In RunOrchestrator.execute(), after the initial Created → InspectingRepo transition:

// Increment license usage (run count)
if (!isResume) {
  val licenseKey = CredentialStore.loadCredentials().map(_.licenseKey).getOrElse("")
  val fingerprint = CredentialStore.getMachineFingerprint()
  UsageReporter.incrementRunCount(licenseKey, fingerprint) match {
    case Left(UsageLimitExceeded(uses, maxUses)) =>
      structuredLogger.logError(s"Run limit reached: $uses/$maxUses runs used this period")
      transitionManager.transition(run.id, RunStatus.Errored, conn)
      return run.id // Abort the run
    case Left(UsageReportError(msg)) =>
      // Non-fatal: log warning but continue (offline grace)
      structuredLogger.logWarning(s"Could not report usage: $msg")
    case Right(UsageReport(uses, maxUses)) =>
      structuredLogger.logInfo(s"Run $uses/$maxUses this period")
  }
}
```

### 3.3 UsageReporter Module

Add to the `modules/license` module:

**File:** `modules/license/src/main/scala/demiurge/license/UsageReporter.scala`

```scala
package demiurge.license

sealed trait UsageResult
case class UsageReport(uses: Int, maxUses: Int) extends UsageResult
case class UsageLimitExceeded(uses: Int, maxUses: Int) extends UsageResult
case class UsageReportError(message: String) extends UsageResult

object UsageReporter {

  /**
   * Increment run count via the cloud backend.
   * The cloud backend proxies to Keygen's increment-usage endpoint.
   * Returns the updated usage count.
   */
  def incrementRunCount(licenseKey: String, fingerprint: String): Either[UsageResult, UsageReport] = {
    if (licenseKey.isEmpty) return Left(UsageReportError("No license key"))

    try {
      val baseUrl = CredentialStore.loadConfig().cloudApiUrl.getOrElse("https://demiurge.dev")
      val url = new java.net.URL(s"$baseUrl/api/license/increment-usage")
      val conn = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setRequestProperty("X-License-Key", licenseKey)
      conn.setRequestProperty("X-Machine-Fingerprint", fingerprint)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setDoOutput(true)
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(10000)

      // Body: increment by 1
      val payload = """{"increment": 1}"""
      conn.getOutputStream.write(payload.getBytes("UTF-8"))
      conn.getOutputStream.close()

      val status = conn.getResponseCode
      val body = readBody(conn)

      status match {
        case 200 =>
          // Parse response: { "uses": 42, "maxUses": 200 }
          import io.circe.parser.decode
          case class IncrementResponse(uses: Int, maxUses: Int)
          implicit val decoder: io.circe.Decoder[IncrementResponse] =
            io.circe.generic.semiauto.deriveDecoder
          decode[IncrementResponse](body) match {
            case Right(r) => Right(UsageReport(r.uses, r.maxUses))
            case Left(e)  => Left(UsageReportError(s"Parse error: ${e.getMessage}"))
          }

        case 422 =>
          // maxUses exceeded
          import io.circe.parser.decode
          case class ErrorResponse(uses: Int, maxUses: Int)
          implicit val decoder: io.circe.Decoder[ErrorResponse] =
            io.circe.generic.semiauto.deriveDecoder
          decode[ErrorResponse](body) match {
            case Right(r) => Left(UsageLimitExceeded(r.uses, r.maxUses))
            case Left(_)  => Left(UsageLimitExceeded(-1, -1))
          }

        case _ =>
          Left(UsageReportError(s"HTTP $status: $body"))
      }
    } catch {
      case e: Exception => Left(UsageReportError(e.getMessage))
    }
  }

  /**
   * Report token consumption for a completed run.
   * This is fire-and-forget (best effort). Tokens are tracked locally in SQLite
   * and periodically synced to the cloud.
   */
  def reportTokenUsage(licenseKey: String, runId: String, inputTokens: Long, outputTokens: Long): Unit = {
    if (licenseKey.isEmpty) return

    try {
      val baseUrl = CredentialStore.loadConfig().cloudApiUrl.getOrElse("https://demiurge.dev")
      val url = new java.net.URL(s"$baseUrl/api/license/report-tokens")
      val conn = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setRequestProperty("X-License-Key", licenseKey)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setDoOutput(true)
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(5000)

      val payload = s"""{"run_id":"$runId","input_tokens":$inputTokens,"output_tokens":$outputTokens}"""
      conn.getOutputStream.write(payload.getBytes("UTF-8"))
      conn.getOutputStream.close()

      conn.getResponseCode // fire-and-forget, ignore response
    } catch {
      case _: Exception => // Silently ignore — best effort
    }
  }

  private def readBody(conn: java.net.HttpURLConnection): String = {
    val stream = if (conn.getResponseCode >= 400) conn.getErrorStream else conn.getInputStream
    if (stream == null) return ""
    val reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, "UTF-8"))
    try {
      val sb = new StringBuilder
      var line = reader.readLine()
      while (line != null) { sb.append(line); line = reader.readLine() }
      sb.toString
    } finally reader.close()
  }
}
```

---

## 4. Cloud Backend API Routes (Additions to Spec 01)

### 4.1 POST /api/license/increment-usage

**Purpose:** Increment the run counter on a Keygen license. Proxies to Keygen's `increment-usage` action.

**Authentication:** License key in `X-License-Key` header.

**Request:**
```json
POST /api/license/increment-usage
X-License-Key: DEMI-XXXX-XXXX-XXXX
X-Machine-Fingerprint: sha256_hex

{ "increment": 1 }
```

**Implementation:**
```typescript
export async function POST(req: Request) {
  const licenseKey = req.headers.get('X-License-Key');
  if (!licenseKey) return Response.json({ error: 'Missing license key' }, { status: 401 });

  const { increment = 1 } = await req.json();

  // Look up license ID by key
  const validation = await keygen.validateLicenseKey(licenseKey);
  if (!validation.valid) {
    return Response.json({ error: 'Invalid license', code: validation.code }, { status: 403 });
  }

  // Increment usage
  try {
    const result = await keygen.incrementUsage(validation.licenseId, increment);
    return Response.json({
      uses: result.uses,
      maxUses: result.maxUses,
    });
  } catch (err: any) {
    // Keygen returns 422 when maxUses exceeded
    if (err.status === 422) {
      return Response.json({
        error: 'Usage limit exceeded',
        uses: err.uses ?? -1,
        maxUses: err.maxUses ?? -1,
      }, { status: 422 });
    }
    throw err;
  }
}
```

### 4.2 POST /api/license/report-tokens

**Purpose:** Accept token usage reports from the CLI/desktop app. Stored for analytics and future billing.

**Authentication:** License key in `X-License-Key` header.

**Request:**
```json
POST /api/license/report-tokens
X-License-Key: DEMI-XXXX-XXXX-XXXX

{
  "run_id": "uuid",
  "input_tokens": 50000,
  "output_tokens": 12000
}
```

**Implementation:**
For MVP, store in a simple Supabase table or log to a file. This data is not used for billing enforcement in v1 — it's for analytics and future usage-based pricing.

```typescript
export async function POST(req: Request) {
  const licenseKey = req.headers.get('X-License-Key');
  if (!licenseKey) return Response.json({ error: 'Missing license key' }, { status: 401 });

  const body = await req.json();

  // Validate license key is real (lightweight check)
  // For MVP: just log it
  console.log(`[token-report] license=${licenseKey.substring(0, 8)}... run=${body.run_id} in=${body.input_tokens} out=${body.output_tokens}`);

  // Future: Insert into Supabase
  // await supabase.from('token_usage').insert({
  //   license_key_prefix: licenseKey.substring(0, 8),
  //   run_id: body.run_id,
  //   input_tokens: body.input_tokens,
  //   output_tokens: body.output_tokens,
  //   reported_at: new Date().toISOString(),
  // });

  return Response.json({ ok: true });
}
```

### 4.3 GET /api/user/usage

**Purpose:** Return the current user's usage for the current billing period.

**Authentication:** Bearer token (Clerk JWT).

**Response:**
```json
{
  "runs": {
    "used": 42,
    "limit": 200,
    "period_start": "2026-03-01T00:00:00Z",
    "period_end": "2026-04-01T00:00:00Z"
  },
  "tokens": {
    "used": 1250000,
    "limit": 2000000,
    "period_start": "2026-03-01T00:00:00Z",
    "period_end": "2026-04-01T00:00:00Z"
  }
}
```

**Implementation:** Reads `uses` and `maxUses` from Keygen license validation. Token usage comes from the Supabase table (future) or is estimated from Keygen metadata.

---

## 5. Token Tracking in the Orchestrator

### 5.1 Where Tokens Are Already Tracked

The existing `InferenceService` module already tracks token usage per run via `UsageTracker`. After a run completes, the total input/output tokens are available.

### 5.2 Report After Run Completion

**File:** `modules/orchestrator/src/main/scala/demiurge/orchestrator/RunOrchestrator.scala`

At the end of `execute()`, after the run reaches a terminal state (Succeeded, Exhausted, Cancelled, Errored), report token usage:

```scala
// After run reaches terminal state:
val totalTokens = inferenceService.getUsageForRun(run.id)
if (totalTokens.totalInputTokens > 0 || totalTokens.totalOutputTokens > 0) {
  val licenseKey = CredentialStore.loadCredentials().map(_.licenseKey).getOrElse("")
  UsageReporter.reportTokenUsage(
    licenseKey,
    run.id.toString,
    totalTokens.totalInputTokens,
    totalTokens.totalOutputTokens
  )
}
```

### 5.3 Token Usage in Run Summary

Enhance the run completion summary to include token usage:

```
Run completed: Succeeded
  Verifiers: 5/5 passed after 1 repair(s)
  Duration: 2m 34s
  Tokens: 125,000 input + 32,000 output = 157,000 total
  Usage: 43/200 runs this period
```

---

## 6. CLI User Experience

### 6.1 Pre-Run Usage Check

Before starting a run, after license validation succeeds, show a one-line usage summary:

```
[demiurge] License valid (Pro plan). Usage: 42/200 runs this period.
```

### 6.2 Limit Reached Error

When the user hits their run limit:

```
Error: Run limit reached (200/200 runs used this period).

Your Pro plan includes 200 runs per month. Your period resets on Apr 1, 2026.

To continue:
  • Upgrade your plan: https://demiurge.dev/pricing
  • Wait for your period to reset

Run 'demiurge status' to check your current usage.
```

### 6.3 Approaching Limit Warning

When usage is ≥80% of the limit, show a yellow warning:

```
[demiurge] Warning: 165/200 runs used this period (82%). Period resets Apr 1.
```

### 6.4 `demiurge status` Enhancement

Enhance the `status` command to show license and usage info:

```
Demiurge v0.2.0
═══════════════════════════════

Account
  Email:     user@example.com
  Plan:      Pro ($79/mo)
  Expires:   Apr 1, 2026

Usage (this period: Mar 1 – Apr 1)
  Runs:      42 / 200  ████████░░░░░░░░░░░░ 21%
  Tokens:    1.25M / 2M ████████████░░░░░░░░ 62%

API Keys
  Anthropic: sk-a...1234 ✓
  OpenAI:    (not set)

Recent Runs
  ...
```

The progress bar is rendered as Unicode block characters in the terminal.

---

## 7. Desktop App User Experience

### 7.1 Usage Display in Sidebar

Add a small usage indicator in the app sidebar (below the navigation):

```
┌─────────────────┐
│  📊 Usage       │
│  Runs: 42/200   │
│  ████████░░ 21% │
│                  │
│  [Manage Plan →] │
└─────────────────┘
```

### 7.2 Account Page Enhancement

The Account page (from Spec 04) should display detailed usage:

**UsageCard component:**
- Two progress bars: Runs and Tokens
- Color coding: green (<60%), yellow (60-80%), red (>80%)
- Period dates: "Mar 1 – Apr 1, 2026"
- "View history" link (future)

### 7.3 Upgrade Prompt Modal

When a run fails due to usage limits, show a modal in the desktop app:

```
┌──────────────────────────────────────┐
│  ⚠️  Run Limit Reached              │
│                                      │
│  You've used all 50 runs included    │
│  in your Starter plan this month.    │
│                                      │
│  Upgrade to Pro for 200 runs/month.  │
│                                      │
│  [Upgrade to Pro]  [Dismiss]         │
└──────────────────────────────────────┘
```

The "Upgrade to Pro" button opens the Stripe billing portal or pricing page in the browser.

### 7.4 Usage Fetching

**New hook:** `desktop/src/hooks/useUsage.ts`

```typescript
import { useQuery } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/auth.store';

interface UsageData {
  runs: { used: number; limit: number; periodEnd: string };
  tokens: { used: number; limit: number; periodEnd: string };
}

export function useUsage() {
  const { licenseKey } = useAuthStore();

  return useQuery<UsageData>({
    queryKey: ['usage', licenseKey],
    queryFn: async () => {
      // Fetch from the sidecar backend which proxies to cloud API
      const res = await fetch(`http://127.0.0.1:19440/usage`, {
        headers: { 'X-License-Key': licenseKey ?? '' },
      });
      if (!res.ok) throw new Error('Failed to fetch usage');
      return res.json();
    },
    enabled: !!licenseKey,
    refetchInterval: 60_000, // Refresh every minute
    staleTime: 30_000,
  });
}
```

### 7.5 Sidecar /usage Endpoint

Add a `/usage` endpoint to the Scala local API server that proxies usage data from the cloud backend (or returns cached data). This avoids the desktop frontend needing to call the cloud API directly.

**File:** `modules/local-api/src/main/scala/demiurge/api/UsageHandler.scala`

```scala
// Handler for GET /usage
// 1. Load license key from request header or CredentialStore
// 2. Call CloudApiClient to get usage data (or return cached)
// 3. Return JSON response
```

---

## 8. Offline Behavior

When the user is offline:

### 8.1 Run Counting

- The `incrementRunCount` call will fail with a network error
- The run proceeds anyway (offline grace period from Spec 02)
- A local counter in `~/.demiurge/offline-usage.json` tracks unsynced runs:
  ```json
  { "unsynced_runs": 3, "last_sync": "2026-03-19T15:30:00Z" }
  ```
- On next successful `incrementRunCount` call, sync the offline count:
  ```scala
  def syncOfflineUsage(licenseKey: String, fingerprint: String): Unit = {
    val offlinePath = demiurgeDir.resolve("offline-usage.json")
    // Load unsynced_runs count
    // Call incrementRunCount with increment = unsynced_runs
    // Reset to 0 on success
  }
  ```

### 8.2 Token Reporting

- Token reports are fire-and-forget. Failed reports are silently dropped.
- Future improvement: queue failed reports in a local file and retry on next run.

---

## 9. Changes to Existing Files

| File | Change |
|------|--------|
| `modules/orchestrator/.../RunOrchestrator.scala` | Add `UsageReporter.incrementRunCount()` after Created → InspectingRepo transition; add `UsageReporter.reportTokenUsage()` at run completion |
| `modules/cli/.../CliApp.scala` | Show usage info in pre-run license check output |
| `modules/cli/.../StatusCommand.scala` | Add license/usage section to `status` output |
| `modules/cli/BUILD.bazel` | Already depends on `//modules/license` from Spec 02 |
| `modules/license/.../UsageReporter.scala` | New file |
| `modules/license/.../CredentialStore.scala` | Add offline usage tracking methods |
| `modules/local-api/.../LocalApiServer.scala` | Add `/usage` endpoint handler |
| `desktop/src/hooks/useUsage.ts` | New file |
| `desktop/src/stores/auth.store.ts` | No change (already has planTier) |
| `desktop/src/components/layout/AppLayout.tsx` | Add usage indicator in sidebar |
| `desktop/src/screens/SettingsScreen.tsx` | Add usage display to account section |

### New Cloud Backend Routes (additions to Spec 01 `web/`)

| Route | File | Purpose |
|-------|------|---------|
| `POST /api/license/increment-usage` | `web/src/app/api/license/increment-usage/route.ts` | Increment run counter |
| `POST /api/license/report-tokens` | `web/src/app/api/license/report-tokens/route.ts` | Accept token usage reports |
| `GET /api/user/usage` | `web/src/app/api/user/usage/route.ts` | Get current period usage |

---

## 10. Testing Plan

### 10.1 Unit Tests (Scala)

**File:** `modules/license/src/test/scala/demiurge/license/UsageReporterSpec.scala`

- Mock HTTP responses for `incrementRunCount`:
  - 200 → returns UsageReport with correct uses/maxUses
  - 422 → returns UsageLimitExceeded
  - Network error → returns UsageReportError
- Mock `reportTokenUsage` — verify fire-and-forget (no exception on failure)
- Test offline usage sync logic

### 10.2 Integration Tests

- Start a run with a Keygen test license that has `maxUses: 2`
- First run → succeeds, uses = 1
- Second run → succeeds, uses = 2
- Third run → blocked with "Run limit reached" error
- Reset usage → next run succeeds

### 10.3 CLI UX Tests

- Run with 80%+ usage → verify yellow warning is printed
- Run with 100% usage → verify error message with upgrade URL
- `demiurge status` → verify usage section is displayed with progress bar

### 10.4 Desktop App Tests

- Open app → verify usage indicator appears in sidebar
- Start a run → verify usage counter increments after run completes
- Trigger limit exceeded → verify modal appears with upgrade CTA

---

## 11. Future: Managed Credits System

This section describes the future "managed credits" feature mentioned in `plan-go-to-market.md`. It is **NOT part of this spec** but is documented here for architectural awareness.

### How It Would Work

1. Users who don't want to manage their own API key can purchase "Demiurge Credits"
2. Credits are purchased via Stripe (one-time or recurring addon)
3. Demiurge holds a centralized Anthropic API key (with a formal reseller agreement)
4. When a user with managed credits runs Demiurge, LLM calls use Demiurge's key
5. Token consumption depletes the user's credit balance
6. Credits are priced at ~30% markup over raw API cost

### Why Not Now

- Requires formal Anthropic partnership/reseller agreement
- Adds billing complexity (tracking per-user token spend, credit balances)
- BYOK covers 90% of users and has zero ToS risk
- Can be added later as an upsell without changing the core architecture

### Architectural Preparation

The current design prepares for this by:
- Separating API key resolution (`CredentialStore.loadConfig()` vs `ANTHROPIC_API_KEY` env var)
- Tracking token usage per run (this spec)
- Having a cloud backend that can serve as a proxy endpoint (future route: `POST /api/inference/proxy`)

---

## 12. Out of Scope

- Managed credits / API key proxy (future — see §11)
- Per-verifier usage breakdown
- Usage history / time-series charts
- Usage alerts via email
- Team-level usage aggregation
- Overage billing (charging for runs beyond the plan limit)
- Token-level hard enforcement (only soft warning for v1)
