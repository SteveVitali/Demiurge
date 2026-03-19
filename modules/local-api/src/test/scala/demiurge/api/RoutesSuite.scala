package demiurge.api

import munit.FunSuite
import java.net.{HttpURLConnection, URL}
import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.Instant
import java.util.UUID

import io.circe.parser.{decode => jsonDecode}
import io.circe.Json

import demiurge.model._
import demiurge.persistence._

// Phase 7: API route tests — real SQLite, real HTTP, localhost only
class RoutesSuite extends FunSuite {

  // Allow Origin header in HttpURLConnection (restricted by default in Java)
  System.setProperty("sun.net.http.allowRestrictedHeaders", "true")

  private var tmpDir: Path = _
  private var dbPath: Path = _
  private var conn: Connection = _
  private var server: com.sun.net.httpserver.HttpServer = _
  private var port: Int = _

  override def beforeEach(context: BeforeEach): Unit = {
    tmpDir = Files.createTempDirectory("demiurge-api-test")
    Files.createDirectories(tmpDir.resolve(".demiurge"))
    dbPath = tmpDir.resolve(".demiurge").resolve("demiurge.db")
    conn = Database.open(dbPath)
    Migrator.migrate(conn)

    // Find a free port
    val ss = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
    port = ss.getLocalPort
    ss.close()

    val artifactRoot = tmpDir.resolve(".demiurge").resolve("artifacts")
    Files.createDirectories(artifactRoot)

    server = LocalApiServer.start(
      port = port,
      dbPath = dbPath,
      artifactRootResolver = runId => {
        implicit val c: Connection = Database.open(dbPath)
        try {
          TaskRunRepo.getById(runId).map(_.artifactRootPath)
        } finally { c.close() }
      },
    )
    // Give server a moment to start
    Thread.sleep(200)
  }

  override def afterEach(context: AfterEach): Unit = {
    server.stop(0)
    conn.close()
    deleteRecursive(tmpDir)
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val stream = Files.list(path)
      try { stream.forEach(child => deleteRecursive(child)) }
      finally { stream.close() }
    }
    Files.deleteIfExists(path)
  }

  private def insertRun(runId: String, status: RunStatus = RunStatus.Succeeded): TaskRun = {
    implicit val c: Connection = conn
    val artifactRoot = tmpDir.resolve(".demiurge").resolve("artifacts")
    val run = TaskRun(
      runId = runId, repoPath = tmpDir, worktreePath = tmpDir,
      gitRef = Some("main"), taskText = "Test task", changedFiles = None,
      status = status, runMode = RunMode.Full,
      createdAt = Instant.now(), startedAt = Some(Instant.now()),
      endedAt = if (status == RunStatus.Succeeded) Some(Instant.now()) else None,
      maxAttempts = 5, attemptCount = 1, envBootAttempts = 0,
      currentAttemptId = None,
      finalVerdict = if (status == RunStatus.Succeeded) Some(VerdictStatus.Pass) else None,
      finalSummary = None, policySnapshotId = s"policy-$runId",
      lockFilePath = tmpDir.resolve(".demiurge/run.lock"),
      artifactRootPath = artifactRoot,
    )
    TaskRunRepo.insert(run)
    run
  }

  private def insertAttempt(runId: String, num: Int): Unit = {
    implicit val c: Connection = conn
    AttemptRepo.insert(Attempt(
      attemptId = s"att-$num", runId = runId, attemptNumber = num,
      status = AttemptStatus.VerificationPassed, startedAt = Instant.now(),
      endedAt = Some(Instant.now()), repairBackend = None, patchRecordId = None,
      failurePacketId = None, rerunPlanId = None, repairRetriesUsed = 0,
      verdictSummary = Some(AttemptVerdictSummary(2, 2, 0, 0, 0, 0, 0)),
    ))
  }

  private def insertArtifact(runId: String, attemptNum: Int): ArtifactRecord = {
    implicit val c: Connection = conn
    val id = UUID.randomUUID().toString
    val record = ArtifactRecord(
      artifactId = id, runId = runId, attemptNumber = Some(attemptNum),
      artifactType = ArtifactType.Screenshot, producerComponent = "test",
      logicalScope = None, relativePath = s"$runId/attempt_$attemptNum/screenshot.png",
      contentType = "image/png", sizeBytes = 10, checksumSha256 = "abc",
      compressed = false, compressionFormat = None, createdAt = Instant.now(),
      metadata = Map.empty,
    )
    ArtifactRecordRepo.insert(record)

    // Write actual file content for content endpoint test
    val filePath = tmpDir.resolve(".demiurge").resolve("artifacts").resolve(record.relativePath)
    Files.createDirectories(filePath.getParent)
    Files.write(filePath, "test-content".getBytes)

    record
  }

  private def httpGet(path: String): (Int, String) = {
    val url = new URL(s"http://127.0.0.1:$port$path")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("GET")
    conn.setConnectTimeout(2000)
    conn.setReadTimeout(2000)
    try {
      val status = conn.getResponseCode
      val stream = if (status >= 400) conn.getErrorStream else conn.getInputStream
      val body = if (stream != null) new String(stream.readAllBytes()) else ""
      (status, body)
    } finally {
      conn.disconnect()
    }
  }

  private def httpPost(path: String, body: String): (Int, String) = {
    val url = new URL(s"http://127.0.0.1:$port$path")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setDoOutput(true)
    conn.setConnectTimeout(2000)
    conn.setReadTimeout(2000)
    conn.getOutputStream.write(body.getBytes)
    try {
      val status = conn.getResponseCode
      val stream = if (status >= 400) conn.getErrorStream else conn.getInputStream
      val respBody = if (stream != null) new String(stream.readAllBytes()) else ""
      (status, respBody)
    } finally {
      conn.disconnect()
    }
  }

  // --- Tests ---

  test("GET /health works") {
    val (status, body) = httpGet("/health")
    assertEquals(status, 200)
    val json = jsonDecode[Json](body).toOption.get
    assertEquals(json.hcursor.get[Boolean]("ok").toOption, Some(true))
  }

  test("GET /runs/{id} returns envelope") {
    val run = insertRun("api-run-1")
    val (status, body) = httpGet("/runs/api-run-1")
    assertEquals(status, 200)
    val json = jsonDecode[Json](body).toOption.get
    assertEquals(json.hcursor.get[Boolean]("ok").toOption, Some(true))
    val data = json.hcursor.downField("data")
    assertEquals(data.get[String]("runId").toOption, Some("api-run-1"))
  }

  test("error envelope works for missing run") {
    val (status, body) = httpGet("/runs/nonexistent")
    assertEquals(status, 404)
    val json = jsonDecode[Json](body).toOption.get
    assertEquals(json.hcursor.get[Boolean]("ok").toOption, Some(false))
    assert(json.hcursor.downField("error").get[String]("message").toOption.get.contains("not found"))
  }

  test("GET /runs/{id}/attempts works") {
    insertRun("api-run-2")
    insertAttempt("api-run-2", 1)
    insertAttempt("api-run-2", 2)

    val (status, body) = httpGet("/runs/api-run-2/attempts")
    assertEquals(status, 200)
    val json = jsonDecode[Json](body).toOption.get
    assertEquals(json.hcursor.get[Boolean]("ok").toOption, Some(true))
    val data = json.hcursor.downField("data").focus.flatMap(_.asArray)
    assert(data.isDefined)
    assertEquals(data.get.length, 2)
  }

  test("GET /runs/{id}/artifacts pagination works") {
    insertRun("api-run-3")
    insertArtifact("api-run-3", 1)
    insertArtifact("api-run-3", 1)
    insertArtifact("api-run-3", 1)

    val (status, body) = httpGet("/runs/api-run-3/artifacts?offset=0&limit=2")
    assertEquals(status, 200)
    val json = jsonDecode[Json](body).toOption.get
    val data = json.hcursor.downField("data")
    assertEquals(data.get[Int]("total").toOption, Some(3))
    assertEquals(data.get[Int]("offset").toOption, Some(0))
    assertEquals(data.get[Int]("limit").toOption, Some(2))
    val items = data.downField("items").focus.flatMap(_.asArray)
    assert(items.isDefined)
    assertEquals(items.get.length, 2)
  }

  test("GET /runs/{id}/artifacts/{artifactId}/content returns content") {
    insertRun("api-run-4")
    val artifact = insertArtifact("api-run-4", 1)

    val (status, body) = httpGet(s"/runs/api-run-4/artifacts/${artifact.artifactId}/content")
    assertEquals(status, 200)
    assertEquals(body, "test-content")
  }

  test("localhost binding only") {
    // The server is started on 127.0.0.1, which means it only accepts local connections.
    // We verify this by checking the server binds to the loopback address.
    val addr = server.getAddress
    assertEquals(addr.getAddress.getHostAddress, "127.0.0.1")
  }

  // --- Desktop Phase 1: CORS middleware tests ---

  private def httpOptions(path: String, origin: String): (Int, Map[String, String]) = {
    val url = new URL(s"http://127.0.0.1:$port$path")
    val c = url.openConnection().asInstanceOf[HttpURLConnection]
    c.setRequestMethod("OPTIONS")
    c.setRequestProperty("Origin", origin)
    c.setConnectTimeout(2000)
    c.setReadTimeout(2000)
    try {
      val status = c.getResponseCode
      val headers = Map(
        "Access-Control-Allow-Origin" -> Option(c.getHeaderField("Access-Control-Allow-Origin")).getOrElse(""),
        "Access-Control-Allow-Methods" -> Option(c.getHeaderField("Access-Control-Allow-Methods")).getOrElse(""),
      )
      (status, headers)
    } finally { c.disconnect() }
  }

  private def httpGetWithOrigin(path: String, origin: String): (Int, String, String) = {
    val url = new URL(s"http://127.0.0.1:$port$path")
    val c = url.openConnection().asInstanceOf[HttpURLConnection]
    c.setRequestMethod("GET")
    c.setRequestProperty("Origin", origin)
    c.setConnectTimeout(2000)
    c.setReadTimeout(2000)
    try {
      val status = c.getResponseCode
      val stream = if (status >= 400) c.getErrorStream else c.getInputStream
      val body = if (stream != null) new String(stream.readAllBytes()) else ""
      val corsHeader = Option(c.getHeaderField("Access-Control-Allow-Origin")).getOrElse("")
      (status, body, corsHeader)
    } finally { c.disconnect() }
  }

  test("CORS preflight responds with 204 for allowed origin") {
    val (status, headers) = httpOptions("/health", "http://localhost:1420")
    assertEquals(status, 204)
    assertEquals(headers("Access-Control-Allow-Origin"), "http://localhost:1420")
    assert(headers("Access-Control-Allow-Methods").contains("GET"))
  }

  test("CORS preflight responds with 204 for tauri origin") {
    val (status, headers) = httpOptions("/health", "tauri://localhost")
    assertEquals(status, 204)
    assertEquals(headers("Access-Control-Allow-Origin"), "tauri://localhost")
  }

  test("CORS preflight does not set header for disallowed origin") {
    val (status, headers) = httpOptions("/health", "http://evil.com")
    assertEquals(status, 204)
    assertEquals(headers("Access-Control-Allow-Origin"), "")
  }

  test("CORS headers set on normal GET for allowed origin") {
    val (status, _, corsHeader) = httpGetWithOrigin("/health", "http://localhost:1420")
    assertEquals(status, 200)
    assertEquals(corsHeader, "http://localhost:1420")
  }

  test("CORS headers not set on normal GET for disallowed origin") {
    val (status, _, corsHeader) = httpGetWithOrigin("/health", "http://evil.com")
    assertEquals(status, 200)
    assertEquals(corsHeader, "")
  }

  // --- Desktop Phase 1: GET /runs (paginated list) ---

  test("GET /runs returns paginated list") {
    insertRun("list-run-1", RunStatus.Succeeded)
    insertRun("list-run-2", RunStatus.Exhausted)
    insertRun("list-run-3", RunStatus.InspectingRepo)

    val (status, body) = httpGet("/runs?offset=0&limit=2")
    assertEquals(status, 200)
    val json = jsonDecode[Json](body).toOption.get
    assertEquals(json.hcursor.get[Boolean]("ok").toOption, Some(true))
    val data = json.hcursor.downField("data")
    assertEquals(data.get[Int]("total").toOption, Some(3))
    assertEquals(data.get[Int]("limit").toOption, Some(2))
    val items = data.downField("items").focus.flatMap(_.asArray)
    assert(items.isDefined)
    assertEquals(items.get.length, 2)
  }

  test("GET /runs with status filter") {
    insertRun("filt-run-1", RunStatus.Succeeded)
    insertRun("filt-run-2", RunStatus.Exhausted)
    insertRun("filt-run-3", RunStatus.Succeeded)

    val (status, body) = httpGet("/runs?status=Succeeded")
    assertEquals(status, 200)
    val json = jsonDecode[Json](body).toOption.get
    val data = json.hcursor.downField("data")
    assertEquals(data.get[Int]("total").toOption, Some(2))
    val items = data.downField("items").focus.flatMap(_.asArray).get
    assert(items.forall(j => j.hcursor.get[String]("status").toOption.contains("Succeeded")))
  }

  // --- Desktop Phase 1: GET /runs/active ---

  test("GET /runs/active returns active run") {
    insertRun("active-run-1", RunStatus.Succeeded)
    insertRun("active-run-2", RunStatus.Verifying)

    val (status, body) = httpGet("/runs/active")
    assertEquals(status, 200)
    val json = jsonDecode[Json](body).toOption.get
    assertEquals(json.hcursor.get[Boolean]("ok").toOption, Some(true))
    assertEquals(json.hcursor.downField("data").get[String]("runId").toOption, Some("active-run-2"))
  }

  test("GET /runs/active returns 404 when no active run") {
    insertRun("done-run-1", RunStatus.Succeeded)
    insertRun("done-run-2", RunStatus.Cancelled)

    val (status, body) = httpGet("/runs/active")
    assertEquals(status, 404)
    val json = jsonDecode[Json](body).toOption.get
    assertEquals(json.hcursor.get[Boolean]("ok").toOption, Some(false))
  }
}
