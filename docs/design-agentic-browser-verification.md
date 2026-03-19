# Design: Agentic Browser UI Verification (Option C)

**Status:** Implementation-Ready (v2 — refined with resolved decisions)
**Date:** 2026-03-19
**Revised:** 2026-03-19
**Depends on:** `impl/agent-sdk-integration` (Agent SDK integration must be merged first)

This document specifies the architecture and implementation plan for **Option C: LLM-agent-driven browser UI verification**. All open questions from v1 have been resolved with concrete decisions.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Requirements (Resolved)](#2-requirements-resolved)
3. [Landscape Review: Agentic Browser Frameworks](#3-landscape-review-agentic-browser-frameworks)
4. [Landscape Review: Vision-Based UI Verification](#4-landscape-review-vision-based-ui-verification)
5. [Claude Agent SDK + MCP Integration](#5-claude-agent-sdk--mcp-integration)
6. [Alternatives Evaluated](#6-alternatives-evaluated)
7. [Architecture](#7-architecture)
8. [Verification Agent Prompt](#8-verification-agent-prompt)
9. [Screenshot Strategy & Before/After Comparison](#9-screenshot-strategy--beforeafter-comparison)
10. [Visual Taste Judgment](#10-visual-taste-judgment)
11. [Repair Agent Browser Tools](#11-repair-agent-browser-tools)
12. [Auth Handling](#12-auth-handling)
13. [Multi-Viewport Testing](#13-multi-viewport-testing)
14. [Artifact Collection](#14-artifact-collection)
15. [File-by-File Change Specification](#15-file-by-file-change-specification)
16. [Budget, Timeouts, and Limits](#16-budget-timeouts-and-limits)
17. [Configuration Surface](#17-configuration-surface)
18. [Implementation Plan](#18-implementation-plan)
19. [Resolved Questions](#19-resolved-questions)

---

## 1. Problem Statement

Demiurge has no functioning browser-based UI verification. `BrowserFlowVerifier` exists but is unused — `RequirementCompilerImpl` sets `browserFlowSpec = None` for all types. The existing pipeline requires pre-scripted action sequences and CSS selectors — it cannot autonomously verify a natural-language UI feature description.

**What we need:** Given a paragraph-long spec, the system autonomously navigates, explores, interacts, screenshots, judges spec compliance + visual quality, and returns a structured verdict with evidence.

---

## 2. Requirements (Resolved)

- **Always agentic.** Every frontend requirement uses the agent flow.
- **Before/after screenshots fed to agent.** Pre-implementation screenshots are passed as context to the post-implementation verification agent for comparison.
- **TASTE_ISSUE triggers repair by default.** Configurable via `tasteTriggersRepair` (default `true`) and `tasteSensitivity` (Strict/Normal/Lenient/Off).
- **Multi-viewport is opt-in.** Not tested by default; configure `viewports` per requirement.
- **Headed debug mode.** `browser.headed: true` in demiurge.yaml or `DEMIURGE_BROWSER_HEADED=true` env var.
- **High initial limits.** 120s timeout, $50 budget, 30 turns per requirement.
- **Repair agent gets browser tools.** When repairing a frontend requirement, Playwright MCP is added alongside existing tools.
- **Playwright MCP lifecycle.** One stdio subprocess spawned per agent session (cleanest — fresh browser state each time).
- **Playwright version.** Use `@latest` — no pinning.

---

## 3. Landscape Review: Agentic Browser Frameworks

### 3.1 Microsoft Playwright MCP (`@playwright/mcp`) — **SELECTED**

Official MCP server for Playwright, maintained by Microsoft. ~25 browser tools via Model Context Protocol.

- **Accessibility-tree-based.** Structured snapshots with stable `ref` identifiers — deterministic element targeting.
- **Rich tool set.** `browser_navigate`, `browser_click`, `browser_type`, `browser_fill`, `browser_select_option`, `browser_hover`, `browser_drag`, `browser_press_key`, `browser_take_screenshot`, `browser_snapshot`, `browser_tab_*`, `browser_console_messages`, `browser_resize`, `browser_handle_dialog`, `browser_file_upload`, `browser_pdf_save`, `browser_close`, `browser_wait`, `browser_network_requests`.
- **`browser_snapshot`** — returns accessibility tree for navigation. **`browser_take_screenshot`** — captures PNG for visual judgment.
- **Transport:** stdio subprocess. `npx @playwright/mcp --headless`.
- **Recommended for:** "Specialized agentic loops that benefit from persistent state, rich introspection, and iterative reasoning over page structure."

### 3.2 Rejected Alternatives (Summary)

| Framework | Why Rejected |
|---|---|
| **Playwright CLI+SKILLS** | Designed for coding agents, not dedicated browser agents |
| **Stagehand** | Caching/self-healing redundant when agent already reasons |
| **browser-use** | Python-first, anti-bot irrelevant for localhost, redundant agent loop |
| **OpenBrowser MCP** | Unvetted, token savings secondary at $50/req budget |
| **Chrome DevTools MCP** | "Brutal in practice" per practitioners |

---

## 4. Landscape Review: Vision-Based UI Verification

### 4.1 Dual-Channel Approach (Key Insight)

1. **Accessibility tree** → *navigation and interaction* (deterministic, reliable)
2. **Screenshots** → *judgment and taste* (Claude's vision capabilities)

Strictly superior to either channel alone. Anthropic recommends this exact approach in their agent-building documentation.

### 4.2 Single-Agent Self-Judge

Same agent serves as executor and judge. Takes a screenshot, immediately reasons about spec compliance and visual quality. No separate judge model needed.

---

## 5. Claude Agent SDK + MCP Integration

### 5.1 Playwright MCP as stdio MCP Server

```typescript
// Built in agentExecute.ts based on agentConfig flags
const playwrightArgs = headedBrowser
  ? ['@playwright/mcp@latest']
  : ['@playwright/mcp@latest', '--headless'];

mcpServers.playwright = {
  type: 'stdio',
  command: 'npx',
  args: playwrightArgs,
};
```

- Spawned per agent session — clean browser state, automatic cleanup.
- Headed mode controlled by config for development debugging.

### 5.2 Two MCP Configurations

**Verification-only agent** — Playwright MCP only (black-box):
```typescript
mcpServers: { playwright: playwrightConfig }
// No demiurge MCP, no file tools — pure QA engineer
```

**Repair agent with browser** — Both MCP servers:
```typescript
mcpServers: {
  playwright: playwrightConfig,
  demiurge: demiurgeMcpConfig,  // existing in-process tools
}
```

---

## 6. Alternatives Evaluated

| Approach | Pros | Cons | Verdict |
|---|---|---|---|
| **A. Playwright MCP (stdio) + Claude Agent SDK** | Official, reliable, dual-channel (a11y + vision), clean per-session lifecycle, zero extra deps | ~25 tools in context costs tokens | **RECOMMENDED** |
| **B. Playwright CLI+SKILLS** | More token-efficient | No persistent browser state, designed for coding agents not browser agents | Not suitable |
| **C. Custom MCP tools wrapping our existing `executeBrowserFlow`** | Reuses existing code | Requires pre-scripted flows, can't do exploratory verification | Defeats the purpose |
| **D. Stagehand + Claude Agent SDK** | Self-healing, caching | Extra dependency, redundant agent loop, caching irrelevant for verification | Over-engineered |
| **E. browser-use** | Multi-provider support | Python-first, redundant agent loop, anti-bot features irrelevant | Wrong tool |
| **F. Anthropic Computer Use (pixel-based)** | Works on any app | Fragile coordinate-based clicking, high token cost, slower | Strictly worse than A |
| **G. Custom Demiurge browser MCP tools** | Full control, can add screenshot-saving logic | Reinvents what Playwright MCP already provides | NIH syndrome |

**Recommendation: Option A — Playwright MCP (stdio) + Claude Agent SDK.**

This is the approach Anthropic themselves recommend in their agent-building documentation. It's the official Microsoft MCP server. It integrates natively with the Claude Agent SDK. It provides the dual-channel (accessibility tree + screenshots) approach that gives us both reliable interaction and visual judgment.

---

## 7. Architecture

### 7.1 High-Level Flow

```
RunOrchestrator.execute()
  │
  ├── VerificationEngine.runVerification()
  │     ├── VerifierGenerator: AgentBrowserVerifierSpec → AgentBrowserVerifier
  │     ├── executeSingleVerifier() matches AgentBrowserVerifier
  │     ├── Delegates to AgentBrowserExecutor.execute(verifier)
  │     │
  │     ▼
  │   AgentBrowserExecutor (new class, implements BrowserVerifierExecutor)
  │     ├── Builds JSON-RPC params with mode="verification"
  │     ├── Calls workerManager.sendRawRequest("agent/execute", params)
  │     │
  │     ▼
  │   TypeScript Worker: handleAgentExecute()  [mode="verification"]
  │     ├── Spawns Playwright MCP Server (stdio, per-session)
  │     ├── Does NOT create Demiurge MCP Server (black-box verification)
  │     ├── Builds system prompt via buildVerificationPrompt()
  │     ├── Calls sdkQuery({ prompt, options: { mcpServers: { playwright }, ... } })
  │     │
  │     ▼
  │   Claude Agent SDK session:
  │     ├── Tools: mcp__playwright__* ONLY
  │     ├── Agent loop: navigate → snapshot → screenshot → interact → judge
  │     ├── Returns structured JSON verdict as final message
  │     │
  │     ▼
  │   handleAgentExecute() parses result:
  │     ├── Extracts verdict JSON from agent's final text
  │     ├── Collects screenshot artifacts from tool use log
  │     ├── Saves artifacts via ArtifactWriter
  │     ├── Returns BrowserVerificationResult over JSON-RPC
  │     │
  │     ▼
  │   AgentBrowserExecutor maps result → BrowserVerifierResult
  │   VerificationEngine aggregates into RequirementVerdict
  │
  ├── For repair (when frontend requirement has FAIL or TASTE_ISSUE):
  │     ├── AgentExecutor.execute() [existing, mode="repair"]
  │     ├── mcpServers includes BOTH playwright + demiurge
  │     └── Agent edits code, restarts service, opens browser to visually verify
```

### 7.2 New Types (Scala)

**`modules/core-model/src/main/scala/demiurge/model/verifier_types.scala`** — add:

```scala
case class AgentBrowserVerifierSpec(
  entryUrl:            String,
  featureDescription:  String,
  viewports:           List[Viewport]       = Nil,
  tasteSensitivity:    TasteSensitivity     = TasteSensitivity.Normal,
  tasteTriggersRepair: Boolean              = true,
  maxBudgetUsd:        Double               = 50.0,
)

case class Viewport(width: Int, height: Int)

sealed trait TasteSensitivity
object TasteSensitivity {
  case object Strict  extends TasteSensitivity  // all taste issues → repair
  case object Normal  extends TasteSensitivity  // warning + error → repair
  case object Lenient extends TasteSensitivity  // only error → repair
  case object Off     extends TasteSensitivity  // never triggers repair
}
```

**`modules/verification-engine/src/main/scala/demiurge/verification/Verifier.scala`** — add:

```scala
case class AgentBrowserVerifier(
  id:                 String,
  requirementId:      String,
  entryUrl:           String,
  featureDescription: String,
  timeout:            Duration,
  maxRetries:         Int,
  maxBudgetUsd:       Double          = 50.0,
  maxTurns:           Int             = 30,
  viewports:          List[Viewport]  = Nil,
  tasteSensitivity:   TasteSensitivity = TasteSensitivity.Normal,
  tasteTriggersRepair: Boolean        = true,
  beforeScreenshots:  List[String]    = Nil,  // artifact paths from pre-impl run
) extends Verifier
```

**`modules/core-model/src/main/scala/demiurge/model/verifier_types.scala`** — extend `VerifierSpec`:

```scala
// Add to VerifierSpec case class:
agentBrowserSpec: Option[AgentBrowserVerifierSpec] = None,
```

**`modules/core-model/src/main/scala/demiurge/model/enums.scala`** — extend `VerifierType`:

```scala
// Add:
case object AgentBrowser extends VerifierType
```

### 7.3 New Type: `BrowserVerificationVerdict`

Parsed from the agent's structured JSON output:

```scala
// New in: modules/agent-backend/src/main/scala/demiurge/agent/BrowserVerificationVerdict.scala

case class BrowserVerificationVerdict(
  verdict:          BrowserVerdictStatus,  // PASS | FAIL | TASTE_ISSUE
  confidence:       Double,
  featureSatisfied: Boolean,
  observations:     List[BrowserObservation],
  tasteIssues:      List[TasteIssue],
  screenshots:      List[ScreenshotRef],
  summary:          String,
)

sealed trait BrowserVerdictStatus
object BrowserVerdictStatus {
  case object Pass       extends BrowserVerdictStatus
  case object Fail       extends BrowserVerdictStatus
  case object TasteIssue extends BrowserVerdictStatus
}

case class BrowserObservation(
  aspect:        String,
  status:        String,  // "pass" | "fail" | "warning"
  detail:        String,
  screenshotRef: Option[String],
)

case class TasteIssue(
  severity:      String,  // "error" | "warning" | "info"
  issue:         String,
  element:       Option[String],
  screenshotRef: Option[String],
)

case class ScreenshotRef(
  ref:         String,
  description: String,
  phase:       String,  // "before" | "during" | "after"
)
```

### 7.4 Two Agent Modes (Decision)

**Verification agent** — Playwright MCP only. No file tools, no Demiurge MCP. Enforced by configuring only the `playwright` MCP server. The `mode: "verification"` parameter in `agent/execute` triggers this configuration.

**Repair agent with browser** — Existing tools + Playwright MCP. Triggered when `enableBrowserTools: true` in agent config for `mode: "repair"`.

---

## 8. Verification Agent Prompt

### 8.1 Full System Prompt

Built by `BrowserVerificationPromptBuilder` (new Scala object). The following is the exact prompt template with `{placeholders}`:

```
You are a meticulous QA engineer verifying a web application feature.
You test ONLY through the browser UI — you have no access to source code or servers.

## Feature to Verify

<feature_description>
{featureDescription}
</feature_description>

## Entry Point
Navigate to: {entryUrl}

{IF beforeScreenshots.nonEmpty}
## Before-Implementation Reference

The following screenshots were taken BEFORE the feature was implemented.
They show what the page looked like without the feature. Use them as a baseline
to confirm the feature is now present and correct. If something in the "before"
state looked broken or missing, verify it is now fixed.

{FOREACH screenshot IN beforeScreenshots}
- [{screenshot.description}] ({screenshot.phase})
{END}

These images are provided in the user prompt. Compare them to what you see now.
{END IF}

{IF viewports.nonEmpty}
## Viewport Testing

After completing verification at the default viewport, resize the browser and
re-verify at each of these viewport sizes:
{FOREACH vp IN viewports}
- {vp.width}x{vp.height}
{END}
Use browser_resize(width, height) to change viewport size.
{END IF}

## Verification Protocol

### Step 1: Initial State Capture
- Navigate to the entry URL using browser_navigate
- Wait for the page to load (use browser_wait if needed)
- Take a screenshot of the initial state
- Use browser_snapshot to read the accessibility tree and understand page structure

### Step 2: Systematic Feature Exploration
For each aspect of the feature description above:
- **Content verification:** Check that expected text, headings, labels, and images are present
  using the accessibility snapshot
- **Interactive features:** Fill forms, click buttons, submit data, test the feature end-to-end
- **Navigation:** Verify redirects, page transitions, URL changes
- **Error states:** Test with invalid inputs, empty required fields, edge cases
- **State changes:** Verify that actions produce the expected UI updates

Take a screenshot AFTER each significant interaction to document the result.

### Step 3: Visual Taste Assessment
After verifying functional correctness, take a full-page screenshot and assess:

| Category | Check |
|---|---|
| **Contrast** | Text readable against its background? No light-on-light or dark-on-dark? |
| **Sizing** | Buttons and inputs large enough to click/tap? (min ~32px height) |
| **Alignment** | Elements follow a consistent grid? No jagged edges? |
| **Typography** | Font sizes readable? (min ~14px for body text) Hierarchy clear? |
| **Spacing** | Consistent margins/padding? Nothing touching edges or cramped? |
| **Overflow** | No horizontal scrollbar? No text cut off or clipped? |
| **Loading** | If async operations exist, is there appropriate feedback (spinner, disabled state)? |
| **Consistency** | Colors, fonts, spacing consistent with the rest of the page? |

### Step 4: Verdict
After completing exploration and taste assessment, output your verdict as a JSON
block with EXACTLY this structure:

```json
{
  "verdict": "PASS | FAIL | TASTE_ISSUE",
  "confidence": 0.0 to 1.0,
  "featureSatisfied": true or false,
  "observations": [
    {
      "aspect": "description of what was checked",
      "status": "pass | fail | warning",
      "detail": "what was found",
      "screenshotRef": "screenshot filename or null"
    }
  ],
  "tasteIssues": [
    {
      "severity": "error | warning | info",
      "issue": "description of the visual problem",
      "element": "which element is affected",
      "screenshotRef": "screenshot filename or null"
    }
  ],
  "screenshots": [
    {
      "ref": "screenshot filename",
      "description": "what this screenshot shows",
      "phase": "before | during | after"
    }
  ],
  "summary": "one-paragraph summary of findings"
}
```

Verdict meanings:
- **PASS**: Feature implemented correctly AND passes visual taste assessment
- **FAIL**: Feature not implemented, broken, or has significant functional issues
- **TASTE_ISSUE**: Feature works correctly but has visual quality problems (contrast,
  sizing, alignment, etc.)

## Rules
- Do NOT try to access source code or server files. You are a black-box tester.
- Take at least 3 screenshots: initial state, key interaction, final state.
- If a page is loading slowly, use browser_wait(time) before taking a screenshot.
- If a dialog appears, handle it with browser_handle_dialog.
- Be thorough: test EVERY aspect mentioned in the feature description.
- Your final message MUST contain the JSON verdict block above.
```

### 8.2 User Prompt Construction

The user prompt is built dynamically. For the post-implementation run, it includes before-screenshots as image content:

```typescript
// In agentExecute.ts, for mode="verification":
const userPromptParts: Array<{ type: string; [key: string]: unknown }> = [];

// Text instruction
userPromptParts.push({
  type: 'text',
  text: 'Verify the feature described in your system prompt. Navigate to the entry URL and begin.',
});

// Attach before-screenshots as images (if provided)
if (p.beforeScreenshots?.length) {
  userPromptParts.push({
    type: 'text',
    text: `\n\nThe following ${p.beforeScreenshots.length} image(s) are the "before" screenshots from the pre-implementation state:`,
  });
  for (const screenshotPath of p.beforeScreenshots) {
    const imageData = fs.readFileSync(screenshotPath);
    userPromptParts.push({
      type: 'image',
      source: {
        type: 'base64',
        media_type: 'image/png',
        data: imageData.toString('base64'),
      },
    });
  }
}
```

### 8.3 Verdict Parsing

The agent's final message contains the JSON verdict block. Parsing in TypeScript:

```typescript
// In agentExecute.ts, after conversation completes:
function parseVerificationVerdict(resultText: string): BrowserVerificationResult | null {
  // Extract JSON block from agent's response
  const jsonMatch = resultText.match(/```json\s*\n([\s\S]*?)\n\s*```/);
  if (!jsonMatch) {
    // Try raw JSON object
    const rawMatch = resultText.match(/\{[\s\S]*"verdict"[\s\S]*\}/);
    if (!rawMatch) return null;
    try { return JSON.parse(rawMatch[0]); } catch { return null; }
  }
  try { return JSON.parse(jsonMatch[1]); } catch { return null; }
}
```

---

## 9. Screenshot Strategy & Before/After Comparison

### 9.1 Before/After Flow

Demiurge runs verification twice — pre-implementation (expecting FAIL) and post-implementation (expecting PASS). This creates a natural before/after comparison:

1. **Pre-implementation verification:** Agent runs → takes screenshots → verdict FAIL (expected). Screenshots saved to `artifacts/{runId}/pre/{reqId}/screenshots/`.

2. **Repair/build agent** makes code changes.

3. **Post-implementation verification:** The `beforeScreenshots` field on `AgentBrowserVerifier` is populated with screenshot paths from step 1. These are passed to the agent as image content in the user prompt. The agent compares current state to the before images.

### 9.2 Screenshot Collection from Agent Tool Use

Screenshots are collected by monitoring the agent's message stream:

```typescript
// In agentExecute.ts message processing loop:
const collectedScreenshots: Array<{ path: string; description: string }> = [];
let screenshotCount = 0;
const MAX_SCREENSHOTS = 20;

for await (const message of conversation) {
  // ... existing message processing ...

  if (message.type === 'assistant') {
    for (const block of message.message.content) {
      if (block.type === 'tool_use' &&
          block.name === 'mcp__playwright__browser_take_screenshot') {
        screenshotCount++;
        if (screenshotCount > MAX_SCREENSHOTS) {
          // Could optionally interrupt, but agent prompt already limits this
        }
      }
    }
  }

  // tool_result messages from screenshot calls contain the image data
  // The Playwright MCP returns a file path or base64 image
  // We save it via ArtifactWriter
}
```

### 9.3 Screenshot Storage Paths

```
artifacts/
  {runId}/
    pre/                          ← pre-implementation verification
      {reqId}/
        screenshots/
          initial_state_{ts}.png
          interaction_1_{ts}.png
          ...
        verdict.json
    post/                         ← post-implementation verification
      {reqId}/
        screenshots/
          initial_state_{ts}.png
          interaction_1_{ts}.png
          ...
        verdict.json
    repair/                       ← repair agent screenshots (if any)
      {reqId}/
        screenshots/
          ...
```

---

## 10. Visual Taste Judgment

### 10.1 Taste Rubric (Embedded in System Prompt)

The taste rubric is part of the system prompt (§8.1 Step 3). It covers:

| Category | Check | Example Failures |
|---|---|---|
| **Contrast** | Text vs. background readability | Light gray on white, dark blue on black |
| **Sizing** | Interactive elements ≥32px height | Tiny buttons, cramped radio buttons |
| **Alignment** | Consistent grid/alignment | Jagged left edges, unequal column widths |
| **Typography** | ≥14px body text, clear hierarchy | 8px text, ALL CAPS body, no heading distinction |
| **Spacing** | Consistent margins/padding | Elements touching edges, no breathing room |
| **Overflow** | No horizontal scroll, no clipping | Text cut off, horizontal scrollbar appears |
| **Loading** | Appropriate async feedback | No spinner, no disabled state during submit |
| **Consistency** | Matches rest of page | Different font family, off-brand colors |

### 10.2 Taste Issue Severity Levels

- **`error`** — Unusable: unreadable text, unclickable button, broken layout.
- **`warning`** — Poor quality: low contrast, small targets, minor misalignment.
- **`info`** — Nitpick: slightly inconsistent spacing, could be better but acceptable.

### 10.3 TASTE_ISSUE → Repair Trigger (Decision: Yes by Default)

**Decision:** TASTE_ISSUE triggers repair by default. This is configurable:

```scala
// In AgentBrowserVerifierSpec:
tasteTriggersRepair: Boolean = true,
tasteSensitivity: TasteSensitivity = TasteSensitivity.Normal,
```

Mapping of `tasteSensitivity` to repair behavior:

| Sensitivity | Which taste issues trigger repair |
|---|---|
| `Strict` | All (error + warning + info) |
| `Normal` | error + warning |
| `Lenient` | error only |
| `Off` | None (taste issues reported but never trigger repair) |

In the orchestrator, when evaluating the verification verdict:

```scala
// In AgentBrowserExecutor, mapping verdict to VerifierOutcome:
verdict.verdict match {
  case "PASS"        => VerifierOutcome.Passed
  case "FAIL"        => VerifierOutcome.Failed(verdict.summary)
  case "TASTE_ISSUE" =>
    if (verifier.tasteTriggersRepair) {
      val triggeredIssues = filterByTasteSensitivity(verdict.tasteIssues, verifier.tasteSensitivity)
      if (triggeredIssues.nonEmpty)
        VerifierOutcome.Failed(s"Taste issues: ${triggeredIssues.map(_.issue).mkString("; ")}")
      else
        VerifierOutcome.Passed  // issues below sensitivity threshold
    } else {
      VerifierOutcome.Passed  // taste issues don't trigger repair
    }
}
```

---

## 11. Repair Agent Browser Tools

### 11.1 Why the Repair Agent Needs Browser Access

Without browser access, the repair agent's feedback loop for frontend features is:
1. Edit code → 2. Restart → 3. HTTP verifier (status code only) → 4. If fail, guess

With browser access:
1. Edit code → 2. Restart → 3. Open browser → 4. Screenshot → 5. See CSS/layout/render issues → 6. Fix precisely

The agent can see and diagnose visual problems that no HTTP status code reveals.

### 11.2 Integration: enableBrowserTools

The repair agent already uses `agentExecute.ts`. Playwright MCP is added when `enableBrowserTools: true`:

```typescript
// In agentExecute.ts — modified mcpServers construction:
const mcpServers: Record<string, unknown> = {};

if (mcpServerConfig) {
  mcpServers.demiurge = mcpServerConfig;
}

if (p.agentConfig.enableBrowserTools) {
  const headedBrowser = p.agentConfig.headedBrowser ?? false;
  const playwrightArgs = headedBrowser
    ? ['@playwright/mcp@latest']
    : ['@playwright/mcp@latest', '--headless'];

  mcpServers.playwright = {
    type: 'stdio',
    command: 'npx',
    args: playwrightArgs,
  };
}

// Use mcpServers in queryOptions (replaces current single-server logic)
```

### 11.3 Repair Prompt Addition

When `enableBrowserTools` is true, `AgentSystemPromptBuilder.appendToolDescriptions()` adds:

```
- **Browser tools (Playwright MCP)**: You have access to a full browser via Playwright.
  Use browser_navigate(url) to open pages, browser_snapshot() to read page structure,
  browser_take_screenshot() to capture visual state, and browser_click/fill/type to interact.
  After making code changes and restarting services, open the browser to visually verify
  your fix looks correct before calling verify_requirements().
```

### 11.4 When enableBrowserTools is Set

The Scala orchestrator sets `enableBrowserTools: true` when any requirement being repaired has:
- `verifierType == VerifierType.AgentBrowser`, OR
- `category == RequirementCategory.UiFlow`

```scala
// In RunOrchestrator, when building agent config for repair:
val hasFrontendRequirement = failedVerdicts.exists { v =>
  graph.nodes.exists(n =>
    n.requirementId == v.requirementId &&
    (n.category == RequirementCategory.UiFlow ||
     n.verifiers.exists(_.verifierType == VerifierType.AgentBrowser))
  )
}
val repairAgentConfig = agentConfig.copy(
  enableBrowserTools = hasFrontendRequirement,
  headedBrowser = browserConfig.headed,
)
```

---

## 12. Auth Handling

### 12.1 Strategy: Agent-Driven Login

The verification agent handles auth as part of its exploratory flow. If the feature description mentions login or the page redirects to a login page, the agent:

1. Detects the login form via `browser_snapshot`
2. Fills credentials (provided in the feature description or as a separate `authHint` field)
3. Submits and proceeds

### 12.2 Storage State (Future Enhancement)

For apps requiring complex auth (OAuth, SSO), a pre-configured Playwright storage state file can be passed:

```scala
// In AgentBrowserVerifier (future):
storageStatePath: Option[String] = None,
```

This is passed to Playwright MCP as `--storage-state <path>`. The existing `BootstrappingAuth` state in RunOrchestrator can be wired to produce this file when needed. **Deferred to Phase 2** — agent-driven login covers the common case.

---

## 13. Multi-Viewport Testing

### 13.1 Opt-In Design (Decision)

Multi-viewport is NOT tested by default. Configure per-requirement:

```yaml
# In requirements.yaml:
- id: login-page
  description: "The login page has a centered form..."
  verifiers:
    - type: agent-browser
      entry_url: "http://localhost:3000/login"
      viewports:                    # Optional — omit for default viewport only
        - { width: 1280, height: 720 }   # Desktop
        - { width: 375, height: 812 }    # Mobile
```

### 13.2 How It Works

When `viewports` is non-empty, the system prompt includes the viewport testing section (see §8.1). The agent:
1. Completes full verification at default viewport
2. Calls `browser_resize(width, height)` for each viewport
3. Re-checks layout, overflow, alignment at each size
4. Reports viewport-specific observations

This is a **single agent session** — the agent resizes the browser within the same session, not separate sessions per viewport. Cost impact is moderate (~30% more tokens for each additional viewport).

---

## 14. Artifact Collection

### 14.1 What to Capture

| Artifact | Source | Storage Path |
|---|---|---|
| Screenshots (PNG) | `mcp__playwright__browser_take_screenshot` | `{runId}/{phase}/{reqId}/screenshots/{label}_{ts}.png` |
| Accessibility trees | `mcp__playwright__browser_snapshot` | `{runId}/{phase}/{reqId}/a11y/{label}_{ts}.json` |
| Console logs | `mcp__playwright__browser_console_messages` | `{runId}/{phase}/{reqId}/console/console_{ts}.json` |
| Network requests | `mcp__playwright__browser_network_requests` | `{runId}/{phase}/{reqId}/network/network_{ts}.json` |
| Verdict JSON | Agent's final structured output | `{runId}/{phase}/{reqId}/verdict.json` |
| Agent transcript | SDK message stream | `{runId}/{phase}/{reqId}/transcript.json` |

Where `{phase}` is `pre`, `post`, or `repair`.

### 14.2 Collection via Message Stream

Reuses the existing message stream loop in `agentExecute.ts`. Add a `BrowserArtifactCollector` class:

```typescript
// New: worker/src/artifacts/browserArtifactCollector.ts

import { ArtifactWriter } from './writer';

export class BrowserArtifactCollector {
  private writer: ArtifactWriter;
  private reqId: string;
  private phase: string;  // "pre" | "post" | "repair"
  private screenshotCount = 0;

  constructor(writer: ArtifactWriter, reqId: string, phase: string) {
    this.writer = writer;
    this.reqId = reqId;
    this.phase = phase;
  }

  async onToolResult(toolName: string, result: unknown): Promise<void> {
    if (toolName === 'mcp__playwright__browser_take_screenshot') {
      this.screenshotCount++;
      // Playwright MCP returns screenshot as base64 image in tool result content
      // Extract and save via ArtifactWriter
      const label = `screenshot_${String(this.screenshotCount).padStart(3, '0')}`;
      // ... extract image data from result, call writer.writeScreenshot()
    }
    // Similar for browser_snapshot, browser_console_messages, etc.
  }

  async saveVerdict(verdict: object): Promise<void> {
    const relPath = `${this.writer['runId']}/${this.phase}/${this.reqId}/verdict.json`;
    await this.writer.writeArtifact(
      'BrowserVerdict', JSON.stringify(verdict, null, 2), relPath, 'application/json', 'verdict',
    );
  }

  async saveTranscript(messages: unknown[]): Promise<void> {
    const relPath = `${this.writer['runId']}/${this.phase}/${this.reqId}/transcript.json`;
    await this.writer.writeArtifact(
      'AgentTranscript', JSON.stringify(messages, null, 2), relPath, 'application/json', 'transcript',
    );
  }
}
```

### 14.3 Reusing Existing ArtifactWriter

`worker/src/artifacts/writer.ts` already provides `writeScreenshot()`, `writeConsoleLog()`, `writeNetworkSummary()`, `writeDomSnapshot()`, `writeAccessibilitySnapshot()`. `BrowserArtifactCollector` wraps these with the phase/reqId path structure.

---

## 15. File-by-File Change Specification

### 15.1 Scala Changes

#### `modules/core-model/src/main/scala/demiurge/model/verifier_types.scala`
**Add** (after `RegressionVerifierSpec`):
```scala
case class AgentBrowserVerifierSpec(
  entryUrl:            String,
  featureDescription:  String,
  viewports:           List[Viewport]       = Nil,
  tasteSensitivity:    TasteSensitivity     = TasteSensitivity.Normal,
  tasteTriggersRepair: Boolean              = true,
  maxBudgetUsd:        Double               = 50.0,
)
case class Viewport(width: Int, height: Int)
sealed trait TasteSensitivity
object TasteSensitivity {
  case object Strict  extends TasteSensitivity
  case object Normal  extends TasteSensitivity
  case object Lenient extends TasteSensitivity
  case object Off     extends TasteSensitivity
}
```
**Modify** `VerifierSpec`: add field `agentBrowserSpec: Option[AgentBrowserVerifierSpec] = None`

#### `modules/core-model/src/main/scala/demiurge/model/enums.scala`
**Add** to `VerifierType`: `case object AgentBrowser extends VerifierType`

#### `modules/verification-engine/src/main/scala/demiurge/verification/Verifier.scala`
**Add** (after `BrowserFlowVerifier`):
```scala
case class AgentBrowserVerifier(
  id:                  String,
  requirementId:       String,
  entryUrl:            String,
  featureDescription:  String,
  timeout:             Duration,
  maxRetries:          Int,
  maxBudgetUsd:        Double           = 50.0,
  maxTurns:            Int              = 30,
  viewports:           List[Viewport]   = Nil,
  tasteSensitivity:    TasteSensitivity = TasteSensitivity.Normal,
  tasteTriggersRepair: Boolean          = true,
  beforeScreenshots:   List[String]     = Nil,
) extends Verifier
```

#### `modules/verification-engine/src/main/scala/demiurge/verification/VerifierGenerator.scala`
**Add** case in `specToVerifier()`:
```scala
case VerifierType.AgentBrowser =>
  val spec = spec.agentBrowserSpec.getOrElse(
    throw new IllegalStateException(s"AgentBrowser verifier ${spec.verifierId} missing agentBrowserSpec")
  )
  AgentBrowserVerifier(
    id = spec.verifierId,
    requirementId = spec.requirementId,
    entryUrl = spec.entryUrl,
    featureDescription = spec.featureDescription,
    timeout = spec.timeout,
    maxRetries = spec.maxRetries,
    maxBudgetUsd = spec.maxBudgetUsd,
    viewports = spec.viewports,
    tasteSensitivity = spec.tasteSensitivity,
    tasteTriggersRepair = spec.tasteTriggersRepair,
  )
```

#### `modules/verification-engine/src/main/scala/demiurge/verification/VerifierExecutor.scala`
**Add** case in `executeOnce()` (alongside existing `BrowserFlowVerifier` case):
```scala
case _: AgentBrowserVerifier =>
  VerifierOutcome.Error("AgentBrowserVerifier must be executed via AgentBrowserExecutor")
```

#### `modules/verification-engine/src/main/scala/demiurge/verification/VerificationEngine.scala`
**Modify** `executeSingleVerifier()` — add `AgentBrowserVerifier` match case in the `verifier match`:
```scala
case abv: AgentBrowserVerifier =>
  // Delegate to agentBrowserExecutor (new parameter, similar to browserExecutor)
  agentBrowserExecutor match {
    case Some(executor) =>
      val result = executor.execute(abv)
      SingleResult(result.outcome, result.observations, result.artifactRefs)
    case None =>
      SingleResult(VerifierOutcome.Error("No agent browser executor available"), Nil, Nil)
  }
```
**Add** new parameter to `runVerification()`:
```scala
agentBrowserExecutor: Option[AgentBrowserExecutor] = None,
```
**Add** new trait:
```scala
trait AgentBrowserExecutor {
  def execute(verifier: AgentBrowserVerifier): BrowserVerifierResult
}
```

#### New file: `modules/agent-backend/src/main/scala/demiurge/agent/BrowserVerificationVerdict.scala`
Full content as specified in §7.3.

#### New file: `modules/agent-backend/src/main/scala/demiurge/agent/BrowserVerificationPromptBuilder.scala`
```scala
object BrowserVerificationPromptBuilder {
  def buildSystemPrompt(
    featureDescription: String,
    entryUrl:           String,
    viewports:          List[Viewport],
    beforeScreenshots:  List[ScreenshotRef],
    tasteSensitivity:   TasteSensitivity,
  ): String = {
    // Builds exact prompt from §8.1 template with conditional sections
  }

  def buildUserPrompt(beforeScreenshotPaths: List[String]): String = {
    // Simple text trigger; before-screenshots attached as images in agentExecute.ts
    if (beforeScreenshotPaths.nonEmpty)
      s"Verify the feature. ${beforeScreenshotPaths.size} before-implementation screenshots are attached."
    else
      "Verify the feature described in your system prompt. Navigate to the entry URL and begin."
  }
}
```

#### New file: `modules/agent-backend/src/main/scala/demiurge/agent/AgentBrowserExecutorImpl.scala`
```scala
class AgentBrowserExecutorImpl(
  workerManager: WorkerProcessManager,
  repoRoot:      Path,
  agentConfig:   AgentConfig,
) extends VerificationEngine.AgentBrowserExecutor {

  override def execute(verifier: AgentBrowserVerifier): BrowserVerifierResult = {
    val systemPrompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      verifier.featureDescription, verifier.entryUrl,
      verifier.viewports, /* ... */ verifier.tasteSensitivity,
    )
    val userPrompt = BrowserVerificationPromptBuilder.buildUserPrompt(verifier.beforeScreenshots)

    val params = Json.obj(
      "runId"              -> verifier.requirementId.asJson,  // or actual runId
      "mode"               -> "verification".asJson,
      "systemPrompt"       -> systemPrompt.asJson,
      "userPrompt"         -> userPrompt.asJson,
      "worktreePath"       -> repoRoot.toAbsolutePath.toString.asJson,
      "repoRoot"           -> repoRoot.toAbsolutePath.toString.asJson,
      "serviceIds"         -> Json.arr(),
      "beforeScreenshots"  -> verifier.beforeScreenshots.asJson,
      "agentConfig"        -> Json.obj(
        "model"              -> agentConfig.model.asJson,
        "maxTurns"           -> Some(verifier.maxTurns).asJson,
        "maxBudgetUsd"       -> Some(verifier.maxBudgetUsd).asJson,
        "timeoutMs"          -> verifier.timeout.toMillis.asJson,
        "enableMcpTools"     -> false.asJson,       // no Demiurge MCP for verification
        "enableBrowserTools" -> true.asJson,
        "headedBrowser"      -> agentConfig.headedBrowser.asJson,
      ),
    )

    val rpcTimeoutMs = verifier.timeout.toMillis + 30000
    val rpcResult = workerManager.sendRawRequest("agent/execute", params, rpcTimeoutMs)

    rpcResult match {
      case Left(err) =>
        BrowserVerifierResult(VerifierOutcome.Error(s"agent/execute failed: $err"), Nil, Nil)
      case Right(json) =>
        parseVerificationResult(json, verifier)
    }
  }

  private def parseVerificationResult(json: Json, verifier: AgentBrowserVerifier): BrowserVerifierResult = {
    // Parse verdict from worker response
    // Map PASS/FAIL/TASTE_ISSUE to VerifierOutcome
    // Apply tasteTriggersRepair + tasteSensitivity logic (§10.3)
    // Map observations to demiurge.model.Observation list
    // Return BrowserVerifierResult
  }
}
```

#### `modules/agent-backend/src/main/scala/demiurge/agent/AgentConfig.scala`
**Add** fields:
```scala
enableBrowserTools: Boolean = false,
headedBrowser: Boolean = false,
```

#### `modules/orchestrator/src/main/scala/demiurge/orchestrator/RunOrchestrator.scala`
**Modify** `execute()` signature — add `agentBrowserExecutor` parameter.
**Add** logic to build `AgentBrowserExecutorImpl` when agent backend + worker manager are available.
**Add** `enableBrowserTools` logic for repair config (§11.4).
**Pass** `agentBrowserExecutor` to `VerificationEngine.runVerification()`.

### 15.2 TypeScript Changes

#### `worker/src/methods/agentExecute.ts`

**Modify** `AgentExecuteParams` interface — add:
```typescript
mode?: 'repair' | 'verification';
enableBrowserTools?: boolean;
headedBrowser?: boolean;
featureDescription?: string;
entryUrl?: string;
beforeScreenshots?: string[];  // file paths to before-implementation screenshots
viewports?: Array<{ width: number; height: number }>;
```

**Modify** `handleAgentExecute()` — replace single-server mcpServers logic:
```typescript
// Build mcpServers dynamically based on mode and config
const mcpServers: Record<string, unknown> = {};

// Demiurge MCP tools: only for repair mode
if (p.mode !== 'verification' && p.agentConfig.enableMcpTools && mcpServerConfig) {
  mcpServers.demiurge = mcpServerConfig;
}

// Playwright MCP: for verification mode OR repair with browser tools
if (p.mode === 'verification' || p.agentConfig.enableBrowserTools) {
  const headedBrowser = p.agentConfig.headedBrowser ?? false;
  const playwrightArgs = headedBrowser
    ? ['@playwright/mcp@latest']
    : ['@playwright/mcp@latest', '--headless'];
  mcpServers.playwright = {
    type: 'stdio',
    command: 'npx',
    args: playwrightArgs,
  };
}
```

**Modify** user prompt construction for verification mode (§8.2) — attach before-screenshots as base64 images.

**Add** verdict parsing after conversation completes (§8.3).

**Add** `BrowserArtifactCollector` integration in message stream loop.

**Return** extended result with `verificationVerdict` field when mode is verification.

#### New file: `worker/src/artifacts/browserArtifactCollector.ts`
Content as specified in §14.2.

#### `worker/package.json`
**Add** dependency: `"@playwright/mcp": "latest"`

### 15.3 Test Changes

#### New: `modules/verification-engine/src/test/scala/demiurge/verification/AgentBrowserVerifierSuite.scala`
- Test `VerifierGenerator` produces `AgentBrowserVerifier` from `AgentBrowserVerifierSpec`
- Test `VerifierExecutor.executeOnce` returns error for `AgentBrowserVerifier` (must use executor)
- Test taste sensitivity filtering logic

#### New: `modules/agent-backend/src/test/scala/demiurge/agent/BrowserVerificationPromptBuilderSuite.scala`
- Test prompt generation with/without before-screenshots
- Test prompt generation with/without viewports
- Test all taste sensitivity variants produce correct prompt sections

#### New: `worker/test/browserVerification.spec.ts`
- Test verdict JSON parsing (valid, malformed, missing)
- Test `BrowserArtifactCollector` artifact path construction
- Test mcpServers construction for verification vs repair modes

---

## 16. Budget, Timeouts, and Limits

### 16.1 Per-Requirement Defaults

| Parameter | Default | Rationale |
|---|---|---|
| `timeout` | 120s | Complex UIs with multi-step interactions need time |
| `maxBudgetUsd` | $50.00 | Vision + multi-turn agent sessions are token-heavy |
| `maxTurns` | 30 | Navigate + 10 interactions + 10 screenshots + judgment |
| `maxScreenshots` | 20 | Prevent runaway screenshot loops (soft limit in prompt) |

### 16.2 Token Cost Estimation

Per verification run:
- System prompt: ~2,000 tokens
- Accessibility snapshots (2-3): ~3,000-5,000 tokens each
- Screenshots (5-10): ~1,500-3,000 tokens each (vision)
- Before-screenshots (1-3): ~1,500-3,000 tokens each
- Agent reasoning: ~2,000-5,000 tokens
- **Estimated total: 30,000-80,000 tokens per requirement (~$0.50-$2.00)**

The $50 budget provides ample headroom. Multi-viewport adds ~30% per viewport.

### 16.3 Cost Controls

- `maxTurns` prevents infinite loops (Agent SDK native).
- `maxBudgetUsd` caps spending (Agent SDK native).
- `timeout` provides wall-clock ceiling.
- Screenshot soft limit in prompt (20). Hard limit could be added via `preToolUse` hook if needed.

---

## 17. Configuration Surface

### 17.1 demiurge.yaml

```yaml
browser:
  headed: false                    # Run Playwright in headed mode for debugging
  taste_triggers_repair: true      # Default: TASTE_ISSUE triggers repair
  taste_sensitivity: normal        # strict | normal | lenient | off
  max_budget_usd: 50.0            # Per-requirement budget
  timeout_seconds: 120             # Per-requirement timeout
```

### 17.2 Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DEMIURGE_BROWSER_HEADED` | `false` | Override headed mode |
| `DEMIURGE_BROWSER_BUDGET_USD` | `50.0` | Override per-requirement budget |
| `DEMIURGE_BROWSER_TIMEOUT_S` | `120` | Override per-requirement timeout |

### 17.3 Per-Requirement Configuration (requirements.yaml)

```yaml
- id: login-page
  description: "The login page has a centered form with email and password fields..."
  category: ui-flow
  verifiers:
    - type: agent-browser
      entry_url: "http://localhost:3000/login"
      feature_description: "The login page has a centered form with email and password fields, a 'Sign In' button, and a 'Forgot Password?' link. Submitting valid credentials redirects to the dashboard. The form shows inline validation errors for empty fields."
      taste_sensitivity: strict       # Override global setting
      taste_triggers_repair: true
      max_budget_usd: 25.0            # Override for simpler features
      viewports:                       # Optional
        - { width: 375, height: 812 }  # Mobile test
```

---

## 18. Implementation Plan

### Phase 1: Core Verification Pipeline (~500 LOC Scala + ~300 LOC TS)

1. **core-model:** Add `AgentBrowserVerifierSpec`, `Viewport`, `TasteSensitivity`, `VerifierType.AgentBrowser`, extend `VerifierSpec`
2. **verification-engine:** Add `AgentBrowserVerifier` to `Verifier.scala`, `VerifierGenerator`, `VerifierExecutor`, `VerificationEngine` (new `AgentBrowserExecutor` trait + parameter threading)
3. **agent-backend:** New `BrowserVerificationVerdict.scala`, `BrowserVerificationPromptBuilder.scala`, `AgentBrowserExecutorImpl.scala`. Extend `AgentConfig` with `enableBrowserTools` + `headedBrowser`
4. **worker/agentExecute.ts:** Add `mode`, `enableBrowserTools`, `headedBrowser`, `beforeScreenshots`, `viewports` to params. Dynamic mcpServers construction. Verdict parsing. Before-screenshot image attachment.
5. **worker/package.json:** Add `@playwright/mcp` dependency
6. **Tests:** `AgentBrowserVerifierSuite`, `BrowserVerificationPromptBuilderSuite`, `worker/test/browserVerification.spec.ts`

### Phase 2: Artifact Collection (~200 LOC TS)

1. **New:** `worker/src/artifacts/browserArtifactCollector.ts`
2. **Modify:** `agentExecute.ts` message loop to use `BrowserArtifactCollector`
3. **Save:** Screenshots, accessibility trees, console logs, network requests, verdict JSON, transcript

### Phase 3: Repair Agent Browser Integration (~150 LOC)

1. **Modify:** `AgentSystemPromptBuilder.appendToolDescriptions()` — add browser tool descriptions when `enableBrowserTools`
2. **Modify:** `RunOrchestrator` — detect frontend requirements, set `enableBrowserTools` on repair agent config
3. **Modify:** `agentExecute.ts` — already handles `enableBrowserTools` from Phase 1

### Phase 4: Orchestrator Wiring & Before/After (~200 LOC Scala)

1. **Modify:** `RunOrchestrator.execute()` — build `AgentBrowserExecutorImpl`, pass to `VerificationEngine`
2. **Add:** Before-screenshot collection: after pre-implementation verification, collect screenshot paths from verdict artifacts, pass as `beforeScreenshots` to post-implementation `AgentBrowserVerifier`
3. **Add:** Taste → repair routing: when verdict is `TASTE_ISSUE` and `tasteTriggersRepair`, treat as `FAIL` for repair loop entry
4. **Modify:** `RunCommand.scala` / `OrchestrationRunner` — wire new executor through CLI entry points

**Total estimated: ~850 LOC Scala + ~500 LOC TypeScript**

---

## 19. Resolved Questions

All open questions from v1 have been resolved:

| # | Question | Decision | Rationale |
|---|---|---|---|
| 1 | Playwright MCP version | `@latest` — no pinning | Actively maintained by Microsoft; latest has best tools and fixes |
| 2 | Headed debug mode | Yes, configurable via `browser.headed` in yaml or `DEMIURGE_BROWSER_HEADED` env | Essential for development debugging |
| 3 | Auth handling | Agent handles login in-flow; storage state deferred to Phase 2 | Simplest approach covers common case |
| 4 | Multi-viewport | Opt-in per requirement, not default | Cost multiplication; default viewport covers most cases |
| 5 | TASTE_ISSUE → repair | Yes by default (`tasteTriggersRepair: true`), configurable with `tasteSensitivity` | Visual quality matters; tunable per requirement |
| 6 | Before/after screenshots | Fed to post-implementation agent as base64 images in user prompt | Enables explicit comparison; catches regressions |
| 7 | Playwright MCP lifecycle | One stdio subprocess per agent session | Cleanest — fresh browser state, automatic cleanup |
