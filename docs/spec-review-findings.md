# Demiurge Spec Review Findings

Systematic review of the implementation against `7_canonical_spec.md` and `design-auto-config-and-build-mode.md`.

**Codebase:** ~15,131 Scala LOC, ~1,470 TypeScript LOC, 17 modules, 43 build targets, 20 test targets.

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

## 2. Core DTOs (Spec §3.2) — ✅ Mostly Complete

- **TaskRun, Attempt, AttemptVerdictSummary** — Match spec. Build mode fields present (runMode, featurePlanId).
- **RequirementGraph, RequirementNode, DependencyEdge** — Match spec.
- **ExecutionBudget** — Match spec. BuildBudgetDefaults adds higher limits for build mode.
- **FailurePacket, SuspectedCause, RepairScope, ReproductionStep** — Match spec.
- **PatchProposal, FileEdit, NewFile, FileDeletion** — Match spec.
- **FeaturePlan, PlannedFile, PlannedModification** — Per design doc.
- **InferenceRequest, InferenceResponse** — Match spec §5.

---

## 3. Orchestrator State Machine (Spec §2.1) — ⚠️ Major Gaps

### 3a. Build Mode NOT Wired — 🔴 Critical

`BuildPhaseManager` exists (295 LOC) but `RunOrchestrator.execute()` never calls it. The `PlanningFeature` and `GeneratingCode` RunStatus values are **dead code**. The orchestrator's `phaseOrder` includes these states but the `shouldExecute` dispatch for them is missing or unreachable.

**Impact:** `demiurge build` / `run --mode build` will not perform code generation.

### 3b. Multi-Attempt Repair Loop — ⚠️ Partially Implemented

The orchestrator does loop on repair (incrementing `attemptNumber` and going back to `ReadyToVerify`), but:
- The spec's `RepairBackend` trait (§10.1) calls for a session-based interface with `prepareSession`/`submitRepairTask`/`cancel`/`getUsage`/`closeSession`. The implementation uses a simpler `proposePatch(packet, context)` interface.
- No session lifecycle management, no transcript capture, no tool validation/approval hooks.
- No `preApplyCommitSha`/`postApplyCommitSha` capture (placeholders in PatchRepo insert).
- No `maxRepairRetriesPerAttempt` — repair is one-shot per attempt.

### 3c. BootstrappingAuth State Never Entered — 🟡 Medium

`BootstrappingAuth` is defined in RunStatus but the orchestrator never transitions to it. Auth bootstrap is supported in the worker (`executeAuthBootstrap`) and in RuntimePlan (`authBootstrapPlan`), but the orchestrator skips it.

---

## 4. Verification Engine (Spec §12) — ⚠️ Major Gaps

### 4a. No Layer-Based Execution — 🔴 Critical

Spec §12.3-12.4 requires verifiers to execute in **layers 0-4**, with parallel groups within each layer. The implementation runs all verifiers **sequentially in flat order** with no layer awareness. `VerifierSpec.executionLayer` and `parallelSafe` fields exist in the DTO but are ignored by `VerificationEngine.runVerification()`.

Missing:
- `OrderedVerifierPlan` / `VerifierLayer` / `ParallelGroup` construction
- Layer-ordered execution
- Parallel dispatch within groups
- Blocked detection (checking hard dependency verdicts before execution)

### 4b. Retry Count Not Tracked on Verdicts — 🟡 Medium

`RequirementVerdict.retryCount` is always hardcoded to `0`. `VerifierExecutor` does implement retry logic internally, but doesn't expose the count.

### 4c. No Flake Detection — 🟡 Medium

Spec defines `VerdictStatus.Flake` but no code path produces it. A verifier that passes on retry should be marked `Flake` per spec.

---

## 5. Repair Backend (Spec §10) — ⚠️ Significant Deviation

### 5a. Simplified Interface — 🟡 Medium

Spec defines a session-based `RepairBackend` with `prepareSession`/`submitRepairTask`/`cancel`/`getUsage`/`closeSession`. Implementation uses a single synchronous `proposePatch(packet, context): RepairResponse`.

Missing per spec:
- **Tool taxonomy** (§10.3): `read_file`, `write_file`, `list_directory`, `search_files`, `run_command` — not exposed to the LLM. Instead, Claude generates a JSON patch directly.
- **Tool validation and approval hooks** (§10.3, §10.4)
- **Working copy guarantees** (§10.5): No pre/post commit SHA capture, no dirty worktree cleanup.
- **Transcript capture** (§10.6): No RepairTranscript artifact produced.
- **Usage/cost reporting** per repair (§10.7)
- **Timeout/cancellation** of repair tasks (§10.8)
- **Retry semantics** within a session (§10.9)
- **Malformed output handling** rules (§10.10)

### 5b. ClaudeRepairBackend — Manual JSON Parsing — 🟡 Low

Uses hand-rolled regex-based JSON extraction (`extractString`, `extractStringArray`, `extractEdits`). Fragile for complex responses. No use of Claude Agent SDK as spec §10.11 requires.

---

## 6. Worker Protocol (Spec §9) — ✅ Well Implemented

- **JSON-RPC 2.0 over stdio** — Correct. Newline-delimited, UTF-8.
- **Methods**: `initialize`, `shutdown`, `cancel`, `executeBrowserFlow`, `executeAuthBootstrap`, `executeApiRequest`, `capturePageSnapshot`, `ping` — all registered.
- **Scala-side client** (`WorkerProcessManager`, `WorkerClient`): Correct lifecycle, restart budget, crash detection, notification handling.
- **Worker types** (`rpc/types.ts`): Correct error codes, request/response types per spec §10.1-10.6.
- **Browser flow execution**: Actions, assertions, artifact capture, tracing, console/network capture implemented.
- **Selector resolution**: Supports css, xpath, text, role, testId, label, placeholder strategies.

### 6a. Missing SELECTOR_NOT_FOUND Error Code — 🟡 Medium

Spec §9.13 defines error code `-32011` for `SELECTOR_NOT_FOUND`. Worker `ErrorCodes` only has `-32000` through `-32003`. Missing: `-32010` (NAVIGATION_FAILED), `-32011` (SELECTOR_NOT_FOUND), `-32012` (ASSERTION_ERROR), `-32013` (POLICY_VIOLATION).

### 6b. No Network Capture Limits — 🟡 Low

Spec §9.9 caps captured requests at 500 and truncates URLs. Implementation captures all requests unbounded.

### 6c. No Console Capture Limits — 🟡 Low

Spec §9.10 caps console entries at 200 and truncates to 4096 chars. Implementation captures all entries unbounded.

---

## 7. Persistence Layer (Spec §7) — ✅ Complete

- **V001__initial.sql**: All required tables present — `task_runs`, `attempts`, `requirement_graphs`, `requirement_verdicts`, `runtime_plans`, `runtime_snapshots`, `failure_packets`, `rerun_plans`, `patch_records`, `artifact_records`, `policy_snapshots`, `usage_records`, `inference_cache`, `repo_inspection_reports`, `events`.
- **V002__build_mode.sql**: `feature_plans` table and `generation_mode` column on `patch_records`.
- **Database.scala**: Correct PRAGMA settings (WAL, foreign_keys, synchronous=NORMAL, busy_timeout=5000).
- **Migrator.scala**: Version tracking, transactional migration application.
- **Repos**: TaskRunRepo, AttemptRepo, VerdictRepo, PatchRepo, ArtifactRecordRepo, EventRepo, etc.

### 7a. PatchRepo Insert — Placeholder Fields — 🟡 Low

`PatchRepo.insert()` passes empty strings/nulls for `diff_artifact_id`, `infra_sensitive_files_json`, `transcript_artifact_id`, `usage_record_id`, `pre_apply_commit_sha`, `post_apply_commit_sha`. These are schema-present but never populated with real data.

---

## 8. Resume Logic (Spec §7.6) — ✅ Well Implemented

- **ResumeManager**: Handles orphan cleanup, worktree verification, resume state determination.
- **ResumeDataLoader**: Loads persisted inspection reports, requirement graphs, runtime plans, patch history.
- **RunOrchestrator**: `shouldExecute` gates phases based on `resumeFromStatus`.
- **Non-resumable states** correctly handled: BootstrappingEnvironment, SeedingFixtures, Repairing → abort attempt and retry.

---

## 9. CLI Commands (Spec §15) — ✅ Complete

All commands implemented: `run`, `resume`, `status`, `cancel`, `clean`, `doctor`, `plan`, `inspect-run`, `open-artifact`, `explain-failure`, `init-manifest`, `build`.

**Exit codes** match spec §14.3: Success=0, Exhausted=1, Cancelled=2, Errored=3, InputError=4, ConcurrentRunConflict=5, ResumeFailed=10.

### 9a. `build` Command — 🟡 Medium

Parser and handler exist but since build mode orchestration is not wired (§3a above), the command will not produce meaningful results.

---

## 10. Inference Service (Spec §5) — ✅ Well Implemented

- **InferenceService trait**: `infer`, `remainingBudget`, `getUsage` per spec.
- **InferenceServiceImpl**: Budget check → cache check → replay mode → backend call → retry (1 retry, 2s backoff) → usage recording → cache store.
- **AnthropicInferenceBackend**: Real API backend with proper rate limit handling (429 → `RateLimited`), timeout handling, response parsing.
- **MockInferenceBackend**: For testing.
- **InferenceBudgetTracker**: Allowed caller validation.
- **InferenceCache**: SHA-256 cache keying per spec §5.7.

### 10a. Dual LLM Paths — 🟡 Medium

`repair-claude` module has its own `ClaudeClient` that directly calls the Anthropic API, bypassing `InferenceService` entirely. This means repair calls are **not budget-tracked, not cached, and not audited** through the unified inference pipeline. The spec requires all LLM calls to go through `InferenceService`.

---

## 11. Local API Server (Spec §14.4) — ✅ Well Implemented

- Binds to `127.0.0.1:19440`.
- All endpoints: `/health`, `GET /runs/{id}`, `GET /runs/{id}/plan`, `GET /runs/{id}/attempts`, `GET /runs/{id}/attempts/{num}/verdicts`, `GET /runs/{id}/artifacts`, `GET /runs/{id}/artifacts/{id}/content`, `POST /runs/{id}/resume`, `POST /runs/{id}/cancel`, `GET /runs/{id}/events` (SSE), `POST /runs`.
- **ApiEnvelope**: JSON envelope with status/error fields.
- **EventStream**: In-memory pub/sub with SSE formatting.
- Pagination support on artifacts endpoint.

---

## 12. Artifact Store (Spec §12) — ✅ Well Implemented

- **ArtifactSink**: Temp-file-then-rename, SHA-256 checksums, gzip compression (>1MB), disk budget enforcement.
- **EvidenceCollector**: Registers worker artifacts, writes verdict/failure/report artifacts, assembles prompt packages with priority-based curation (§14.1, §14.6).
- **ArtifactPaths**: Correct path layout under `.demiurge/artifacts/<runId>/`.
- Essential artifact types bypass budget check.

---

## 13. Environment Planner & Runtime Supervisor (Spec §8) — ✅ Mostly Complete

- **EnvironmentPlannerImpl**: Plans from manifest or inspection. Topological sort with cycle detection. Fixture steps, readiness probes, restart policies.
- **RuntimeSupervisorImpl**: Dependency-ordered startup, readiness checks, fixture execution, snapshot capture.
- **ServiceProcessManager**: Script and compose startup modes.
- **TeardownManager**: Reverse-order teardown, PID file cleanup.
- **ReadinessChecker**: HTTP, TCP, exec, log_contains probes.

### 13a. No Degrade/Recovery Loop — 🟡 Medium

Spec describes environment degradation detection and recovery. Implementation is simple boot pass/fail with no ongoing health monitoring.

### 13b. No Observability Taps — 🟡 Low

Config schema supports `observabilityTaps` but nothing reads service logs during verification. RuntimePlan always has `observabilityTaps = Nil`.

---

## 14. Config Resolver (Design Doc Phase A) — ✅ Implemented

- Layered resolution: explicit YAML → cached inference → live inference.
- `InferredConfigWriter` for persisting inferred config.
- `init` command with `--smart` flag for LLM augmentation.

---

## 15. Cross-Cutting Concerns

### 15a. No CI/CD — 🟡 Medium

No GitHub Actions, no CI config of any kind.

### 15b. No End-to-End Integration Tests — 🟡 Medium

All 59 test suites are unit-level per module. No integration test that exercises the full orchestration pipeline.

### 15c. No Policy Module — 🟡 Low

`modules/policy/` directory exists but has no Scala source. PolicySnapshot table exists in schema but is never populated. `FilesystemPolicy`, `BrowserPolicy`, `NetworkPolicy` DTOs exist in core-model but are not enforced.

---

## Summary: Priority Issues

| # | Severity | Area | Issue |
|---|----------|------|-------|
| 1 | 🔴 Critical | Orchestrator | Build mode not wired — PlanningFeature/GeneratingCode are dead code |
| 2 | 🔴 Critical | Verification | No layer-based execution — all verifiers run sequentially, ignoring layers and parallel groups |
| 3 | 🟡 Medium | Repair | Simplified interface vs spec's session-based protocol — no tools, no transcript, no approval hooks |
| 4 | 🟡 Medium | Repair | ClaudeRepairBackend bypasses InferenceService — no budget tracking/caching/auditing |
| 5 | 🟡 Medium | Orchestrator | BootstrappingAuth state never entered |
| 6 | 🟡 Medium | Worker | Missing application-specific error codes (-32010 through -32013) |
| 7 | 🟡 Medium | Verification | No flake detection, retry count not tracked |
| 8 | 🟡 Medium | Environment | No degrade/recovery monitoring loop |
| 9 | 🟡 Medium | Infra | No CI/CD, no integration tests |
| 10 | 🟡 Low | Persistence | PatchRepo placeholder fields never populated |
| 11 | 🟡 Low | Worker | No network/console capture limits |
| 12 | 🟡 Low | Policy | Policy module empty — no runtime enforcement |
