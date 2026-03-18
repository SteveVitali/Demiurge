package lastmile.selectors

import munit.FunSuite
import java.nio.file.Files

class SelectorsSuite extends FunSuite {

  test("parses valid selectors.yaml") {
    val yaml =
      """selectors:
        |  - id: login-button
        |    strategy: css
        |    value: "button#login"
        |    label: Login button
        |  - id: username-input
        |    strategy: test_id
        |    value: username-field
        |""".stripMargin

    val result = SelectorsParser.parseString(yaml)
    assert(result.isRight, s"Expected Right, got $result")
    val file = result.toOption.get
    assertEquals(file.selectors.size, 2)
    assertEquals(file.selectors.head.id, "login-button")
    assertEquals(file.selectors.head.strategy, "css")
    assertEquals(file.selectors.head.value, "button#login")
    assertEquals(file.selectors.head.label, Some("Login button"))
    assertEquals(file.selectors(1).label, None)
  }

  test("rejects missing selectors key") {
    val yaml = "other:\n  - id: x\n"
    val result = SelectorsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Missing 'selectors' key"))
  }

  test("rejects missing required fields") {
    val yaml =
      """selectors:
        |  - strategy: css
        |    value: "div"
        |""".stripMargin
    val result = SelectorsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("missing required field 'id'"))
  }

  test("rejects invalid strategy") {
    val yaml =
      """selectors:
        |  - id: bad-sel
        |    strategy: invalid
        |    value: "div"
        |""".stripMargin
    val result = SelectorsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("invalid strategy"))
  }

  test("rejects duplicate IDs") {
    val yaml =
      """selectors:
        |  - id: dup
        |    strategy: css
        |    value: "a"
        |  - id: dup
        |    strategy: xpath
        |    value: "//a"
        |""".stripMargin
    val result = SelectorsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Duplicate"))
  }

  test("rejects empty value") {
    val yaml =
      """selectors:
        |  - id: empty-val
        |    strategy: css
        |    value: ""
        |""".stripMargin
    val result = SelectorsParser.parseString(yaml)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("value must not be empty"))
  }

  test("parses from file") {
    val tmpFile = Files.createTempFile("sels-", ".yaml")
    try {
      val yaml =
        """selectors:
          |  - id: file-sel
          |    strategy: role
          |    value: button
          |""".stripMargin
      Files.write(tmpFile, yaml.getBytes("UTF-8"))
      val result = SelectorsParser.parse(tmpFile)
      assert(result.isRight)
      assertEquals(result.toOption.get.selectors.size, 1)
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  test("returns error for missing file") {
    val result = SelectorsParser.parse(java.nio.file.Paths.get("/nonexistent/selectors.yaml"))
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("not found"))
  }

  test("parses all supported strategies") {
    val strategies = List("css", "xpath", "role", "text", "test_id", "label")
    strategies.foreach { s =>
      val yaml = s"""selectors:\n  - id: sel-$s\n    strategy: $s\n    value: some-value\n"""
      val result = SelectorsParser.parseString(yaml)
      assert(result.isRight, s"Strategy '$s' should parse successfully, got $result")
    }
  }
}
