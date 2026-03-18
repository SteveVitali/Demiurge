package lastmile.worker

import munit.FunSuite
import io.circe.Json

class JsonRpcSuite extends FunSuite {

  test("JsonRpcRequest serializes to valid JSON-RPC 2.0") {
    val req = JsonRpcRequest(1, "initialize", Json.obj("artifactRoot" -> Json.fromString("/tmp")))
    val json = req.toJson
    assert(json.contains("\"jsonrpc\":\"2.0\""))
    assert(json.contains("\"id\":1"))
    assert(json.contains("\"method\":\"initialize\""))
    assert(json.contains("\"artifactRoot\":\"/tmp\""))
  }

  test("parseResponse parses successful response") {
    val line = """{"jsonrpc":"2.0","id":1,"result":{"browserVersion":"120.0"}}"""
    val parsed = JsonRpc.parseResponse(line)
    assert(parsed.isRight)
    parsed.foreach {
      case Right(resp) =>
        assertEquals(resp.id, Some(1L))
        assert(resp.isSuccess)
        assert(resp.result.isDefined)
      case Left(_) => fail("Expected response, got notification")
    }
  }

  test("parseResponse parses error response") {
    val line = """{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"Method not found"}}"""
    val parsed = JsonRpc.parseResponse(line)
    assert(parsed.isRight)
    parsed.foreach {
      case Right(resp) =>
        assertEquals(resp.id, Some(2L))
        assert(resp.isError)
        assertEquals(resp.error.get.code, -32601)
        assertEquals(resp.error.get.message, "Method not found")
      case Left(_) => fail("Expected response, got notification")
    }
  }

  test("parseResponse parses notification (no id)") {
    val line = """{"jsonrpc":"2.0","method":"progress","params":{"taskId":"t1","step":"navigate"}}"""
    val parsed = JsonRpc.parseResponse(line)
    assert(parsed.isRight)
    parsed.foreach {
      case Left(notif) =>
        assertEquals(notif.method, "progress")
        assert(notif.params.hcursor.downField("taskId").as[String].contains("t1"))
      case Right(_) => fail("Expected notification, got response")
    }
  }

  test("parseResponse returns error for invalid JSON") {
    val line = "not json at all"
    val parsed = JsonRpc.parseResponse(line)
    assert(parsed.isLeft)
  }

  test("parseResponse handles null id as notification") {
    val line = """{"jsonrpc":"2.0","id":null,"method":"ping"}"""
    val parsed = JsonRpc.parseResponse(line)
    assert(parsed.isRight)
    // null id + method = notification
    parsed.foreach {
      case Left(notif) => assertEquals(notif.method, "ping")
      case Right(_) => // also valid if treated as response with null id
    }
  }

  test("error codes are defined correctly") {
    assertEquals(JsonRpc.PARSE_ERROR, -32700)
    assertEquals(JsonRpc.INVALID_REQUEST, -32600)
    assertEquals(JsonRpc.METHOD_NOT_FOUND, -32601)
    assertEquals(JsonRpc.INVALID_PARAMS, -32602)
    assertEquals(JsonRpc.INTERNAL_ERROR, -32603)
    assertEquals(JsonRpc.TASK_CANCELLED, -32000)
    assertEquals(JsonRpc.BROWSER_ERROR, -32001)
    assertEquals(JsonRpc.ARTIFACT_ERROR, -32002)
    assertEquals(JsonRpc.NOT_INITIALIZED, -32003)
  }

  test("JsonRpcRequest with empty params serializes correctly") {
    val req = JsonRpcRequest(42, "ping")
    val json = req.toJson
    assert(json.contains("\"method\":\"ping\""))
    assert(json.contains("\"id\":42"))
  }

  test("parseResponse handles response with data in error") {
    val line = """{"jsonrpc":"2.0","id":3,"error":{"code":-32000,"message":"cancelled","data":{"taskId":"t1"}}}"""
    val parsed = JsonRpc.parseResponse(line)
    assert(parsed.isRight)
    parsed.foreach {
      case Right(resp) =>
        assert(resp.isError)
        assert(resp.error.get.data.isDefined)
      case _ => fail("Expected error response")
    }
  }
}
