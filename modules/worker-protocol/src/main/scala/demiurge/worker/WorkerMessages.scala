package demiurge.worker

import io.circe._
import io.circe.syntax._

// Spec §10.2–10.6: Typed request/response messages for worker protocol

object WorkerMessages {

  // Spec §9.3.1: Browser options for initialize
  case class BrowserOptions(
    viewport:           Option[(Int, Int)]  = Some((1280, 720)),
    locale:             Option[String]      = Some("en-US"),
    timezone:           Option[String]      = None,
    deviceScaleFactor:  Option[Double]      = None,
    ignoreHTTPSErrors:  Boolean             = true,
  )

  // Spec §9.3.1: Default timeouts for initialize
  case class DefaultTimeouts(
    navigationMs: Int = 30000,
    actionMs:     Int = 15000,
    assertionMs:  Int = 10000,
  )

  // Spec §9.3.1: Browser policy for initialize
  case class BrowserPolicy(
    allowedOrigins:   List[String] = Nil,
    forbiddenOrigins: List[String] = Nil,
  )

  // Spec §9.3.1: Initialize with full params
  def initializeParams(
    artifactRoot:   String,
    worktreePath:   String,
    runId:          String,
    browserOptions: BrowserOptions   = BrowserOptions(),
    defaultTimeouts: DefaultTimeouts = DefaultTimeouts(),
    browserPolicy:  BrowserPolicy    = BrowserPolicy(),
  ): Json = {
    var obj = Json.obj(
      "artifactRoot" -> artifactRoot.asJson,
      "worktreePath" -> worktreePath.asJson,
      "runId"        -> runId.asJson,
    )

    // Spec §9.3.1: browserOptions
    val boObj = Json.obj(
      "ignoreHTTPSErrors" -> browserOptions.ignoreHTTPSErrors.asJson,
    ).deepMerge(
      browserOptions.viewport.map { case (w, h) =>
        Json.obj("viewport" -> Json.obj("width" -> w.asJson, "height" -> h.asJson))
      }.getOrElse(Json.obj())
    ).deepMerge(
      browserOptions.locale.map(l => Json.obj("locale" -> l.asJson)).getOrElse(Json.obj())
    ).deepMerge(
      browserOptions.timezone.map(t => Json.obj("timezone" -> t.asJson)).getOrElse(Json.obj())
    ).deepMerge(
      browserOptions.deviceScaleFactor.map(d => Json.obj("deviceScaleFactor" -> d.asJson)).getOrElse(Json.obj())
    )
    obj = obj.deepMerge(Json.obj("browserOptions" -> boObj))

    // Spec §9.3.1: defaultTimeouts
    obj = obj.deepMerge(Json.obj("defaultTimeouts" -> Json.obj(
      "navigationMs" -> defaultTimeouts.navigationMs.asJson,
      "actionMs"     -> defaultTimeouts.actionMs.asJson,
      "assertionMs"  -> defaultTimeouts.assertionMs.asJson,
    )))

    // Spec §9.3.1: browserPolicy
    obj = obj.deepMerge(Json.obj("browserPolicy" -> Json.obj(
      "allowedOrigins"   -> browserPolicy.allowedOrigins.asJson,
      "forbiddenOrigins" -> browserPolicy.forbiddenOrigins.asJson,
    )))

    obj
  }

  case class InitializeResult(
    browserVersion: String,
    capabilities:   Map[String, Boolean],
  )

  def parseInitializeResult(json: Json): Either[String, InitializeResult] = {
    val c = json.hcursor
    for {
      bv   <- c.downField("browserVersion").as[String].left.map(_.getMessage)
      caps <- c.downField("capabilities").as[Map[String, Boolean]].left.map(_.getMessage)
    } yield InitializeResult(bv, caps)
  }

  // Spec §10.3: Execute browser flow
  def executeBrowserFlowParams(
    taskId:           String,
    entryUrl:         String,
    actions:          List[Json]      = Nil,
    assertions:       List[Json]      = Nil,
    artifactPlan:     List[Json]      = Nil,
    storageStatePath: Option[String]  = None,
    timeoutMs:        Option[Int]     = None,
  ): Json = {
    var obj = Json.obj(
      "taskId"       -> taskId.asJson,
      "entryUrl"     -> entryUrl.asJson,
      "actions"      -> actions.asJson,
      "assertions"   -> assertions.asJson,
      "artifactPlan" -> artifactPlan.asJson,
    )
    storageStatePath.foreach(p => obj = obj.deepMerge(Json.obj("storageStatePath" -> p.asJson)))
    timeoutMs.foreach(t => obj = obj.deepMerge(Json.obj("timeoutMs" -> t.asJson)))
    obj
  }

  case class BrowserFlowResult(
    status:       String,
    observations: List[ObservationResult],
    artifacts:    List[ArtifactMeta],
    errorMessage: Option[String],
    durationMs:   Long,
  )

  case class ObservationResult(
    observationType: String,
    message:         String,
    selector:        Option[String],
    expected:        Option[String],
    actual:          Option[String],
    timestamp:       String,
  )

  case class ArtifactMeta(
    artifactType:    String,
    relativePath:    String,
    contentType:     String,
    sizeBytes:       Long,
    checksumSha256:  String,
    label:           Option[String],
  )

  def parseBrowserFlowResult(json: Json): Either[String, BrowserFlowResult] = {
    val c = json.hcursor
    for {
      status   <- c.downField("status").as[String].left.map(_.getMessage)
      durationMs <- c.downField("durationMs").as[Long].left.map(_.getMessage)
    } yield {
      val errorMessage = c.downField("errorMessage").as[String].toOption
      val observations = parseObservations(c.downField("observations"))
      val artifacts = parseArtifacts(c.downField("artifacts"))
      BrowserFlowResult(status, observations, artifacts, errorMessage, durationMs)
    }
  }

  // Spec §10.4: Auth bootstrap
  def executeAuthBootstrapParams(
    taskId:             String,
    mode:               String,
    loginUrl:           Option[String],
    credentials:        Map[String, String],
    storageStateOutput: String,
    timeoutMs:          Option[Int] = None,
  ): Json = {
    var obj = Json.obj(
      "taskId"             -> taskId.asJson,
      "mode"               -> mode.asJson,
      "credentials"        -> credentials.asJson,
      "storageStateOutput" -> storageStateOutput.asJson,
    )
    loginUrl.foreach(u => obj = obj.deepMerge(Json.obj("loginUrl" -> u.asJson)))
    timeoutMs.foreach(t => obj = obj.deepMerge(Json.obj("timeoutMs" -> t.asJson)))
    obj
  }

  case class AuthBootstrapResult(
    success:          Boolean,
    storageStatePath: Option[String],
    apiHeaders:       Map[String, String],
    errorMessage:     Option[String],
    artifacts:        List[ArtifactMeta],
  )

  def parseAuthBootstrapResult(json: Json): Either[String, AuthBootstrapResult] = {
    val c = json.hcursor
    for {
      success <- c.downField("success").as[Boolean].left.map(_.getMessage)
    } yield {
      val path = c.downField("storageStatePath").as[String].toOption
      val headers = c.downField("apiHeaders").as[Map[String, String]].getOrElse(Map.empty)
      val err = c.downField("errorMessage").as[String].toOption
      val artifacts = parseArtifacts(c.downField("artifacts"))
      AuthBootstrapResult(success, path, headers, err, artifacts)
    }
  }

  // Spec §10.5: API request
  def executeApiRequestParams(
    taskId:           String,
    method:           String,
    url:              String,
    headers:          Map[String, String] = Map.empty,
    body:             Option[String]      = None,
    storageStatePath: Option[String]      = None,
    timeoutMs:        Option[Int]         = None,
  ): Json = {
    var obj = Json.obj(
      "taskId"  -> taskId.asJson,
      "method"  -> method.asJson,
      "url"     -> url.asJson,
      "headers" -> headers.asJson,
    )
    body.foreach(b => obj = obj.deepMerge(Json.obj("body" -> b.asJson)))
    storageStatePath.foreach(p => obj = obj.deepMerge(Json.obj("storageStatePath" -> p.asJson)))
    timeoutMs.foreach(t => obj = obj.deepMerge(Json.obj("timeoutMs" -> t.asJson)))
    obj
  }

  case class ApiRequestResult(
    status:     Int,
    headers:    Map[String, String],
    body:       String,
    durationMs: Long,
    artifacts:  List[ArtifactMeta],
  )

  def parseApiRequestResult(json: Json): Either[String, ApiRequestResult] = {
    val c = json.hcursor
    for {
      status     <- c.downField("status").as[Int].left.map(_.getMessage)
      body       <- c.downField("body").as[String].left.map(_.getMessage)
      durationMs <- c.downField("durationMs").as[Long].left.map(_.getMessage)
    } yield {
      val headers = c.downField("headers").as[Map[String, String]].getOrElse(Map.empty)
      val artifacts = parseArtifacts(c.downField("artifacts"))
      ApiRequestResult(status, headers, body, durationMs, artifacts)
    }
  }

  // Spec §10.6: Page snapshot
  def capturePageSnapshotParams(
    taskId:           String,
    url:              String,
    storageStatePath: Option[String] = None,
    waitForSelector:  Option[String] = None,
    timeoutMs:        Option[Int]    = None,
  ): Json = {
    var obj = Json.obj(
      "taskId" -> taskId.asJson,
      "url"    -> url.asJson,
    )
    storageStatePath.foreach(p => obj = obj.deepMerge(Json.obj("storageStatePath" -> p.asJson)))
    waitForSelector.foreach(s => obj = obj.deepMerge(Json.obj("waitForSelector" -> s.asJson)))
    timeoutMs.foreach(t => obj = obj.deepMerge(Json.obj("timeoutMs" -> t.asJson)))
    obj
  }

  case class PageSnapshotResult(
    artifacts:    List[ArtifactMeta],
    errorMessage: Option[String],
  )

  def parsePageSnapshotResult(json: Json): Either[String, PageSnapshotResult] = {
    val c = json.hcursor
    val artifacts = parseArtifacts(c.downField("artifacts"))
    val err = c.downField("errorMessage").as[String].toOption
    Right(PageSnapshotResult(artifacts, err))
  }

  // Spec §9.2: Heartbeat/progress notification types
  case class HeartbeatNotification(
    taskId:       String,
    status:       String,       // "alive", "busy", "idle"
    uptimeMs:     Long,
    memoryUsageMb: Option[Double],
  )

  case class ProgressNotification(
    taskId:           String,
    phase:            String,     // "navigating", "acting", "asserting", "capturing"
    stepIndex:        Int,
    totalSteps:       Int,
    currentAction:    Option[String],
    elapsedMs:        Long,
  )

  def parseHeartbeatNotification(json: Json): Either[String, HeartbeatNotification] = {
    val c = json.hcursor
    for {
      taskId   <- c.downField("taskId").as[String].left.map(_.getMessage)
      status   <- c.downField("status").as[String].left.map(_.getMessage)
      uptimeMs <- c.downField("uptimeMs").as[Long].left.map(_.getMessage)
    } yield HeartbeatNotification(
      taskId, status, uptimeMs,
      c.downField("memoryUsageMb").as[Double].toOption,
    )
  }

  def parseProgressNotification(json: Json): Either[String, ProgressNotification] = {
    val c = json.hcursor
    for {
      taskId     <- c.downField("taskId").as[String].left.map(_.getMessage)
      phase      <- c.downField("phase").as[String].left.map(_.getMessage)
      stepIndex  <- c.downField("stepIndex").as[Int].left.map(_.getMessage)
      totalSteps <- c.downField("totalSteps").as[Int].left.map(_.getMessage)
      elapsedMs  <- c.downField("elapsedMs").as[Long].left.map(_.getMessage)
    } yield ProgressNotification(
      taskId, phase, stepIndex, totalSteps,
      c.downField("currentAction").as[String].toOption,
      elapsedMs,
    )
  }

  // Shared parsers
  private def parseObservations(cursor: ACursor): List[ObservationResult] = {
    cursor.as[List[Json]].getOrElse(Nil).flatMap { j =>
      val c = j.hcursor
      for {
        ot  <- c.downField("observationType").as[String].toOption
        msg <- c.downField("message").as[String].toOption
        ts  <- c.downField("timestamp").as[String].toOption
      } yield ObservationResult(
        ot, msg,
        c.downField("selector").as[String].toOption,
        c.downField("expected").as[String].toOption,
        c.downField("actual").as[String].toOption,
        ts,
      )
    }
  }

  private def parseArtifacts(cursor: ACursor): List[ArtifactMeta] = {
    cursor.as[List[Json]].getOrElse(Nil).flatMap { j =>
      val c = j.hcursor
      for {
        at   <- c.downField("artifactType").as[String].toOption
        rp   <- c.downField("relativePath").as[String].toOption
        ct   <- c.downField("contentType").as[String].toOption
        sb   <- c.downField("sizeBytes").as[Long].toOption
        cs   <- c.downField("checksumSha256").as[String].toOption
      } yield ArtifactMeta(at, rp, ct, sb, cs, c.downField("label").as[String].toOption)
    }
  }
}
