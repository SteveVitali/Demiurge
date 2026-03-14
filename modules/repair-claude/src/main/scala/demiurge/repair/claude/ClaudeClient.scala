package demiurge.repair.claude

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.net.{HttpURLConnection, URL}

// Phase 5: ClaudeClient — minimal HTTP client for Claude API.
// Uses java.net.HttpURLConnection (no external HTTP library needed).
// Requires ANTHROPIC_API_KEY environment variable.
object ClaudeClient {

  case class ClaudeResponse(
    content:      String,
    inputTokens:  Long,
    outputTokens: Long,
    stopReason:   String,
  )

  case class ClaudeError(message: String, statusCode: Int)

  private val ApiUrl = "https://api.anthropic.com/v1/messages"
  private val DefaultModel = "claude-sonnet-4-20250514"
  private val ApiVersion = "2023-06-01"
  private val MaxTokens = 4096

  def sendMessage(
    systemPrompt: String,
    userMessage: String,
    model: Option[String] = None,
    maxTokens: Int = MaxTokens,
  ): Either[ClaudeError, ClaudeResponse] = {
    val apiKey = sys.env.getOrElse("ANTHROPIC_API_KEY", "")
    if (apiKey.isEmpty) {
      return Left(ClaudeError("ANTHROPIC_API_KEY environment variable not set", 0))
    }

    val selectedModel = model.getOrElse(DefaultModel)

    // Build JSON request manually (no circe dependency in this module)
    val requestJson = buildRequestJson(systemPrompt, userMessage, selectedModel, maxTokens)

    try {
      val conn = new URL(ApiUrl).openConnection().asInstanceOf[HttpURLConnection]
      try {
        conn.setRequestMethod("POST")
        conn.setDoOutput(true)
        conn.setConnectTimeout(30000)
        conn.setReadTimeout(120000)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", ApiVersion)

        val writer = new OutputStreamWriter(conn.getOutputStream, "UTF-8")
        writer.write(requestJson)
        writer.flush()
        writer.close()

        val statusCode = conn.getResponseCode
        if (statusCode == 200) {
          val body = readStream(conn.getInputStream)
          parseResponse(body)
        } else {
          val errorBody = try { readStream(conn.getErrorStream) } catch { case _: Exception => "" }
          Left(ClaudeError(s"HTTP $statusCode: $errorBody", statusCode))
        }
      } finally {
        conn.disconnect()
      }
    } catch {
      case e: Exception =>
        Left(ClaudeError(s"Request failed: ${e.getMessage}", 0))
    }
  }

  private def buildRequestJson(
    systemPrompt: String,
    userMessage: String,
    model: String,
    maxTokens: Int,
  ): String = {
    val escapedSystem = escapeJson(systemPrompt)
    val escapedUser = escapeJson(userMessage)
    s"""{"model":"$model","max_tokens":$maxTokens,"system":"$escapedSystem","messages":[{"role":"user","content":"$escapedUser"}]}"""
  }

  private def parseResponse(body: String): Either[ClaudeError, ClaudeResponse] = {
    // Simple JSON parsing without circe
    try {
      val content = extractJsonString(body, "text")
      val inputTokens = extractJsonLong(body, "input_tokens")
      val outputTokens = extractJsonLong(body, "output_tokens")
      val stopReason = extractJsonString(body, "stop_reason")

      Right(ClaudeResponse(
        content = content,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        stopReason = stopReason,
      ))
    } catch {
      case e: Exception =>
        Left(ClaudeError(s"Failed to parse response: ${e.getMessage}", 200))
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
