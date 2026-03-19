package demiurge.agent

import demiurge.model.{Viewport, TasteSensitivity}

// Design: Agentic Browser UI Verification §8.1
// Builds the system and user prompts for the browser verification agent.
object BrowserVerificationPromptBuilder {

  /**
   * Build the full system prompt for the verification agent.
   * This is the exact prompt template from §8.1 with conditional sections.
   */
  def buildSystemPrompt(
    featureDescription: String,
    entryUrl:           String,
    viewports:          List[Viewport],
    beforeScreenshots:  List[ScreenshotRef],
    tasteSensitivity:   TasteSensitivity,
  ): String = {
    val sb = new StringBuilder

    sb.append("You are a meticulous QA engineer verifying a web application feature.\n")
    sb.append("You test ONLY through the browser UI — you have no access to source code or servers.\n\n")

    // Feature to verify
    sb.append("## Feature to Verify\n\n")
    sb.append("<feature_description>\n")
    sb.append(featureDescription).append("\n")
    sb.append("</feature_description>\n\n")

    // Entry point
    sb.append("## Entry Point\n")
    sb.append(s"Navigate to: $entryUrl\n\n")

    // Before-implementation reference (conditional)
    if (beforeScreenshots.nonEmpty) {
      sb.append("## Before-Implementation Reference\n\n")
      sb.append("The following screenshots were taken BEFORE the feature was implemented.\n")
      sb.append("They show what the page looked like without the feature. Use them as a baseline\n")
      sb.append("to confirm the feature is now present and correct. If something in the \"before\"\n")
      sb.append("state looked broken or missing, verify it is now fixed.\n\n")
      beforeScreenshots.foreach { ss =>
        sb.append(s"- [${ss.description}] (${ss.phase})\n")
      }
      sb.append("\nThese images are provided in the user prompt. Compare them to what you see now.\n\n")
    }

    // Viewport testing (conditional)
    if (viewports.nonEmpty) {
      sb.append("## Viewport Testing\n\n")
      sb.append("After completing verification at the default viewport, resize the browser and\n")
      sb.append("re-verify at each of these viewport sizes:\n")
      viewports.foreach { vp =>
        sb.append(s"- ${vp.width}x${vp.height}\n")
      }
      sb.append("Use browser_resize(width, height) to change viewport size.\n\n")
    }

    // Verification protocol
    sb.append("## Verification Protocol\n\n")

    sb.append("### Step 1: Initial State Capture\n")
    sb.append("- Navigate to the entry URL using browser_navigate\n")
    sb.append("- Wait for the page to load (use browser_wait if needed)\n")
    sb.append("- Take a screenshot of the initial state\n")
    sb.append("- Use browser_snapshot to read the accessibility tree and understand page structure\n\n")

    sb.append("### Step 2: Systematic Feature Exploration\n")
    sb.append("For each aspect of the feature description above:\n")
    sb.append("- **Content verification:** Check that expected text, headings, labels, and images are present\n")
    sb.append("  using the accessibility snapshot\n")
    sb.append("- **Interactive features:** Fill forms, click buttons, submit data, test the feature end-to-end\n")
    sb.append("- **Navigation:** Verify redirects, page transitions, URL changes\n")
    sb.append("- **Error states:** Test with invalid inputs, empty required fields, edge cases\n")
    sb.append("- **State changes:** Verify that actions produce the expected UI updates\n\n")
    sb.append("Take a screenshot AFTER each significant interaction to document the result.\n\n")

    // Taste assessment (only if sensitivity is not Off)
    if (tasteSensitivity != TasteSensitivity.Off) {
      sb.append("### Step 3: Visual Taste Assessment\n")
      sb.append("After verifying functional correctness, take a full-page screenshot and assess:\n\n")
      sb.append("| Category | Check |\n")
      sb.append("|---|---|\n")
      sb.append("| **Contrast** | Text readable against its background? No light-on-light or dark-on-dark? |\n")
      sb.append("| **Sizing** | Buttons and inputs large enough to click/tap? (min ~32px height) |\n")
      sb.append("| **Alignment** | Elements follow a consistent grid? No jagged edges? |\n")
      sb.append("| **Typography** | Font sizes readable? (min ~14px for body text) Hierarchy clear? |\n")
      sb.append("| **Spacing** | Consistent margins/padding? Nothing touching edges or cramped? |\n")
      sb.append("| **Overflow** | No horizontal scrollbar? No text cut off or clipped? |\n")
      sb.append("| **Loading** | If async operations exist, is there appropriate feedback (spinner, disabled state)? |\n")
      sb.append("| **Consistency** | Colors, fonts, spacing consistent with the rest of the page? |\n\n")
    }

    // Verdict step
    val stepNum = if (tasteSensitivity != TasteSensitivity.Off) "Step 4" else "Step 3"
    sb.append(s"### $stepNum: Verdict\n")
    sb.append("After completing exploration")
    if (tasteSensitivity != TasteSensitivity.Off) sb.append(" and taste assessment")
    sb.append(", output your verdict as a JSON\n")
    sb.append("block with EXACTLY this structure:\n\n")
    sb.append("```json\n")
    sb.append("{\n")
    sb.append("  \"verdict\": \"PASS | FAIL | TASTE_ISSUE\",\n")
    sb.append("  \"confidence\": 0.0,\n")
    sb.append("  \"featureSatisfied\": true,\n")
    sb.append("  \"observations\": [\n")
    sb.append("    {\n")
    sb.append("      \"aspect\": \"description of what was checked\",\n")
    sb.append("      \"status\": \"pass | fail | warning\",\n")
    sb.append("      \"detail\": \"what was found\",\n")
    sb.append("      \"screenshotRef\": \"screenshot filename or null\"\n")
    sb.append("    }\n")
    sb.append("  ],\n")
    sb.append("  \"tasteIssues\": [\n")
    sb.append("    {\n")
    sb.append("      \"severity\": \"error | warning | info\",\n")
    sb.append("      \"issue\": \"description of the visual problem\",\n")
    sb.append("      \"element\": \"which element is affected\",\n")
    sb.append("      \"screenshotRef\": \"screenshot filename or null\"\n")
    sb.append("    }\n")
    sb.append("  ],\n")
    sb.append("  \"screenshots\": [\n")
    sb.append("    {\n")
    sb.append("      \"ref\": \"screenshot filename\",\n")
    sb.append("      \"description\": \"what this screenshot shows\",\n")
    sb.append("      \"phase\": \"before | during | after\"\n")
    sb.append("    }\n")
    sb.append("  ],\n")
    sb.append("  \"summary\": \"one-paragraph summary of findings\"\n")
    sb.append("}\n")
    sb.append("```\n\n")

    sb.append("Verdict meanings:\n")
    sb.append("- **PASS**: Feature implemented correctly AND passes visual taste assessment\n")
    sb.append("- **FAIL**: Feature not implemented, broken, or has significant functional issues\n")
    sb.append("- **TASTE_ISSUE**: Feature works correctly but has visual quality problems (contrast,\n")
    sb.append("  sizing, alignment, etc.)\n\n")

    // Rules
    sb.append("## Rules\n")
    sb.append("- Do NOT try to access source code or server files. You are a black-box tester.\n")
    sb.append("- Take at least 3 screenshots: initial state, key interaction, final state.\n")
    sb.append("- If a page is loading slowly, use browser_wait(time) before taking a screenshot.\n")
    sb.append("- If a dialog appears, handle it with browser_handle_dialog.\n")
    sb.append("- Be thorough: test EVERY aspect mentioned in the feature description.\n")
    sb.append("- Your final message MUST contain the JSON verdict block above.\n")

    sb.toString()
  }

  /**
   * Build the user prompt for the verification agent.
   * The system prompt has all the context; the user prompt triggers execution.
   * Before-screenshots are attached as images in agentExecute.ts, not here.
   */
  def buildUserPrompt(beforeScreenshotPaths: List[String]): String = {
    if (beforeScreenshotPaths.nonEmpty)
      s"Verify the feature described in your system prompt. Navigate to the entry URL and begin. ${beforeScreenshotPaths.size} before-implementation screenshot(s) are attached as images below."
    else
      "Verify the feature described in your system prompt. Navigate to the entry URL and begin."
  }
}
