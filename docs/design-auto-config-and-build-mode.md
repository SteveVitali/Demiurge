# Design: Auto-Configuration & Build Mode

**Status:** Draft
**Date:** 2026-03-17

This document specifies two interconnected features:
1. **Auto-Configuration** — eliminate hand-written YAML; infer everything from the repo + task string
2. **Build Mode** — autonomous feature generation with verify/repair loop

---

## Table of Contents

1. [Motivation](#1-motivation)
2. [Design Principles](#2-design-principles)
3. [Auto-Configuration Pipeline](#3-auto-configuration-pipeline)
4. [Build Mode](#4-build-mode)
5. [State Machine Changes](#5-state-machine-changes)
6. [CLI Changes](#6-cli-changes)
7. [New & Modified Modules](#7-new--modified-modules)
8. [Budget & Policies](#8-budget--policies)
9. [Git Integration](#9-git-integration)
10. [Prompt Design](#10-prompt-design)
11. [Migration & Backwards Compatibility](#11-migration--backwards-compatibility)
12. [Implementation Plan](#12-implementation-plan)

---

## 1. Motivation

Today, Demiurge requires users to hand-write three YAML files (`demiurge.yaml`, `requirements.yaml`, `selectors.yaml`) before it can do anything. This is a non-starter for adoption. The only required user input should be a **task string**.

Additionally, Demiurge currently only operates in a verify→repair loop on *existing* code. Users need a mode where they describe a feature, Demiurge generates the code, then verifies it — closing the full loop from spec to working feature.

These two features are tightly coupled: if requirements are LLM-generated from the task string (auto-config), then build mode is a natural extension — the system already knows *what* to build and *how to verify* it.

---

## 2. Design Principles

1. **Task string is the primary interface.** `demiurge run --task "..."` or `demiurge build --task "..."` should be the only required input.
2. **YAML files are optional overrides, not required inputs.** They exist for power users who want deterministic control.
3. **Show-then-act.** Before executing, Demiurge shows what it plans to do and asks for confirmation (in interactive mode). In CI or with `--yes`, it proceeds automatically.
4. **Cache inferences.** Auto-generated configs are written to `.demiurge/inferred/` so they can be inspected, committed, or overridden.
5. **Build and repair share the same code generation backend.** The `RepairBackend` interface is general enough for both. The difference is context, not mechanism.
6. **Isolated by default.** All changes happen in a git worktree. The user's working tree is never modified during a run.

---

## 3. Auto-Configuration Pipeline

### 3.1 Overview

When a user runs any command, Demiurge resolves configuration through a **layered inference pipeline**:

```
Layer 1: Explicit YAML (demiurge.yaml, requirements.yaml, selectors.yaml)
    ↓ fills gaps in
Layer 2: Cached inference (.demiurge/inferred/*.yaml from previous runs)
    ↓ fills gaps in
Layer 3: Live inference (RepoInspector + LLM, run at execution time)
```

Each layer only fills in what the layers above didn't provide. If `demiurge.yaml` exists and is complete, no inference runs. If it's partial, inference fills the gaps. If it doesn't exist at all, everything is inferred.

### 3.2 ConfigResolver

New component: `ConfigResolver` (in `modules/manifest/`).

```
trait ConfigResolver {
  def resolve(
    repoPath: Path,
    taskText: String,
    changedFiles: Option[List[String]],
    inspectionReport: RepoInspectionReport,
    inferenceService: Option[InferenceService],
  ): ResolvedConfig
}
```

`ResolvedConfig` is the fully-resolved, ready-to-use configuration:

```
case class ResolvedConfig(
  app:            AppConfig,
  services:       List[ServiceConfig],
  fixtures:       Option[FixturesConfig],
  auth:           Option[AuthConfig],
  verification:   VerificationConfig,
  inference:      InferenceConfig,
  policies:       PoliciesConfig,
  observability:  Option[ObservabilityConfig],
  requirements:   List[ResolvedRequirement],  // merged from YAML + LLM
  provenance:     ConfigProvenance,           // tracks where each field came from
)

case class ConfigProvenance(
  manifestSource:      ConfigSource,  // Explicit | Cached | Inferred
  requirementSources:  Map[String, ConfigSource],  // per requirement ID
  servicesSources:     Map[String, ConfigSource],  // per service ID
)

sealed trait ConfigSource
object ConfigSource {
  case object Explicit extends ConfigSource     // from user-written YAML
  case object Cached extends ConfigSource       // from .demiurge/inferred/
  case object Inferred extends ConfigSource     // from LLM at runtime
  case object Default extends ConfigSource      // from built-in defaults
}
```

### 3.3 What gets inferred at each layer

#### Layer 1: Explicit YAML (existing behavior, unchanged)

Files: `demiurge.yaml`, `requirements.yaml`, `selectors.yaml` in repo root.

If all three exist and are complete, no inference is needed. The system works exactly as it does today.

#### Layer 2: Cached inference

Directory: `.demiurge/inferred/`

After a successful inference run, the resolved config is written here:
- `.demiurge/inferred/demiurge.yaml` — inferred manifest
- `.demiurge/inferred/requirements.yaml` — inferred requirements (task-specific, keyed by task hash)
- `.demiurge/inferred/selectors.yaml` — inferred selectors

These are human-readable YAML. Users can copy them to the repo root and edit them to "lock in" the inference. They're also useful for debugging ("what did Demiurge think my app looks like?").

#### Layer 3: Live inference

This is where the LLM comes in. Two phases:

**Phase A: Environment inference (deterministic + LLM)**

Extends the existing `RepoInspector` + `InitManifestCommand` logic, but much smarter:

| Field | Deterministic signals | LLM augmentation |
|-------|----------------------|------------------|
| `app.type` | `package.json` deps (react→frontend, express→api) | Ambiguous cases (monorepo structure) |
| `services` | `docker-compose.yml` parsing, `package.json` scripts, `Procfile`, Dockerfile detection | Service naming, port inference from source code, inter-service dependencies |
| `readiness` | Convention-based (`/health`, `/healthz`, `/api/health`) | Parse route files to find actual health endpoints |
| `fixtures` | Detect `"migrate"`, `"seed"` in `package.json` scripts; Prisma/Knex/Sequelize config | Ordering, which to run on reset vs init |
| `auth` | Detect auth libraries (passport, next-auth, @auth/core), `.env.example` with credentials | Auth mode selection, login URL |
| `ports` | Parse from `docker-compose.yml`, `.env`, source code (`listen(3000)`) | Conflict resolution |

The deterministic layer (existing `RepoInspector` + enhanced `InitManifestCommand` logic) runs first and produces `CandidateService` entries with confidence scores. If confidence is high enough (>0.8), no LLM call is needed. For lower confidence or missing fields, the LLM is called with the repo context.

**Phase B: Requirement inference (LLM-driven)**

This is new and entirely LLM-driven. Given:
- The task string
- The `RepoInspectionReport`
- The resolved environment config (from Phase A)
- (Build mode only) The feature spec

The LLM generates a `RequirementGraph` with:
- HTTP verifiers for API endpoints
- TCP verifiers for infrastructure (DB, cache, queue)
- Browser flow verifiers for UI interactions
- State assertions for data integrity
- Dependency edges between requirements

**The `RequirementCompiler` interface changes.** Today it takes `RequirementsFile` + `SelectorsFile` (parsed YAML). The new interface:

```
trait RequirementCompiler {
  def compile(
    runId: String,
    inspectionReport: RepoInspectionReport,
    taskText: String,
    explicitRequirements: Option[RequirementsFile],  // from YAML, if present
    explicitSelectors: Option[SelectorsFile],         // from YAML, if present
    resolvedConfig: ResolvedConfig,                   // environment context
    inferenceService: Option[InferenceService],       // for LLM-based compilation
  ): RequirementGraph
}
```

When `explicitRequirements` is provided, those are used as-is (merged with any LLM-inferred ones for coverage). When absent, all requirements come from LLM inference.

### 3.4 Selector elimination

`selectors.yaml` is **eliminated as a user-facing concept.** Selectors are discovered at runtime:

1. Browser worker navigates to the target page
2. Captures accessibility tree + DOM snapshot
3. LLM identifies the best selector for each action/assertion
4. Selectors are cached in `.demiurge/inferred/selectors.yaml` for reuse

For explicit overrides, users can still provide `selectors.yaml`, which takes precedence over runtime discovery. But the default path never requires it.

This means `BrowserFlowVerifier` execution changes:
- Today: selectors are pre-specified in the verifier spec
- New: if a selector is `None` in the spec, the worker captures a page snapshot and the orchestrator asks the LLM to produce a selector from the snapshot

### 3.5 `demiurge init` (replaces `init-manifest`)

The `init-manifest` command becomes `init` (alias: `init-manifest` for backwards compat):

```bash
# Smart init — inspects repo, optionally uses LLM, writes all config files
demiurge init

# With LLM augmentation (requires ANTHROPIC_API_KEY)
demiurge init --smart

# Just dump what inference would produce, don't write files
demiurge init --dry-run

# Write to custom location
demiurge init --output-dir ./config
```

`demiurge init` runs the deterministic layer of ConfigResolver and writes:
- `demiurge.yaml` (if not present)
- `requirements.yaml` (if not present) — only environment-readiness requirements (health checks, DB reachability)

`demiurge init --smart` additionally uses the LLM to produce richer requirements based on repo analysis (detected routes, components, etc.).

---

## 4. Build Mode

### 4.1 Concept

Build mode is a `RunMode` where Demiurge:
1. Receives a feature specification (the task string)
2. Inspects the repo to understand the existing codebase
3. Generates requirements from the spec (what "done" looks like)
4. Generates the initial code to implement the feature
5. Boots the environment and runs verification
6. If verification fails, enters the standard repair loop
7. Repeats until all requirements pass or budget is exhausted

### 4.2 RunMode changes

Current `RunMode` values: `Full`, `PlanOnly`, `VerifyOnly`, `RepairOnly`.

Add: **`Build`**.

```scala
sealed trait RunMode
object RunMode {
  case object Full extends RunMode        // existing: verify + optional repair
  case object PlanOnly extends RunMode    // existing: plan without executing
  case object VerifyOnly extends RunMode  // existing: verify only, no repair
  case object RepairOnly extends RunMode  // existing: repair only
  case object Build extends RunMode       // NEW: generate + verify + repair loop
  
  val values: List[RunMode] = List(Full, PlanOnly, VerifyOnly, RepairOnly, Build)
}
```

### 4.3 Build mode state machine

New states added to `RunStatus`:

```scala
case object PlanningFeature extends RunStatus    // LLM generates implementation plan
case object GeneratingCode extends RunStatus     // LLM generates initial code
```

Full build mode path:

```
Created
  → InspectingRepo                    // analyze existing codebase
    → CompilingRequirements           // LLM generates requirements from task spec
      → PlanningEnvironment           // infer environment config
        → PlanningFeature             // NEW: LLM plans what files to create/modify
          → GeneratingCode            // NEW: LLM generates code, PatchApplier writes it
            → BootstrappingEnvironment
              → SeedingFixtures
                → ReadyToVerify
                  → Verifying
                    ├→ Succeeded
                    └→ AnalyzingFailure
                          → PlanningRepair
                            → Repairing
                              → SoftResettingEnvironment
                                → ReadyToVerify
                                  → Verifying
                                    ├→ Succeeded
                                    └→ ... (loop until budget exhausted)
```

The key insight: **`GeneratingCode` produces the same output as `Repairing`** — a `PatchProposal` with `FileEdit`/`NewFile`/`FileDeletion` entries, applied by `PatchApplier`. The only difference is the prompt context.

### 4.4 BuildBackend

Rather than creating a separate `BuildBackend` trait, we extend the existing `RepairBackend` to handle both cases. The `RepairRequest` DTO already contains `taskObjective`, `repoSummary`, `relevantChangedFiles`, `requirementSubset`, etc. — everything needed for initial generation too.

The difference is in the prompt. We introduce a `GenerationMode` to distinguish:

```scala
sealed trait GenerationMode
object GenerationMode {
  case object InitialBuild extends GenerationMode   // "implement this feature"
  case object Repair extends GenerationMode         // "fix this failure"
}
```

The `ClaudeRepairBackend` (renamed to `ClaudeCodegenBackend` or generalized) uses `GenerationMode` to select the appropriate prompt template:

- **InitialBuild prompt**: "Here is the codebase structure. Here are the requirements that define 'done.' Implement the feature. Output a patch."
- **Repair prompt**: "Here is the failing verification. Here is the code that was generated. Fix it. Output a patch." (existing behavior)

The `RepairRequest` DTO is extended with:

```scala
case class RepairRequest(
  // ... existing fields ...
  generationMode:     GenerationMode = GenerationMode.Repair,
  featureSpec:        Option[String] = None,  // full feature description for Build mode
)
```

### 4.5 Multi-attempt loop in Build mode

Build mode uses the same `maxAttempts` budget as repair mode, but the counting is:

- **Attempt 1**: Initial code generation + verification
- **Attempt 2+**: Repair iterations (standard repair loop)

The orchestrator tracks this via the existing `attemptCount` on `TaskRun`. The only structural difference from `Full` mode is that attempt 1 includes `PlanningFeature` → `GeneratingCode` before `BootstrappingEnvironment`.

### 4.6 Feature planning

The `PlanningFeature` state produces a `FeaturePlan`:

```scala
case class FeaturePlan(
  planId:             String,
  runId:              String,
  taskText:           String,
  summary:            String,             // human-readable plan summary
  filesToCreate:      List[PlannedFile],
  filesToModify:      List[PlannedModification],
  filesToDelete:      List[String],
  requiresNewDeps:    List[String],       // npm packages, etc.
  requiresMigration:  Boolean,
  estimatedComplexity: String,            // "small" | "medium" | "large"
  createdAt:          Instant,
)

case class PlannedFile(
  relativePath:       String,
  description:        String,
  category:           String,   // "component", "route", "migration", "test", "config"
)

case class PlannedModification(
  relativePath:       String,
  description:        String,
  changeType:         String,   // "add_import", "add_route", "modify_function", etc.
)
```

This plan is:
1. Persisted to SQLite (new `FeaturePlanRepo`)
2. Shown to the user for confirmation (in interactive mode)
3. Passed to the code generation prompt as structured context

### 4.7 Confirmation flow

In interactive mode (terminal with TTY), build mode pauses after `PlanningFeature`:

```
$ demiurge build --task "Add user registration with email/password"

Inspecting repository... done
Compiling requirements... done (5 requirements generated)
Planning environment... done (3 services: frontend, api, postgres)

--- Feature Plan ---
  Summary: Add a registration page with form, API endpoint, and database migration
  Files to create:
    - src/pages/Register.tsx (component)
    - src/api/routes/register.ts (route)
    - prisma/migrations/002_add_users/migration.sql (migration)
  Files to modify:
    - src/App.tsx (add route)
    - src/api/index.ts (register route handler)
  New dependencies: bcryptjs
  Estimated complexity: medium

--- Requirements ---
  1. [required] POST /api/register returns 201 with valid input
  2. [required] POST /api/register returns 409 for duplicate email
  3. [required] POST /api/register returns 400 for invalid input
  4. [required] Browser: navigate /register → fill form → submit → redirect /dashboard
  5. [important] User record exists in database after registration

Proceed? [Y/n/edit]
```

- **Y** (default): proceed to code generation
- **n**: abort
- **edit**: open the inferred config in `$EDITOR` for manual adjustments

Flags:
- `--yes` / `-y`: skip confirmation (for CI/scripts)
- `--plan-only`: show the plan and exit (equivalent to `demiurge plan --mode build`)

---

## 5. State Machine Changes

### 5.1 New RunStatus values

Add to `RunStatus`:

```scala
case object PlanningFeature extends RunStatus     // Build mode: LLM plans implementation
case object GeneratingCode extends RunStatus      // Build mode: LLM generates initial code
```

Update `values` list accordingly (23 total, up from 21).

### 5.2 Transition rules

New valid transitions:

```
PlanningEnvironment → PlanningFeature      (Build mode only)
PlanningFeature → GeneratingCode           (Build mode only)
GeneratingCode → BootstrappingEnvironment  (Build mode only)
```

The existing transition `PlanningEnvironment → BootstrappingEnvironment` remains valid for non-Build modes.

### 5.3 Resume mapping

`ResumeManager` additions:

| Interrupted in | Resumes at |
|----------------|------------|
| `PlanningFeature` | `PlanningFeature` (re-run planning) |
| `GeneratingCode` | `GeneratingCode` (re-run generation) |

---

## 6. CLI Changes

### 6.1 New `build` command

```bash
demiurge build --task "Add user registration with email/password"
```

This is syntactic sugar for `demiurge run --mode build --task "..."`. Having a dedicated command makes the UX clearer.

Parsed as `BuildCmd`:

```scala
case class BuildCmd(
  task: String,
  maxAttempts: Option[Int]        = None,
  runTimeout: Option[Long]        = None,
  attemptTimeout: Option[Long]    = None,
  maxPatchLines: Option[Int]      = None,
  changedFiles: Option[List[String]] = None,
  gitRef: Option[String]          = None,
  branch: Option[String]          = None,      // --branch: create named branch
  openPr: Boolean                 = false,     // --open-pr: open a PR after success
  yes: Boolean                    = false,     // --yes: skip confirmation
  runId: Option[String]           = None,
  replayInference: Boolean        = false,
  headless: Boolean               = true,
) extends ParsedCommand
```

### 6.2 Updated `run` command

`--mode build` is accepted in the existing `run` command. Behavior identical to `build` command.

### 6.3 Updated `init` command

```bash
demiurge init                  # deterministic repo scan → write config files
demiurge init --smart          # deterministic + LLM augmentation
demiurge init --dry-run        # show what would be generated
demiurge init --force          # overwrite existing files
```

`init-manifest` becomes an alias for `init` (backwards compat).

### 6.4 New flags on `run` and `build`

| Flag | Description | Default |
|------|-------------|---------|
| `--branch <name>` | Create a git branch with changes after success | None (worktree only) |
| `--open-pr` | Create branch + open a PR (implies `--branch` if not set) | `false` |
| `--yes` / `-y` | Skip interactive confirmation | `false` (interactive) |
| `--no-infer` | Require explicit YAML, don't use LLM inference for config | `false` |

---

## 7. New & Modified Modules

### 7.1 New: `modules/config-resolver/`

**Purpose:** Layered configuration resolution (explicit YAML → cached → inferred).

Key types:
- `ConfigResolver` trait + `ConfigResolverImpl`
- `ResolvedConfig`, `ConfigProvenance`, `ConfigSource`
- `InferredConfigCache` — read/write `.demiurge/inferred/`

Dependencies: `core-model`, `manifest`, `requirements`, `selectors`, `repo-inspector`, `inference`

### 7.2 Modified: `modules/manifest/`

- `ManifestParser` unchanged (still parses explicit `demiurge.yaml`)
- Add `ManifestInferrer` — generates manifest YAML from `RepoInspectionReport` + optional LLM
- Add `ManifestMerger` — merges explicit manifest with inferred values (explicit wins)

### 7.3 Modified: `modules/requirement-compiler/`

- `RequirementCompiler.compile` signature extended to accept `Option[InferenceService]`
- New `LlmRequirementGenerator` — generates requirements from task string + repo context
- `RequirementMerger` — merges explicit requirements with LLM-generated ones (explicit wins, LLM fills gaps)

### 7.4 Modified: `modules/repair-api/`

- Add `GenerationMode` sealed trait
- Extend `RepairRequest` with `generationMode` and `featureSpec` fields
- `PatchApplier` unchanged (already handles `NewFile`, `FileEdit`, `FileDeletion`)

### 7.5 Modified: `modules/repair-claude/`

- Rename conceptually to "Claude codegen backend" (file names can stay for now)
- `ClaudePromptBuilder` gains a `buildFeaturePrompt` method alongside existing `buildRepairPrompt`
- `ClaudeRepairBackend.execute` checks `generationMode` to select prompt template

### 7.6 New DTOs in `modules/core-model/`

```scala
// Feature planning (Build mode)
case class FeaturePlan(...)         // defined in §4.6
case class PlannedFile(...)
case class PlannedModification(...)

// Config resolution
case class ResolvedConfig(...)      // defined in §3.2
case class ConfigProvenance(...)
sealed trait ConfigSource

// Generation mode
sealed trait GenerationMode
```

### 7.7 Modified: `modules/persistence/`

- New `FeaturePlanRepo` (insert, getById, getByRunId)
- New table `feature_plans` in migration V002

### 7.8 Modified: `modules/orchestrator/`

- `RunOrchestrator.execute` gains `configResolver: ConfigResolver` parameter
- New `BuildPhaseManager` — handles `PlanningFeature` → `GeneratingCode` transitions
- `ResumeManager` extended with new state mappings

### 7.9 Modified: `modules/cli/`

- New `BuildCommand` handler
- Updated `InitManifestCommand` → `InitCommand`
- `CommandParsers`: new `BuildCmd`, updated `RunCmd` with `--branch`/`--open-pr`/`--yes`, new `InitCmd`
- New `ConfirmationPrompt` utility — reads Y/n from TTY

---

## 8. Budget & Policies

### 8.1 Build mode defaults

Build mode needs higher defaults because initial generation is more expensive than repair:

```scala
object BuildBudgetDefaults {
  def defaults: ExecutionBudget = ExecutionBudget(
    runTimeoutMs              = 7200000L,   // 2 hours (vs 1h for verify)
    attemptTimeoutMs          = 1800000L,   // 30 min (vs 15m for verify)
    verifierTimeoutMs         = 60000L,     // same
    browserActionTimeoutMs    = 15000L,     // same
    repairBackendTimeoutMs    = 600000L,    // 10 min (vs 5m — initial gen is bigger)
    inferenceTimeoutMs        = 120000L,    // same
    softResetTimeoutMs        = 30000L,     // same
    degradedRecoveryTimeoutMs = 30000L,     // same
    maxAttempts               = 8,          // more attempts (vs 5 for verify)
    maxRepairRetriesPerAttempt = 2,         // allow more retries per attempt
    maxRepairTokensPerInvocation = 500000L, // 500k tokens (vs 200k — initial gen is bigger)
    maxExploratorySteps       = 100,        // more exploration (vs 50)
    maxEnvBootRetries         = 3,          // more tolerance (vs 2)
    maxArtifactDiskBytes      = 1073741824L, // 1 GB (vs 512 MB)
    maxLogCaptureBytes        = 10485760L,  // same
    maxPatchLines             = 5000,       // larger patches (vs 2000)
    maxServiceRestarts        = 3,          // more restarts (vs 2)
    healthCheckIntervalMs     = 2000,       // same
    healthCheckMaxFailures    = 30,         // same
  )
}
```

Budget selection happens in `RunCommand`/`BuildCommand` based on `RunMode`:

```scala
val budget = if (runMode == RunMode.Build) BuildBudgetDefaults.defaults
             else ExecutionBudgetDefaults.defaults
```

CLI overrides (e.g., `--max-attempts 10`) always take precedence.

---

## 9. Git Integration

### 9.1 Default: worktree only

All changes are made in the isolated git worktree. After a successful run, the worktree contains the committed changes but nothing happens to the user's working tree or any remote.

The user is told where the worktree is:

```
✓ Build succeeded. Changes are in worktree: .demiurge/worktrees/run-abc123/
  To inspect: cd .demiurge/worktrees/run-abc123/
  To apply:   git -C .demiurge/worktrees/run-abc123/ diff HEAD~1 | git apply
```

### 9.2 `--branch <name>`

Creates a named branch from the worktree's state:

```bash
demiurge build --task "Add registration" --branch feature/registration
```

After success:
1. In the worktree, create branch `feature/registration` pointing at the worktree HEAD
2. Push the branch ref to the main repo (so it's visible in `git branch`)

```
✓ Build succeeded. Branch created: feature/registration
  To switch: git checkout feature/registration
  To diff:   git diff main..feature/registration
```

### 9.3 `--open-pr`

Creates a branch and opens a pull request. Requires a git remote and appropriate credentials.

```bash
demiurge build --task "Add registration" --open-pr
# Implies --branch demiurge/add-registration-<short-hash>
```

After success:
1. Create branch (auto-named if `--branch` not specified: `demiurge/<slugified-task>-<8char-hash>`)
2. Push to remote (`origin` by default)
3. Open PR via GitHub CLI (`gh pr create`) if available, or output the URL for manual creation

```
✓ Build succeeded. PR opened: https://github.com/user/repo/pull/42
  Title: Add user registration with email/password
  Branch: demiurge/add-registration-a1b2c3d4
```

PR body includes:
- Task description
- Summary of changes (files created/modified/deleted)
- Verification results (all requirements passed)
- Artifacts link (if applicable)

### 9.4 Implementation

New `GitIntegration` utility in `modules/orchestrator/`:

```scala
object GitIntegration {
  def createBranch(worktreePath: Path, branchName: String, repoPath: Path): Either[String, Unit]
  def pushBranch(repoPath: Path, branchName: String, remote: String = "origin"): Either[String, Unit]
  def openPullRequest(repoPath: Path, branchName: String, title: String, body: String): Either[String, String]
}
```

`openPullRequest` tries `gh pr create` first, falls back to printing a URL.

---

## 10. Prompt Design

### 10.1 Requirement generation prompt

System prompt:
```
You are a verification requirement generator for a web application.
Given a task description and repository analysis, generate a set of
executable verification requirements that define "done" for this task.

Each requirement must be one of:
- http: HTTP request with expected status code
- tcp: TCP connection check (host:port)
- exec: Shell command with expected exit code 0
- browser_flow: Playwright browser flow with navigation, actions, assertions
- state: Database/state assertion

Output JSON matching the RequirementGraph schema.
```

User prompt includes:
- Task string
- `RepoInspectionReport` summary (languages, frameworks, services, routes, DB schema)
- `ResolvedConfig` (services, ports, URLs) — so requirements target the right endpoints
- Existing requirements from YAML (if any) — so LLM doesn't duplicate

### 10.2 Feature generation prompt (Build mode, attempt 1)

System prompt:
```
You are a full-stack developer implementing a feature in an existing codebase.
Given the repository structure, the feature specification, and the verification
requirements that define "done," generate the code changes needed.

Output a JSON patch proposal with file edits, new files, and deletions.
Follow the existing code style and patterns in the repository.
```

User prompt includes:
- Task string (feature spec)
- `RepoInspectionReport` (codebase structure, frameworks, patterns)
- `FeaturePlan` (from planning phase)
- `RequirementGraph` (what "done" looks like)
- Relevant source files (existing routes, components, models — limited by token budget)
- `RepairOutputContract` (output format requirements)

### 10.3 Repair prompt (attempts 2+)

Existing `ClaudePromptBuilder` behavior, enhanced with:
- Prior attempt summary (what was generated, what failed)
- Verification verdicts with failure details
- Captured artifacts (screenshots, console logs, network errors)

---

## 11. Migration & Backwards Compatibility

### 11.1 Existing users

- All existing YAML-based workflows continue to work unchanged.
- `init-manifest` is aliased to `init` — no breaking change.
- `RunMode.Full` behavior is unchanged.
- The only new enum values (`Build`, `PlanningFeature`, `GeneratingCode`) don't affect existing runs.

### 11.2 Database migration

`V002__build_mode.sql`:
- Add `feature_plans` table
- Add `generation_mode` column to `patch_records` (nullable, default NULL for existing rows)

### 11.3 Config file changes

No existing config files change format. New files:
- `.demiurge/inferred/` directory (auto-created, gitignored by default)

Recommendation: add `.demiurge/inferred/` to the `.gitignore` template, but allow users to commit it if they want deterministic builds.

---

## 12. Implementation Plan

### Phase A: Auto-Configuration Foundation

1. **`ResolvedConfig` DTOs** — new types in `core-model`
2. **`ConfigResolver`** — layered resolution logic in `modules/config-resolver/`
3. **`ManifestInferrer`** — enhanced manifest generation from repo inspection
4. **`LlmRequirementGenerator`** — requirement generation from task string
5. **Updated `RequirementCompiler`** — accept optional inference service
6. **Updated `init` command** — smart init with `--smart` flag
7. **Wire into orchestrator** — `ConfigResolver` called before state machine begins

### Phase B: Build Mode Core

8. **New `RunStatus` values** — `PlanningFeature`, `GeneratingCode`
9. **`FeaturePlan` DTOs** — in `core-model`
10. **`FeaturePlanRepo`** — persistence
11. **`GenerationMode`** — in repair-api
12. **Updated `ClaudePromptBuilder`** — feature generation prompt
13. **`BuildPhaseManager`** — orchestrator logic for planning + generating
14. **Updated `RunOrchestrator.execute`** — build mode branch

### Phase C: CLI & Git Integration

15. **`BuildCmd` parser** — new command
16. **`ConfirmationPrompt`** — interactive Y/n/edit
17. **`GitIntegration`** — branch creation, push, PR opening
18. **`--branch` / `--open-pr` / `--yes` flags**
19. **Updated `init` command** — alias for `init-manifest`

### Phase D: Selector Discovery & Polish

20. **Runtime selector discovery** — worker captures snapshot, LLM produces selectors
21. **Inferred config caching** — write to `.demiurge/inferred/`
22. **`ConfigProvenance` tracking** — show users where each config value came from
23. **Build budget defaults**
24. **Documentation updates**

---

## Appendix: Example End-to-End Flow (Build Mode)

```bash
$ demiurge build --task "Add a user settings page where users can update 
  their display name and email. Include form validation and a success toast."

[1/7] Inspecting repository...
  Languages: TypeScript, JavaScript
  Frameworks: Next.js 14, Prisma, PostgreSQL
  Services: frontend (next dev :3000), db (postgres :5432)

[2/7] Generating requirements from task...
  5 requirements generated:
    1. [required] GET /settings returns 200 (authenticated)
    2. [required] PUT /api/user/settings returns 200 with valid input
    3. [required] PUT /api/user/settings returns 400 with invalid email
    4. [required] Browser: /settings → update name → submit → see success toast
    5. [important] Updated name persists in database

[3/7] Planning environment...
  Services: frontend (script: npm run dev), db (compose: postgres)
  Fixtures: prisma migrate deploy, prisma db seed

[4/7] Planning feature implementation...
  Files to create:
    - src/app/settings/page.tsx (component)
    - src/app/api/user/settings/route.ts (API route)
    - prisma/migrations/003_settings/migration.sql
  Files to modify:
    - src/components/Navigation.tsx (add settings link)
  Estimated complexity: medium

Proceed? [Y/n/edit] Y

[5/7] Generating code... done (4 files created, 1 modified)
[6/7] Booting environment... done (frontend ready, db ready)
[7/7] Verifying...
  ✓ GET /settings returns 200
  ✓ PUT /api/user/settings returns 200
  ✓ PUT /api/user/settings returns 400 for invalid
  ✗ Browser: form submit → success toast (toast not visible after 5s)
  ✓ Database persistence check

Analyzing failure... toast component not imported
Repairing... added import for Toast component
Re-verifying...
  ✓ GET /settings returns 200
  ✓ PUT /api/user/settings returns 200
  ✓ PUT /api/user/settings returns 400 for invalid
  ✓ Browser: form submit → success toast
  ✓ Database persistence check

✓ Build succeeded (2 attempts). Changes in worktree.
  To apply: git diff .demiurge/worktrees/run-a1b2c3d4/
```
