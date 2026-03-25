# Demiurge Spec Review Findings

Systematic review of the implementation against `7_canonical_spec.md` and `design-auto-config-and-build-mode.md`.

**Codebase:** ~15,131 Scala LOC, ~1,470 TypeScript LOC, 17 modules, 44 build targets, 21 test targets.
**Last updated:** Full §1–§16 audit (comprehensive cross-reference)

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
| VerifierType | 9 | 10 | +AgentBrowser for agentic browser UI verification |
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

## 3. Orchestrator State Machine (Spec §2.1) — ✅ Complete

- Build mode wired: `PlanningFeature` → `GeneratingCode` → `PlanningEnvironment` when `runMode == Build`.
- Full multi-attempt repair loop with `ReadyToVerify → Verifying → AnalyzingFailure → PlanningRepair → Repairing → SoftResettingEnvironment → ReadyToVerify`.
- Auth bootstrap enters `BootstrappingAuth` when auth config is present.
- Environment health monitoring before each verification attempt.
- Signal handler for `Interrupted` state persistence.
- Flake verdict correctly handled as success in control flow (spec §4.5).
- **§10.9 RepairFailed retry**: Repair retries within attempt (up to `maxRepairRetriesPerAttempt=2`), transitions through `RepairFailed` state before re-entering `AnalyzingFailure → PlanningRepair → Repairing`.
- **§2.1 EnvironmentFailed retry**: Boot retries up to `maxEnvBootRetries=2`, transitions through `EnvironmentFailed` state before re-entering `BootstrappingEnvironment`.
- **§13.11 Infra-sensitive file detection**: `InfraSensitiveDetector.requiresRebuild()` checks patch `filesChanged` against infra-sensitive patterns (Dockerfiles, lock files, .env, migrations, etc.) to choose between `SoftResettingEnvironment` and `RebuildingEnvironment`.

---

## 4. Verdict Aggregation (Spec §4.3–4.4) — ✅ Complete

- **§4.3 Requirement-level aggregation**: `VerdictAggregator.requirementVerdict()` computes a single verdict from all verifiers for a requirement (all Pass → Pass, mix Pass+Flake → Flake, any Fail → Fail).
- **§4.4 Priority-aware attempt-level**: `VerdictAggregator.aggregateWithGraph()` only uses `Required`-priority requirements to determine overall pass/fail. `Important` and `NiceToHave` failures do NOT block success.
- **§4.5 Flake propagation**: If any required requirement is Flake, overall verdict is Flake (still success for control flow).
- **stopOnFailure**: `VerificationEngine` tracks stopped requirements and skips remaining verifiers with `Blocked` status.
- **AggregateResult**: Includes `flakeCount` and `blockedCount` alongside existing counts.
- **Test coverage**: 20 tests covering legacy flat aggregation, requirement-level verdict, priority-aware aggregation, and edge cases.

---

## 5. Repair Backend (Spec §10) — ✅ Complete

- **§10.1 Session-based interface**: `RepairBackend` trait now defines `prepareSession`, `submitRepairTask`, `cancel`, `getUsage`, `closeSession`, `backendId` with default implementations. `proposePatch` retained as convenience wrapper.
- **§10.1 Implementations**: Both `InferenceBackedRepairBackend` and `ClaudeRepairBackend` implement the full session lifecycle with in-memory session state tracking (TrieMap), usage recording, and cancellation support.
- **§10.3 Tool taxonomy**: `RepairTool`, `ToolCategory`, `ToolParameter`, `ToolCallRecord` types defined in `core-model/tool_types.scala`. `RepairTools.defaultToolSet` provides the standard tool definitions (`read_file`, `write_file`, `list_directory`, `search_files`, `run_command`).
- **§10.11 Structured JSON parsing**: `ClaudeRepairBackend` uses circe for structured JSON parsing of LLM responses instead of regex-based extraction.
- **Prompt building**: `RepairPromptBuilder.buildRepairRequestPrompt(RepairRequest)` method supports session-based interface. `ClaudePromptBuilder` implements it.

---

## 6. Worker Protocol (Spec §9) — ✅ Complete

- **JSON-RPC 2.0 over stdio** — Correct. Newline-delimited, UTF-8.
- **Methods**: `initialize`, `shutdown`, `cancel`, `executeBrowserFlow`, `executeAuthBootstrap`, `executeApiRequest`, `capturePageSnapshot`, `ping` — all registered.
- **Scala-side client**: Correct lifecycle, restart budget, crash detection.
- **Browser flow**: Actions, assertions, artifact capture, tracing, console/network capture with limits.
- **Selector resolution**: css, xpath, text, role, testId, label, placeholder strategies.
- **Error codes**: Full spec §9.4 catalog — `NAVIGATION_FAILED` (-32010), `SELECTOR_NOT_FOUND` (-32011), `ACTION_TIMEOUT` (-32012), `ASSERTION_FAILED` (-32013), `BROWSER_CRASHED` (-32014), `POLICY_VIOLATION` (-32015), `STORAGE_STATE_INVALID` (-32016), `REQUEST_FAILED` (-32020), `REQUEST_TIMEOUT` (-32021), `AUTH_FAILED` (-32030), `AUTH_TIMEOUT` (-32031), `CAPTURE_FAILED` (-32040).
- **§9.3.1 Initialize params**: `WorkerMessages.initializeParams()` now includes `BrowserOptions` (viewport, locale, timezone, deviceScaleFactor, ignoreHTTPSErrors), `DefaultTimeouts` (navigationMs, actionMs, assertionMs), and `BrowserPolicy` (allowedOrigins, forbiddenOrigins).
- **§9.2 Heartbeat/progress protocol**: `HeartbeatNotification` and `ProgressNotification` types defined with parsers in `WorkerMessages`.

---

## 7. Persistence Layer (Spec §7) — ✅ Complete

- **V001__initial.sql**: All required tables present.
- **V002__build_mode.sql**: `feature_plans` table and `generation_mode` column.
- **Database.scala**: Correct PRAGMA settings (WAL, foreign_keys, synchronous=NORMAL, busy_timeout=5000).
- **Migrator.scala**: Version tracking, transactional migration application.
- **Repos**: TaskRunRepo, AttemptRepo, VerdictRepo, PatchRepo, ArtifactRecordRepo, EventRepo, etc.

---

## 8. Resume Logic (Spec §7.6) — ✅ Complete

- **ResumeManager**: Handles orphan cleanup, worktree verification, resume state determination.
- **ResumeDataLoader**: Loads persisted inspection reports, requirement graphs, runtime plans, patch history.
- **RunOrchestrator**: `shouldExecute` gates phases based on `resumeFromStatus`.
- **Non-resumable states** correctly handled.
- **§7.6 PRAGMA integrity_check**: `ResumeManager.prepareResume()` now runs `PRAGMA integrity_check` before proceeding with resume. Fails with descriptive error if database is corrupted.

---

## 9. CLI Commands (Spec §15) — ✅ Complete

All commands implemented: `run`, `build`, `plan`, `resume`, `status`, `inspect-run`, `open-artifact`, `explain-failure`, `cancel`, `clean`, `doctor`, `init` (aliased as `init-manifest`), `serve`.

**Exit codes** match spec §14.3: Success=0, Exhausted=1, Cancelled=2, Errored=3, InputError=4, ConcurrentRunConflict=5, ResumeFailed=10.

---

## 10. Inference Service (Spec §5) — ✅ Complete

- **InferenceService trait**: `infer`, `remainingBudget`, `getUsage` per spec.
- **InferenceServiceImpl**: Budget check → cache check → replay mode → backend call → retry → usage recording → cache store.
- **AnthropicInferenceBackend**: Real API backend with proper rate limit handling.
- **InferenceBudgetTracker**: Allowed caller validation.
- **InferenceCache**: SHA-256 cache keying per spec §5.7.
- **§5.8 Replay mode**: `--replay-inference` CLI flag supported. `InferenceServiceImpl.replayMode` serves from cache only.
- **§5.9 UsageRecords persisted**: `InferenceServiceImpl` now accepts optional `usageRecordPersister` callback for SQLite persistence. `UsageRecordRepo` provides `insert` and `listByRunId` methods.

- **§5.3 Future-based interface**: `InferenceService.infer` now returns `Future[Either[InferenceError, InferenceResponse]]` per spec. `InferenceServiceImpl` wraps synchronous logic in `Future.successful`. All callers use `Await.result` for blocking semantics (orchestrator is single-threaded).

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

## 13. Environment Planner & Runtime Supervisor (Spec §13) — ✅ Complete

- **EnvironmentPlannerImpl**: Plans from manifest or inspection. Topological sort with cycle detection.
- **RuntimeSupervisorImpl**: Dependency-ordered startup, readiness checks, fixture execution, snapshot capture.
- **EnvironmentHealthMonitor**: Degraded/Failed/Healthy detection, recovery attempts.
- **ReadinessChecker**: HTTP, TCP, exec, log_contains probes.
- **§13 Observability taps**: `LogCollector.collectAfterVerification()` now reads `ObservabilityTap` entries from `RuntimePlan.observabilityTaps`. Supports `log_file`, `docker_logs`, and `service_stdout` tap types. Tap data included in serialized logs alongside service logs.

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

### 16a. CI/CD — ✅ Implemented

GitHub Actions CI workflow at `.github/workflows/ci.yml`: Bazel build + test, Node.js worker build + test, concurrency control, caching.

### 16b. No End-to-End Integration Tests — 🟡 Low

All test suites are unit-level per module. No integration test that exercises the full orchestration pipeline against a real target application.

---

## 17. Additional Cross-Cutting Findings

### 17a. AuthContext Type — ✅ Fixed

`AuthContext` case class defined in `runtime_types.scala` with `mode`, `storageStatePath`, `apiHeaders`, `staticToken`, `devBypassHeaders`, `expiresAt`. `RunOrchestrator` now builds `AuthContext` from `AuthBootstrapExecutor.AuthResult` and threads it through `VerificationEngine` instead of raw `storageStatePath: Option[String]`.

### 17b. `--replay-inference` CLI Flag — ✅ Fixed

`--replay-inference` flag now supported on both `run` and `build` commands. `InferenceServiceImpl.replayMode` serves from cache only.

### 17c. Artifact Orphan Cleanup on Startup — ✅ Fixed

`ResumeManager.cleanTmpFiles()` deletes `.tmp-*` files in artifact directories per spec §7.5.

### 17d. `PRAGMA integrity_check` on Resume — ✅ Fixed

`ResumeManager.prepareResume()` now runs `PRAGMA integrity_check` before proceeding with resume per spec §7.6.

### 17e. EvidenceCollector Trait — ✅ Fixed

`EvidenceCollector` trait defined in `artifact-store` module with `registerWorkerArtifacts`, `writeVerdictArtifact`, `writeFailurePacketArtifact`, `writeFinalReportArtifact`, `writeAttemptReportArtifact`. `EvidenceCollectorImpl` implements it.

---

## Summary: Resolved Issues

All previously-identified high and medium severity issues have been resolved. The following table shows all items and their final status.

| # | Prior Severity | Area | Issue | Status |
|---|---------------|------|-------|--------|
| 1 | 🔴 High | Repair §10.1 | RepairBackend trait session-based interface | ✅ Fixed — `prepareSession`/`submitRepairTask`/`cancel`/`getUsage`/`closeSession` with default impls |
| 2 | 🟡 Medium | Orchestrator §2.1 | EnvironmentFailed state / env boot retry | ✅ Fixed — `maxEnvBootRetries=2`, transitions through `EnvironmentFailed` |
| 3 | 🟡 Medium | Orchestrator §10.9 | RepairFailed retry within attempt | ✅ Fixed — `maxRepairRetriesPerAttempt=2`, transitions through `RepairFailed` |
| 4 | 🟡 Medium | Orchestrator §13.11 | Infra-sensitive file detection | ✅ Fixed — `InfraSensitiveDetector.requiresRebuild()` chooses soft vs full rebuild |
| 5 | 🟡 Medium | Repair §10.3 | Tool taxonomy types | ✅ Fixed — `RepairTool`, `ToolCategory`, `ToolParameter`, `ToolCallRecord` in `tool_types.scala` |
| 6 | 🟡 Medium | Infra | CI/CD | ✅ Fixed — GitHub Actions CI at `.github/workflows/ci.yml` |
| 7 | 🟡 Low | Repair §10.11 | ClaudeRepairBackend JSON parsing | ✅ Fixed — Uses circe structured JSON parsing |
| 8 | 🟡 Low | Environment §13 | Observability taps | ✅ Fixed — `LogCollector` reads `ObservabilityTap` entries (log_file, docker_logs, service_stdout) |
| 9 | 🟡 Low | Inference §5.9 | UsageRecords persist to SQLite | ✅ Fixed — `UsageRecordRepo` + `usageRecordPersister` callback in `InferenceServiceImpl` |
| 10 | 🟡 Low | Worker §9.3.1 | Worker initialize params | ✅ Fixed — `BrowserOptions`, `DefaultTimeouts`, `BrowserPolicy` in `WorkerMessages` |
| 11 | 🟡 Low | Worker §9.2 | Heartbeat/progress protocol | ✅ Fixed — `HeartbeatNotification`, `ProgressNotification` types + parsers |
| 12 | 🟡 Low | CLI §15.1 | `--replay-inference` CLI flag | ✅ Fixed — Supported on `run` and `build` commands |
| 13 | 🟡 Low | Resume §7.6 | `PRAGMA integrity_check` on resume | ✅ Fixed — `ResumeManager.checkDatabaseIntegrity()` |
| 14 | 🟡 Low | Artifacts §14.1 | EvidenceCollector trait | ✅ Fixed — Trait + impl in `artifact-store` module |
| 15 | 🟡 Low | Inference §5.3 | InferenceService Future-based interface | ✅ Fixed — `infer` returns `Future[Either[...]]`, callers use `Await.result` |
| 16 | 🟡 Low | Core Model §3.2 | AuthContext structured type | ✅ Fixed — Orchestrator builds `AuthContext` from `AuthResult`, threads through `VerificationEngine` |

### Remaining Minor Items (Low Priority)

| # | Severity | Area | Issue |
|---|----------|------|-------|
| 1 | 🟡 Low | Testing | No end-to-end integration tests exercising full orchestration pipeline against a real target application. |
