package demiurge.repair

import munit.FunSuite
import java.time.Instant

class RepairSessionSuite extends FunSuite {

  test("serializeTranscript produces valid JSON structure") {
    val session = RepairSession.SessionState(
      sessionId = "sess-1",
      runId = "run-1",
      attemptNumber = 1,
      startedAt = Instant.parse("2024-01-01T00:00:00Z"),
      preCommitSha = Some("abc123"),
      postCommitSha = Some("def456"),
      transcript = List(
        RepairSession.TranscriptEntry(Instant.parse("2024-01-01T00:00:01Z"), "system", "Session opened"),
        RepairSession.TranscriptEntry(Instant.parse("2024-01-01T00:00:02Z"), "assistant", "Proposed patch"),
      ),
      tokensUsed = 1000,
      closedAt = Some(Instant.parse("2024-01-01T00:01:00Z")),
    )

    val json = RepairSession.serializeTranscript(session)
    assert(json.contains("\"sessionId\":\"sess-1\""))
    assert(json.contains("\"runId\":\"run-1\""))
    assert(json.contains("\"attemptNumber\":1"))
    assert(json.contains("\"preCommitSha\":\"abc123\""))
    assert(json.contains("\"postCommitSha\":\"def456\""))
    assert(json.contains("\"transcript\":["))
    assert(json.contains("\"role\":\"system\""))
    assert(json.contains("\"role\":\"assistant\""))
  }

  test("serializeTranscript handles empty transcript") {
    val session = RepairSession.SessionState(
      sessionId = "sess-2",
      runId = "run-2",
      attemptNumber = 1,
      startedAt = Instant.parse("2024-01-01T00:00:00Z"),
      preCommitSha = None,
    )

    val json = RepairSession.serializeTranscript(session)
    assert(json.contains("\"transcript\":[]"))
    assert(json.contains("\"preCommitSha\":\"\""))
    assert(json.contains("\"postCommitSha\":\"\""))
  }

  test("serializeTranscript escapes special characters in content") {
    val session = RepairSession.SessionState(
      sessionId = "sess-3",
      runId = "run-3",
      attemptNumber = 1,
      startedAt = Instant.parse("2024-01-01T00:00:00Z"),
      preCommitSha = None,
      transcript = List(
        RepairSession.TranscriptEntry(
          Instant.parse("2024-01-01T00:00:01Z"),
          "system",
          "Line1\nLine2\tTabbed \"quoted\"",
        ),
      ),
    )

    val json = RepairSession.serializeTranscript(session)
    // Verify escaped characters don't break JSON structure
    assert(json.contains("\\n"), "Newlines should be escaped")
    assert(json.contains("\\t"), "Tabs should be escaped")
    assert(json.contains("\\\"quoted\\\""), "Quotes should be escaped")
    // Verify the JSON is parseable by checking balanced braces
    assert(json.count(_ == '{') == json.count(_ == '}'), "Braces should be balanced")
    assert(json.count(_ == '[') == json.count(_ == ']'), "Brackets should be balanced")
  }

  test("SessionState defaults are sensible") {
    val session = RepairSession.SessionState(
      sessionId = "s1",
      runId = "r1",
      attemptNumber = 1,
      startedAt = Instant.now(),
      preCommitSha = None,
    )
    assertEquals(session.postCommitSha, None)
    assertEquals(session.transcript, Nil)
    assertEquals(session.tokensUsed, 0L)
    assertEquals(session.closedAt, None)
  }
}
