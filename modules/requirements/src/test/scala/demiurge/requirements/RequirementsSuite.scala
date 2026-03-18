package demiurge.requirements

import munit.FunSuite
import java.nio.file.Files

class RequirementsSuite extends FunSuite {

  test("parses valid requirements.yaml") {
    val yaml =
      """requirements:
        |  - id: req-1
        |    type: http
        |    description: Health check returns 200
        |    expected: http://localhost:3000/health
        |    timeout_ms: 5000
        |    retry: 2
        |    severity: required
        |  - id: req-2
        |    type: process
        |    description: Server process runs
        |    timeout_ms: 10000
        |""".stripMargin

    val result = RequirementsParser.parseString(yaml)
    assert(result.isRight, s"Expected Right, got $result")
    val file = result.toOption.get
    assertEquals(file.requirements.size, 2)
    assertEquals(file.requirements.head.id, "req-1")
    assertEquals(file.requirements.head.`type`, "http")
    assertEquals(file.requirements.head.timeoutMs, Some(5000L))
    assertEquals(file.requirements.head.retry, Some(2))
    assertEquals(file.requirements.head.severity, Some("required"))
    assertEquals(file.requirements(1).id, "req-2")
  }

  test("rejects missing requirements key") {
    val yaml = "something_else:\n  - id: x\n"
    val result = RequirementsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Missing 'requirements' key"))
  }

  test("rejects missing required fields") {
    val yaml =
      """requirements:
        |  - type: http
        |    description: missing id
        |""".stripMargin
    val result = RequirementsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("missing required field 'id'"))
  }

  test("rejects invalid type") {
    val yaml =
      """requirements:
        |  - id: req-bad
        |    type: invalid_type
        |    description: bad type
        |""".stripMargin
    val result = RequirementsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("invalid type"))
  }

  test("rejects invalid severity") {
    val yaml =
      """requirements:
        |  - id: req-bad
        |    type: http
        |    description: bad severity
        |    severity: critical
        |""".stripMargin
    val result = RequirementsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("invalid severity"))
  }

  test("rejects duplicate IDs") {
    val yaml =
      """requirements:
        |  - id: dup
        |    type: http
        |    description: first
        |  - id: dup
        |    type: http
        |    description: second
        |""".stripMargin
    val result = RequirementsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Duplicate"))
  }

  test("parses from file") {
    val tmpFile = Files.createTempFile("reqs-", ".yaml")
    try {
      val yaml =
        """requirements:
          |  - id: file-req
          |    type: log
          |    description: Check log output
          |""".stripMargin
      Files.write(tmpFile, yaml.getBytes("UTF-8"))
      val result = RequirementsParser.parse(tmpFile)
      assert(result.isRight)
      assertEquals(result.toOption.get.requirements.size, 1)
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  test("returns error for missing file") {
    val result = RequirementsParser.parse(java.nio.file.Paths.get("/nonexistent/file.yaml"))
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("not found"))
  }

  test("parses all supported types") {
    val types = List("http", "process", "state", "log", "tcp", "browser", "env_readiness")
    types.foreach { t =>
      val yaml = s"""requirements:\n  - id: req-$t\n    type: $t\n    description: test $t\n"""
      val result = RequirementsParser.parseString(yaml)
      assert(result.isRight, s"Type '$t' should parse successfully, got $result")
    }
  }

  test("handles optional fields as None when missing") {
    val yaml =
      """requirements:
        |  - id: minimal
        |    type: http
        |    description: minimal requirement
        |""".stripMargin
    val result = RequirementsParser.parseString(yaml)
    assert(result.isRight)
    val req = result.toOption.get.requirements.head
    assertEquals(req.selector, None)
    assertEquals(req.expected, None)
    assertEquals(req.timeoutMs, None)
    assertEquals(req.retry, None)
    assertEquals(req.severity, None)
  }
}
