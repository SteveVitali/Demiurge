package demiurge.agent

import munit.FunSuite
import io.circe.parser
import demiurge.model.{Viewport, TasteSensitivity}

class BrowserVerificationPromptBuilderSuite extends FunSuite {

  test("system prompt includes feature description") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Login form with email and password fields",
      entryUrl = "http://localhost:3000/login",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(prompt.contains("Login form with email and password fields"))
    assert(prompt.contains("<feature_description>"))
    assert(prompt.contains("</feature_description>"))
  }

  test("system prompt includes entry URL") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:8080/dashboard",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(prompt.contains("http://localhost:8080/dashboard"))
    assert(prompt.contains("Navigate to:"))
  }

  test("system prompt includes viewport testing section when viewports provided") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = List(Viewport(375, 667), Viewport(1920, 1080)),
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(prompt.contains("## Viewport Testing"))
    assert(prompt.contains("375x667"))
    assert(prompt.contains("1920x1080"))
    assert(prompt.contains("browser_resize"))
  }

  test("system prompt omits viewport section when no viewports") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(!prompt.contains("## Viewport Testing"))
  }

  test("system prompt includes before-screenshots section when provided") {
    val beforeScreenshots = List(
      ScreenshotRef("ss-001.png", "Homepage before implementation", "before"),
      ScreenshotRef("ss-002.png", "Dashboard before implementation", "before"),
    )
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = beforeScreenshots,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(prompt.contains("## Before-Implementation Reference"))
    assert(prompt.contains("Homepage before implementation"))
    assert(prompt.contains("Dashboard before implementation"))
    assert(prompt.contains("baseline"))
  }

  test("system prompt omits before-screenshots section when empty") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(!prompt.contains("## Before-Implementation Reference"))
  }

  test("system prompt includes taste assessment for Normal sensitivity") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(prompt.contains("Visual Taste Assessment"))
    assert(prompt.contains("Contrast"))
    assert(prompt.contains("Sizing"))
    assert(prompt.contains("Alignment"))
    assert(prompt.contains("Typography"))
  }

  test("system prompt includes taste assessment for Strict sensitivity") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Strict,
    )
    assert(prompt.contains("Visual Taste Assessment"))
  }

  test("system prompt omits taste assessment for Off sensitivity") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Off,
    )
    assert(!prompt.contains("Visual Taste Assessment"))
  }

  test("system prompt includes verdict JSON template") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(prompt.contains("PASS | FAIL | TASTE_ISSUE"))
    assert(prompt.contains("\"verdict\""))
    assert(prompt.contains("\"confidence\""))
    assert(prompt.contains("\"featureSatisfied\""))
    assert(prompt.contains("\"observations\""))
    assert(prompt.contains("\"tasteIssues\""))
    assert(prompt.contains("\"summary\""))
  }

  test("system prompt includes verification protocol steps") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(prompt.contains("Initial State Capture"))
    assert(prompt.contains("Systematic Feature Exploration"))
    assert(prompt.contains("browser_navigate"))
    assert(prompt.contains("browser_snapshot"))
  }

  test("system prompt includes rules section") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(prompt.contains("## Rules"))
    assert(prompt.contains("Do NOT try to access source code"))
    assert(prompt.contains("at least 3 screenshots"))
  }

  test("system prompt mentions QA engineer role") {
    val prompt = BrowserVerificationPromptBuilder.buildSystemPrompt(
      featureDescription = "Test feature",
      entryUrl = "http://localhost:3000",
      viewports = Nil,
      beforeScreenshots = Nil,
      tasteSensitivity = TasteSensitivity.Normal,
    )
    assert(prompt.contains("QA engineer"))
    assert(prompt.contains("black-box tester"))
  }

  test("user prompt mentions before-screenshots when provided") {
    val userPrompt = BrowserVerificationPromptBuilder.buildUserPrompt(
      List("/tmp/ss-001.png", "/tmp/ss-002.png"),
    )
    assert(userPrompt.contains("2 before-implementation screenshot(s)"))
  }

  test("user prompt works without before-screenshots") {
    val userPrompt = BrowserVerificationPromptBuilder.buildUserPrompt(Nil)
    assert(userPrompt.contains("Verify the feature"))
    assert(!userPrompt.contains("before-implementation"))
  }

  test("taste sensitivity filtering — Strict keeps all issues") {
    val issues = List(
      TasteIssue("error", "Bad contrast", None, None),
      TasteIssue("warning", "Small text", None, None),
      TasteIssue("info", "Minor spacing", None, None),
    )
    val filtered = AgentBrowserExecutorImpl.filterByTasteSensitivity(issues, TasteSensitivity.Strict)
    assertEquals(filtered.size, 3)
  }

  test("taste sensitivity filtering — Normal keeps error and warning") {
    val issues = List(
      TasteIssue("error", "Bad contrast", None, None),
      TasteIssue("warning", "Small text", None, None),
      TasteIssue("info", "Minor spacing", None, None),
    )
    val filtered = AgentBrowserExecutorImpl.filterByTasteSensitivity(issues, TasteSensitivity.Normal)
    assertEquals(filtered.size, 2)
    assert(filtered.forall(i => i.severity == "error" || i.severity == "warning"))
  }

  test("taste sensitivity filtering — Lenient keeps only error") {
    val issues = List(
      TasteIssue("error", "Bad contrast", None, None),
      TasteIssue("warning", "Small text", None, None),
      TasteIssue("info", "Minor spacing", None, None),
    )
    val filtered = AgentBrowserExecutorImpl.filterByTasteSensitivity(issues, TasteSensitivity.Lenient)
    assertEquals(filtered.size, 1)
    assertEquals(filtered.head.severity, "error")
  }

  test("taste sensitivity filtering — Off returns empty") {
    val issues = List(
      TasteIssue("error", "Bad contrast", None, None),
      TasteIssue("warning", "Small text", None, None),
    )
    val filtered = AgentBrowserExecutorImpl.filterByTasteSensitivity(issues, TasteSensitivity.Off)
    assertEquals(filtered.size, 0)
  }

  test("BrowserVerdictStatus.fromString parses valid values") {
    assertEquals(BrowserVerdictStatus.fromString("PASS"), Some(BrowserVerdictStatus.Pass))
    assertEquals(BrowserVerdictStatus.fromString("FAIL"), Some(BrowserVerdictStatus.Fail))
    assertEquals(BrowserVerdictStatus.fromString("TASTE_ISSUE"), Some(BrowserVerdictStatus.TasteIssue))
    assertEquals(BrowserVerdictStatus.fromString("pass"), Some(BrowserVerdictStatus.Pass))
    assertEquals(BrowserVerdictStatus.fromString("fail"), Some(BrowserVerdictStatus.Fail))
  }

  test("BrowserVerdictStatus.fromString returns None for invalid values") {
    assertEquals(BrowserVerdictStatus.fromString(""), None)
    assertEquals(BrowserVerdictStatus.fromString("UNKNOWN"), None)
    assertEquals(BrowserVerdictStatus.fromString("partial"), None)
  }

  // --- Verdict JSON parsing tests (AgentBrowserExecutorImpl companion object) ---

  test("parseVerdictJson parses valid PASS verdict") {
    val json = parser.parse("""
      {
        "verdict": "PASS",
        "confidence": 0.95,
        "featureSatisfied": true,
        "observations": [{"aspect": "login", "status": "pass", "detail": "works"}],
        "tasteIssues": [],
        "summary": "All good"
      }
    """).getOrElse(io.circe.Json.Null)
    val result = AgentBrowserExecutorImpl.parseVerdictJson(json.hcursor)
    assert(result.isDefined)
    assertEquals(result.get.verdict, BrowserVerdictStatus.Pass)
    assertEquals(result.get.confidence, 0.95)
    assert(result.get.featureSatisfied)
    assertEquals(result.get.observations.size, 1)
    assertEquals(result.get.observations.head.aspect, "login")
    assertEquals(result.get.summary, "All good")
  }

  test("parseVerdictJson parses FAIL verdict with taste issues") {
    val json = parser.parse("""
      {
        "verdict": "TASTE_ISSUE",
        "confidence": 0.7,
        "featureSatisfied": true,
        "observations": [],
        "tasteIssues": [{"severity": "warning", "issue": "Low contrast", "element": "h1"}],
        "summary": "Works but ugly"
      }
    """).getOrElse(io.circe.Json.Null)
    val result = AgentBrowserExecutorImpl.parseVerdictJson(json.hcursor)
    assert(result.isDefined)
    assertEquals(result.get.verdict, BrowserVerdictStatus.TasteIssue)
    assertEquals(result.get.tasteIssues.size, 1)
    assertEquals(result.get.tasteIssues.head.severity, "warning")
    assertEquals(result.get.tasteIssues.head.element, Some("h1"))
  }

  test("parseVerdictJson returns None for missing verdict field") {
    val json = parser.parse("""{"confidence": 0.5}""").getOrElse(io.circe.Json.Null)
    val result = AgentBrowserExecutorImpl.parseVerdictJson(json.hcursor)
    assert(result.isEmpty)
  }

  test("parseVerdictJson returns None for unknown verdict value") {
    val json = parser.parse("""{"verdict": "UNKNOWN"}""").getOrElse(io.circe.Json.Null)
    val result = AgentBrowserExecutorImpl.parseVerdictJson(json.hcursor)
    assert(result.isEmpty)
  }

  test("parseVerdictJson provides defaults for missing optional fields") {
    val json = parser.parse("""{"verdict": "PASS"}""").getOrElse(io.circe.Json.Null)
    val result = AgentBrowserExecutorImpl.parseVerdictJson(json.hcursor)
    assert(result.isDefined)
    assertEquals(result.get.confidence, 0.5)
    assert(!result.get.featureSatisfied)  // default false
    assert(result.get.observations.isEmpty)
    assert(result.get.tasteIssues.isEmpty)
    assertEquals(result.get.summary, "")
  }

  test("parseVerdictFromText extracts verdict from fenced JSON block") {
    val text = """Here is my verdict:
      |```json
      |{"verdict": "PASS", "confidence": 0.9, "summary": "OK"}
      |```
      |Done.""".stripMargin
    val result = AgentBrowserExecutorImpl.parseVerdictFromText(text)
    assert(result.isDefined)
    assertEquals(result.get.verdict, BrowserVerdictStatus.Pass)
    assertEquals(result.get.confidence, 0.9)
  }

  test("parseVerdictFromText returns None for empty text") {
    assert(AgentBrowserExecutorImpl.parseVerdictFromText("").isEmpty)
    assert(AgentBrowserExecutorImpl.parseVerdictFromText(null).isEmpty)
  }

  test("parseVerdictFromText returns None for text without verdict JSON") {
    assert(AgentBrowserExecutorImpl.parseVerdictFromText("Just some regular text").isEmpty)
  }
}
