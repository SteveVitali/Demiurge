# Demiurge Spec Review Findings

Systematic review of the implementation against `7_canonical_spec.md` and `design-auto-config-and-build-mode.md`.

**Codebase:** ~15,131 Scala LOC, ~1,470 TypeScript LOC, 17 modules, 44 build targets, 21 test targets.
**Last updated:** close-all-spec-gaps branch (spec gap fixes applied)

---

## Previously Reported Issues Now FIXED

The following items from prior reviews have been resolved:

| # | Prior Finding | Status | Evidence |
|---|---------------|--------|----------|
| 1 | Build mode not wired (was 🔴 Critical) | ✅ Fixed | `RunOrchestrator.execute()` now checks `runMode == RunMode.Build` and calls `BuildPhaseManager.planFeature()` / `generateCode()` |
| 2 | No layer-based verification (was 🔴 Critical) | ✅ Fixed | `VerificationEngine` iterates `plan.layers` (0–4), dispatches `parallelGroups` concurrently via thread pool, checks `VerificationPlanner.isBlocked()` |
| 3 | BootstrappingAuth never entered (was 🟡) | ✅ Fixed | Orchestrator transitions to `BootstrappingAuth` when `resolvedConfig.auth` is defined |
| 4 | Worker error codes misnamed/missing (was 🟡) | ✅ Fixed | Full spec §9.13 error code catalog now implemented: `-32010`–`-32016`, `-32020`–`-32021`, `-32030`–`-32031`, `-32040` |
| 5 | No flake detection / retry count (was 🟡) | ✅ Fixed | `VerificationEngine.executeOneVerifier()` tracks `retryCount`, detects flakes (pass-on-retry → `VerdictStatus.Flake`) |
| 6 | No degrade/recovery monitoring (was 🟡) | ✅ Fixed | `EnvironmentHealthMonitor.checkHealth()` with `Degraded`/`Failed`/`Healthy` states, `attemptRecovery()` called before each verification attempt |
| 7 | No network/console capture limits (was 🟡 Low) | ✅ Fixed | `MAX_NETWORK_REQUESTS=500`, `MAX_CONSOLE_ENTRIES=200`, `MAX_CONSOLE_CHAR_LENGTH=4096` in `executeBrowserFlow.ts` |
| 8 | Policy module empty (was 🟡 Low) | ✅ Fixed | `PolicyEnforcer` implements filesystem, network, browser, tool, and destructive action validation with tests |
| 9 | ClaudeRepairBackend bypasses InferenceService (was 🟡) | ✅ Mostly fixed | `InferenceBackedRepairBackend` is preferred when `InferenceService` is available (`OrchestrationRunner` line 68-69); `ClaudeRepairBackend` is fallback only when no API key for inference pipeline |
| 10 | No session/transcript in repair (was 🟡) | ✅ Fixed | `RepairSession.executeWithSession()` captures pre/post commit SHA, transcript entries, policy validation, elapsed time |
| 11 | VerdictAggregator ignores RequirementPriority (was 🔴 Critical) | ✅ Fixed | `VerdictAggregator.aggregateWithGraph()` implements spec §4.4: only `Required` priority blocks success. `Important`/`NiceToHave` failures do NOT block. |
| 12 | No requirement-level verdict aggregation (was 🟡) | ✅ Fixed | `VerdictAggregator.requirementVerdict()` aggregates multiple verifier verdicts per requirement per spec §4.3 |
| 13 | No stopOnFailure behavior (was 🟡) | ✅ Fixed | `VerificationEngine` tracks `stoppedRequirements` and skips remaining verifiers with `Blocked` status when `stopOnFailure=true` |
| 14 | No flakeCount in AggregateResult (was 🟡 Low) | ✅ Fixed | `AggregateResult` now includes `flakeCount` and `blockedCount` |
| 15 | ApiContractVerifierSpec field mismatch (was 🟡) | ✅ Fixed | Renamed `urlTemplate`→`path`, `bodyTemplate`→`requestBody`; added `serviceId`, `authMode`, `queryParams`, `expectedHeaders`, `sideEffectChecks` |
| 16 | StateAssertionVerifierSpec field mismatch (was 🟡) | ✅ Fixed | Renamed `queryType`→`source`, `connectionRef`→`serviceId`; added `bindVariables`, `readOnly`; removed `setupCommands`/`teardownCommands` |
| 17 | ConsoleLogVerifierSpec field divergence (was 🟡 Low) | ✅ Fixed | Renamed `targetUrl`→`url`, `allowedPatterns`→`requiredPatterns`, `captureLevel`/`maxErrors`→`severityThreshold` |
| 18 | EnvReadinessVerifierSpec field divergence (was 🟡 Low) | ✅ Fixed | Added `checkType`, `target`, `expectedValue` alongside existing `probeOverride`/`requiredLogPatterns` |

---

## 1. Enums (Spec §3.1) — ✅ Complete

All enums in `core-model/src/main/scala/demiurge/model/enums.scala` match the spec:

| Enum | Spec Count | Impl Count | Notes |
|------|-----------|------------|-------|
| RunStatus | 21 | 23 | +2 build mode states (PlanningFeature, GeneratingCode) per design doc |
| AttemptStatus | 10 | 10 | ✅ |
| ServiceStatus | 7 | 7 | ✅ |
| EnvironmentStatus | 8 | 8 | ✅ |
| RequirementPriority | 3 | 3 | ✅ |
| RequirementCategory | 8 | 8 | ✅ |
| VerdictStatus | 6 | 6 | ✅ |
| VerifierType | 9 | 9 | ✅ |
| FailureClass | 18 | 18 | ✅ |
| ServiceKind | 7 | 7 | ✅ |
| StartupMode | 4 | 4 | ✅ |
| AuthMode | 5 | 5 | ✅ |
| ArtifactType | 24 | 24 | ✅ |
| RunMode | 5 | 5 | +Build per design doc |
| ResetStrategy | 3 | 3 | ✅ |
| InferenceProvider | 4 | 4 | ✅ |
| DependencyEdgeType | 3 | 3 | ✅ |
| RepairResultStatus | 6 | 6 | ✅ |
| RepairBackendError | 9 | 9 | ✅ |
| InferenceError | 6 | 6 | ✅ |
| WorkerTaskStatus | 5 | 5 | ✅ |
| GenerationMode | 2 | 2 | Per design doc (Repair, InitialBuild) |

---

## 2. Core DTOs (Spec §3.2) — ✅ Complete

- **TaskRun, Attempt, AttemptVerdictSummary** — Match spec. Build mode fields present.
- **RequirementGraph, RequirementNode, DependencyEdge** — Match spec.
- **ExecutionBudget** — Match spec. BuildBudgetDefaults adds higher limits for build mode.
- **FailurePacket, SuspectedCause, RepairScope, ReproductionStep** — Match spec.
- **PatchProposal, FileEdit, NewFile, FileDeletion** — Match spec.
- **BrowserFlowVerifierSpec, BrowserAction, SelectorRef, Assertion, ArtifactCapture** — Match spec.
- **ApiContractVerifierSpec** — Match spec: `path`, `serviceId`, `authMode`, `queryParams`, `requestBody`, `expectedStatus`, `expectedHeaders`, `responseAssertions`, `sideEffectChecks`, `artifactPlan`.
- **StateAssertionVerifierSpec** — Match spec: `source`, `serviceId`, `query`, `bindVariables`, `assertions`, `readOnly`.
- **ConsoleLogVerifierSpec** — Match spec: `url`, `forbiddenPatterns`, `requiredPatterns`, `severityThreshold`.
- **EnvReadinessVerifierSpec** — Match spec: `serviceId`, `checkType`, `target`, `expectedValue`, `probeOverride`, `requiredLogPatterns`.
- **InferenceRequest, InferenceResponse** — Match spec §5.

---

## 3. Orchestrator State Machine (Spec §2.1) — ✅ Mostly Complete

- Build mode wired: `PlanningFeature` → `GeneratingCode` → `PlanningEnvironment` when `runMode == Build`.
- Full multi-attempt repair loop with `ReadyToVerify → Verifying → AnalyzingFailure → PlanningRepair → Repairing → SoftResettingEnvironment → ReadyToVerify`.
- Auth bootstrap enters `BootstrappingAuth` when auth config is present.
- Environment health monitoring before each verification attempt.
- Signal handler for `Interrupted` state persistence.
- Flake verdict correctly handled as success in control flow (spec §4.5).

### 3a. No RepairFailed → Retry Logic — 🟡 Medium

Spec §10.9 defines `maxRepairRetriesPerAttempt` — if repair fails, retry within the same attempt before advancing to the next attempt. The orchestrator treats repair failure as terminal for the attempt (transitions to `Exhausted`).

---

## 4. Verdict Aggregation (Spec §4.3–4.4) — ✅ Complete

- **§4.3 Requirement-level aggregation**: `VerdictAggregator.requirementVerdict()` computes a single verdict from all verifiers for a requirement (all Pass → Pass, mix Pass+Flake → Flake, any Fail → Fail).
- **§4.4 Priority-aware attempt-level**: `VerdictAggregator.aggregateWithGraph()` only uses `Required`-priority requirements to determine overall pass/fail. `Important` and `NiceToHave` failures do NOT block success.
- **§4.5 Flake propagation**: If any required requirement is Flake, overall verdict is Flake (still success for control flow).
- **stopOnFailure**: `VerificationEngine` tracks stopped requirements and skips remaining verifiers with `Blocked` status.
- **AggregateResult**: Includes `flakeCount` and `blockedCount` alongside existing counts.
- **Test coverage**: 20 tests covering legacy flat aggregation, requirement-level verdict, priority-aware aggregation, and edge cases.

---

## 5. Repair Backend (Spec §10) — ⚠️ Partially Implemented

### 5a. No Tool Taxonomy — 🟡 Medium

Spec §10.3 defines tools (`read_file`, `write_file`, `list_directory`, `search_files`, `run_command`) exposed to the LLM for interactive exploration. The implementation uses a simpler model: Claude generates a complete JSON patch in one shot. No tool-use loop, no file browsing, no command execution.

### 5b. ClaudeRepairBackend — Manual JSON Parsing — 🟡 Low

Uses hand-rolled regex-based JSON extraction (`extractString`, `extractStringArray`, `extractEdits`). Fragile for complex responses. No use of Claude Agent SDK as spec §10.11 mentions.

---

## 6. Worker Protocol (Spec §9) — ✅ Complete

- **JSON-RPC 2.0 over stdio** — Correct. Newline-delimited, UTF-8.
- **Methods**: `initialize`, `shutdown`, `cancel`, `executeBrowserFlow`, `executeAuthBootstrap`, `executeApiRequest`, `capturePageSnapshot`, `ping` — all registered.
- **Scala-side client**: Correct lifecycle, restart budget, crash detection.
- **Browser flow**: Actions, assertions, artifact capture, tracing, console/network capture with limits.
- **Selector resolution**: css, xpath, text, role, testId, label, placeholder strategies.
- **Error codes**: Full spec §9.13 catalog — `NAVIGATION_FAILED` (-32010), `SELECTOR_NOT_FOUND` (-32011), `ACTION_TIMEOUT` (-32012), `ASSERTION_FAILED` (-32013), `BROWSER_CRASHED` (-32014), `POLICY_VIOLATION` (-32015), `STORAGE_STATE_INVALID` (-32016), `REQUEST_FAILED` (-32020), `REQUEST_TIMEOUT` (-32021), `AUTH_FAILED` (-32030), `AUTH_TIMEOUT` (-32031), `CAPTURE_FAILED` (-32040).

---

## 7. Persistence Layer (Spec §7) — ✅ Complete

- **V001__initial.sql**: All required tables present.
- **V002__build_mode.sql**: `feature_plans` table and `generation_mode` column.
- **Database.scala**: Correct PRAGMA settings (WAL, foreign_keys, synchronous=NORMAL, busy_timeout=5000).
- **Migrator.scala**: Version tracking, transactional migration application.
- **Repos**: TaskRunRepo, AttemptRepo, VerdictRepo, PatchRepo, ArtifactRecordRepo, EventRepo, etc.

---

## 8. Resume Logic (Spec §7.6) — ✅ Well Implemented

- **ResumeManager**: Handles orphan cleanup, worktree verification, resume state determination.
- **ResumeDataLoader**: Loads persisted inspection reports, requirement graphs, runtime plans, patch history.
- **RunOrchestrator**: `shouldExecute` gates phases based on `resumeFromStatus`.
- **Non-resumable states** correctly handled.

---

## 9. CLI Commands (Spec §15) — ✅ Complete

All commands implemented: `run`, `resume`, `status`, `cancel`, `clean`, `doctor`, `plan`, `inspect-run`, `open-artifact`, `explain-failure`, `init-manifest`, `build`.

**Exit codes** match spec §14.3: Success=0, Exhausted=1, Cancelled=2, Errored=3, InputError=4, ConcurrentRunConflict=5, ResumeFailed=10.

---

## 10. Inference Service (Spec §5) — ✅ Well Implemented

- **InferenceService trait**: `infer`, `remainingBudget`, `getUsage` per spec.
- **InferenceServiceImpl**: Budget check → cache check → replay mode → backend call → retry → usage recording → cache store.
- **AnthropicInferenceBackend**: Real API backend with proper rate limit handling.
- **InferenceBudgetTracker**: Allowed caller validation.
- **InferenceCache**: SHA-256 cache keying per spec §5.7.

---

## 11. Local API Server (Spec §15.4) — ✅ Well Implemented

- Binds to `127.0.0.1:19440`.
- All endpoints per spec.
- **ApiEnvelope**: JSON envelope with status/error fields.
- **EventStream**: In-memory pub/sub with SSE formatting.
- Pagination support on artifacts endpoint.

---

## 12. Artifact Store (Spec §14) — ✅ Well Implemented

- **ArtifactSink**: Temp-file-then-rename, SHA-256 checksums, gzip compression (>1MB), disk budget enforcement.
- **EvidenceCollector**: Registers worker artifacts, writes verdict/failure/report artifacts, assembles prompt packages.
- **ArtifactPaths**: Correct path layout.
- Essential artifact types bypass budget check.

---

## 13. Environment Planner & Runtime Supervisor (Spec §13) — ✅ Mostly Complete

- **EnvironmentPlannerImpl**: Plans from manifest or inspection. Topological sort with cycle detection.
- **RuntimeSupervisorImpl**: Dependency-ordered startup, readiness checks, fixture execution, snapshot capture.
- **EnvironmentHealthMonitor**: Degraded/Failed/Healthy detection, recovery attempts.
- **ReadinessChecker**: HTTP, TCP, exec, log_contains probes.

### 13a. No Observability Taps — 🟡 Low

Config schema supports `observabilityTaps` but nothing reads service logs during verification. RuntimePlan always has `observabilityTaps = Nil`.

---

## 14. Policy Enforcement (Spec §6) — ✅ Implemented

- **PolicyEnforcer**: Filesystem write/delete validation, network egress validation, browser origin validation, tool usage validation, destructive action validation.
- **PolicySnapshot construction**: `PolicyEnforcer.defaultPolicySnapshot()` from budget and paths.
- **Integration with RepairSession**: Patch file paths validated against filesystem policy before application.

---

## 15. Config Resolver (Design Doc Phase A) — ✅ Implemented

- Layered resolution: explicit YAML → cached inference → live inference.
- `InferredConfigWriter` for persisting inferred config.
- `init` command with `--smart` flag for LLM augmentation.

---

## 16. Cross-Cutting Concerns

### 16a. No CI/CD — 🟡 Medium

No GitHub Actions or CI config of any kind.

### 16b. No End-to-End Integration Tests — 🟡 Medium

All test suites are unit-level per module. No integration test that exercises the full orchestration pipeline.

---

## Summary: Remaining Issues

| # | Severity | Area | Issue |
|---|----------|------|-------|
| 1 | 🟡 Medium | Repair §10.3 | No tool taxonomy — LLM generates JSON patch directly, no interactive file/command tools |
| 2 | 🟡 Medium | Orchestrator §10.9 | No RepairFailed retry within attempt (`maxRepairRetriesPerAttempt`) |
| 3 | 🟡 Medium | Infra | No CI/CD, no integration tests |
| 4 | 🟡 Low | Repair §10.11 | ClaudeRepairBackend uses manual JSON parsing (no Agent SDK) |
| 5 | 🟡 Low | Environment §13 | No observability taps |
