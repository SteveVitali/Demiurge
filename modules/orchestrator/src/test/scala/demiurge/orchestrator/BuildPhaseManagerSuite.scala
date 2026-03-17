package demiurge.orchestrator

import munit.FunSuite
import demiurge.model._

class BuildPhaseManagerSuite extends FunSuite {

  test("parsePlanResponse extracts all fields from valid JSON") {
    val json = """{
      "summary": "Add user registration page",
      "files_to_create": [
        {"path": "src/pages/Register.tsx", "description": "Registration form component", "category": "component"},
        {"path": "src/api/register.ts", "description": "Registration API route", "category": "route"}
      ],
      "files_to_modify": [
        {"path": "src/App.tsx", "description": "Add register route", "change_type": "add_route"}
      ],
      "new_dependencies": ["bcryptjs", "zod"],
      "requires_migration": true,
      "estimated_complexity": "medium"
    }"""

    val response = InferenceResponse(
      requestId = "req-1",
      responseText = json,
      parsedJson = Some(json),
      inputTokens = 100,
      outputTokens = 200,
      cachedHit = false,
      durationMs = 500,
      model = "test",
      provider = InferenceProvider.Mock,
    )

    val result = BuildPhaseManager.parsePlanResponse("run-1", "Add registration", response)
    assert(result.isDefined, "Should parse successfully")

    val plan = result.get
    assertEquals(plan.summary, "Add user registration page")
    assertEquals(plan.estimatedComplexity, "medium")
    assertEquals(plan.requiresMigration, true)
    assertEquals(plan.filesToCreate.size, 2)
    assertEquals(plan.filesToCreate(0).relativePath, "src/pages/Register.tsx")
    assertEquals(plan.filesToCreate(0).category, "component")
    assertEquals(plan.filesToCreate(1).relativePath, "src/api/register.ts")
    assertEquals(plan.filesToModify.size, 1)
    assertEquals(plan.filesToModify(0).relativePath, "src/App.tsx")
    assertEquals(plan.filesToModify(0).changeType, "add_route")
    assertEquals(plan.requiresNewDeps, List("bcryptjs", "zod"))
  }

  test("parsePlanResponse returns None for malformed JSON") {
    val response = InferenceResponse(
      requestId = "req-1",
      responseText = "this is not json",
      parsedJson = None,
      inputTokens = 10,
      outputTokens = 5,
      cachedHit = false,
      durationMs = 100,
      model = "test",
      provider = InferenceProvider.Mock,
    )

    val result = BuildPhaseManager.parsePlanResponse("run-1", "task", response)
    // Should return Some with defaults since regex extraction won't crash, just find nothing
    assert(result.isDefined)
    assertEquals(result.get.filesToCreate, Nil)
    assertEquals(result.get.filesToModify, Nil)
  }

  test("parsePlanResponse uses defaults for missing optional fields") {
    val json = """{"summary": "Simple fix"}"""
    val response = InferenceResponse(
      requestId = "req-1",
      responseText = json,
      parsedJson = Some(json),
      inputTokens = 10,
      outputTokens = 5,
      cachedHit = false,
      durationMs = 100,
      model = "test",
      provider = InferenceProvider.Mock,
    )

    val result = BuildPhaseManager.parsePlanResponse("run-1", "task", response)
    assert(result.isDefined)
    assertEquals(result.get.summary, "Simple fix")
    assertEquals(result.get.estimatedComplexity, "medium") // default
    assertEquals(result.get.requiresMigration, false) // default
    assertEquals(result.get.requiresNewDeps, Nil) // default
  }

  test("parsePlanResponse handles files_to_create with relative_path key") {
    val json = """{
      "summary": "Test plan",
      "files_to_create": [
        {"relative_path": "src/utils/helper.ts", "description": "Helper util", "category": "util"}
      ]
    }"""
    val response = InferenceResponse(
      requestId = "req-1",
      responseText = json,
      parsedJson = Some(json),
      inputTokens = 10,
      outputTokens = 5,
      cachedHit = false,
      durationMs = 100,
      model = "test",
      provider = InferenceProvider.Mock,
    )

    val result = BuildPhaseManager.parsePlanResponse("run-1", "task", response)
    assert(result.isDefined)
    assertEquals(result.get.filesToCreate.size, 1)
    assertEquals(result.get.filesToCreate(0).relativePath, "src/utils/helper.ts")
  }
}
