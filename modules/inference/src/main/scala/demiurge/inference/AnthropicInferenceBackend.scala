package demiurge.inference

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.net.{HttpURLConnection, SocketTimeoutException, URL}

import io.circe.parser.{parse => parseJsonStr}
import demiurge.model._

// Spec §2.1: Real Anthropic API backend implementing InferenceBackend.
// Reuses HTTP logic from ClaudeClient — POST to Claude Messages API via HttpURLConnection.
class AnthropicInferenceBackend(apiKey: String) extends InferenceBackend {

  override def call(request: InferenceRequest): Either[InferenceError, InferenceResponse] = {
    val startMs = System.currentTimeMillis()
    try {
      val requestJson = buildRequestJson(request)
      val conn = new URL(AnthropicInferenceBackend.ApiUrl).openConnection().asInstanceOf[HttpURLConnection]
      try {
        conn.setRequestMethod("POST")
        conn.setDoOutput(true)
        conn.setConnectTimeout(30000)
        conn.setReadTimeout(if (request.timeoutMs > 0) request.timeoutMs.toInt else 120000)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", AnthropicInferenceBackend.ApiVersion)

        val writer = new OutputStreamWriter(conn.getOutputStream, "UTF-8")
        writer.write(requestJson)
        writer.flush()
        writer.close()

        val statusCode = conn.getResponseCode
        if (statusCode == 200) {
          val body = readStream(conn.getInputStream)
          val elapsed = System.currentTimeMillis() - startMs
          parseResponse(body, request, elapsed)
        } else if (statusCode == 429) {
          val retryAfter = Option(conn.getHeaderField("retry-after"))
            .flatMap(s => scala.util.Try(s.toLong * 1000L).toOption)
            .getOrElse(0L)
          Left(InferenceError.RateLimited(request.requestId, retryAfter))
        } else {
          val errorBody = try { readStream(conn.getErrorStream) } catch { case _: Exception => "" }
          Left(InferenceError.ProviderError(request.requestId, statusCode, s"HTTP $statusCode: $errorBody"))
        }
      } finally {
        conn.disconnect()
      }
    } catch {
      case _: SocketTimeoutException =>
        Left(InferenceError.Timeout(request.requestId, request.timeoutMs))
      case e: java.net.SocketException =>
        Left(InferenceError.ProviderError(request.requestId, 0, s"Connection failed: ${e.getMessage}"))
      case e: Exception =>
        Left(InferenceError.ProviderError(request.requestId, 0, s"Request failed: ${e.getMessage}"))
    }
  }

  private[inference] def buildRequestJson(request: InferenceRequest): String = {
    val escapedSystem = escapeJson(request.systemPrompt)
    val escapedUser = escapeJson(request.userPrompt)
    val tempPart = if (request.temperature != 0.0) s""","temperature":${request.temperature}""" else ""
    // When JSON response format is requested, use assistant prefill to force JSON output
    val messagesPart = if (request.responseFormat.contains("json")) {
      s"""[{"role":"user","content":"$escapedUser"},{"role":"assistant","content":"{"}]"""
    } else {
      s"""[{"role":"user","content":"$escapedUser"}]"""
    }
    s"""{"model":"${escapeJson(request.model)}","max_tokens":${request.maxOutputTokens},"system":"$escapedSystem","messages":$messagesPart$tempPart}"""
  }

  private[inference] def parseResponse(
    body: String,
    request: InferenceRequest,
    elapsedMs: Long = 0L,
  ): Either[InferenceError, InferenceResponse] = {
    try {
      // Use circe for proper JSON parsing of the API response to handle escape sequences correctly
      val (content, inputTokens, outputTokens) = parseJsonStr(body) match {
        case Right(json) =>
          val text = json.hcursor
            .downField("content").downArray
            .downField("text").as[String]
            .getOrElse(extractJsonString(body, "text")) // fallback to regex
          val usage = json.hcursor.downField("usage")
          val inTok = usage.downField("input_tokens").as[Long].getOrElse(0L)
          val outTok = usage.downField("output_tokens").as[Long].getOrElse(0L)
          (text, inTok, outTok)
        case Left(_) =>
          // Fallback to regex extraction if circe parse fails
          (extractJsonString(body, "text"),
           extractJsonLong(body, "input_tokens"),
           extractJsonLong(body, "output_tokens"))
      }

      // When JSON prefill was used, prepend the opening brace that was in the assistant prefill
      val finalContent = if (request.responseFormat.contains("json") && !content.trim.startsWith("{")) {
        "{" + content
      } else {
        content
      }

      val parsedJson = if (request.responseFormat.contains("json")) Some(finalContent) else None

      Right(InferenceResponse(
        requestId = request.requestId,
        responseText = finalContent,
        parsedJson = parsedJson,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedHit = false,
        durationMs = elapsedMs,
        model = request.model,
        provider = InferenceProvider.Anthropic,
      ))
    } catch {
      case e: Exception =>
        Left(InferenceError.MalformedResponse(
          request.requestId, body, s"Failed to parse response: ${e.getMessage}"))
    }
  }

  private def extractJsonString(json: String, key: String): String = {
    val pattern = s""""$key"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1)).getOrElse("")
      .replace("\\n", "\n")
      .replace("\\t", "\t")
      .replace("\\\"", "\"")
      .replace("\\\\", "\\")
  }

  private def extractJsonLong(json: String, key: String): Long = {
    val pattern = s""""$key"\\s*:\\s*(\\d+)""".r
    pattern.findFirstMatchIn(json).map(_.group(1).toLong).getOrElse(0L)
  }

  private def escapeJson(s: String): String = {
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
  }

  private def readStream(is: java.io.InputStream): String = {
    val reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))
    try {
      val sb = new StringBuilder
      var line = reader.readLine()
      while (line != null) {
        sb.append(line).append("\n")
        line = reader.readLine()
      }
      sb.toString.trim
    } finally {
      reader.close()
    }
  }
}

object AnthropicInferenceBackend {
  private[inference] val ApiUrl = "https://api.anthropic.com/v1/messages"
  private[inference] val ApiVersion = "2023-06-01"
  val DefaultModel = "claude-sonnet-4-20250514"
}
