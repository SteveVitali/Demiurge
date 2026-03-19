package demiurge.agent

import io.circe._
import io.circe.syntax._

import java.nio.file.{Files, Path}

import demiurge.model._
import demiurge.inference.InferenceService
import demiurge.verification.VerificationEngine
import demiurge.runtime.{ReadinessChecker, RuntimeSupervisor, ServiceProcessManager}
import demiurge.worker.{WorkerProcessManager, JsonRpcNotification}

// Design §6.3: Scala-side handlers for MCP tool callback notifications.
// When the agent calls a Demiurge MCP tool (e.g. verify_requirements),
// the worker sends a JSON-RPC notification to Scala. This object handles
// those notifications, performs the requested action, and sends the result
// back as a callback response notification.
object AgentToolRpcHandlers {

  /**
   * Context needed to handle agent tool callbacks.
   * Passed in when registering the notification handler.
   */
  case class AgentToolContext(
    runId: String,
    requirementGraph: RequirementGraph,
    runtimePlan: Option[RuntimePlan],
    supervisor: RuntimeSupervisor,
    workerManager: WorkerProcessManager,
    repoRoot: java.nio.file.Path,
    browserExecutor: Option[VerificationEngine.BrowserVerifierExecutor] = None,
    inferenceService: Option[InferenceService] = None,
    resolvedConfig: Option[ResolvedConfig] = None,
    authContext: Option[AuthContext] = None,
  )

  /**
   * Register notification handlers on the worker for all Demiurge MCP tool callbacks.
   * Each handler:
   *   1. Receives the notification from worker (sent by MCP tool handler)
   *   2. Executes the requested action (verification, restart, logs, etc.)
   *   3. Sends a callback response notification back to the worker
   */
  def registerHandlers(ctx: AgentToolContext): Unit = {
    ctx.workerManager.setNotificationHandler { notification =>
      try {
        handleNotification(notification, ctx)
      } catch {
        case e: Exception =>
          System.err.println(s"[agent-tool-handler] Error handling ${notification.method}: ${e.getMessage}")
          sendCallbackError(ctx, notification.params, e.getMessage)
      }
    }
  }

  private def handleNotification(notification: JsonRpcNotification, ctx: AgentToolContext): Unit = {
    notification.method match {
      case "demiurge.verifyRequirements" =>
        handleVerifyRequirements(notification.params, ctx)

      case "demiurge.getServiceLogs" =>
        handleGetServiceLogs(notification.params, ctx)

      case "demiurge.restartService" =>
        handleRestartService(notification.params, ctx)

      case "demiurge.getRequirementDetails" =>
        handleGetRequirementDetails(notification.params, ctx)

      case "demiurge.checkServiceHealth" =>
        handleCheckServiceHealth(notification.params, ctx)

      case "agent/toolUse" =>
        // Real-time tool use notifications — log for transcript, no response needed
        val toolName = notification.params.hcursor.downField("toolName").as[String].getOrElse("unknown")
        val inputSummary = notification.params.hcursor.downField("inputSummary").as[String].getOrElse("")
        val shortInput = if (inputSummary.length > 100) inputSummary.take(100) + "..." else inputSummary
        System.err.println(s"[agent] ▸ $toolName ${if (shortInput.nonEmpty) s"— $shortInput" else ""}")

      case "agent/progress" =>
        // Agent text progress — limited stdout logging
        val text = notification.params.hcursor.downField("text").as[String].getOrElse("")
        if (text.nonEmpty) {
          val truncated = if (text.length > 120) text.take(120) + "..." else text
          System.err.println(s"[agent] $truncated")
        }

      case _ =>
        // Unknown notification — ignore
    }
  }

  // Design §6.4: verify_requirements — run the full verification suite
  private def handleVerifyRequirements(params: Json, ctx: AgentToolContext): Unit = {
    val callbackId = params.hcursor.downField("_callbackId").as[String].getOrElse("")
    val reqIds = params.hcursor.downField("requirementIds").as[List[String]].getOrElse(Nil)

    val attemptNumber = 0 // agent-internal verification, not a tracked attempt

    try {
      val result = VerificationEngine.runVerification(
        ctx.runId,
        attemptNumber,
        ctx.requirementGraph,
        ctx.browserExecutor,
        ctx.inferenceService,
        ctx.resolvedConfig,
        ctx.authContext,
      )

      // Filter by requested requirement IDs if specified
      val verdicts = if (reqIds.isEmpty) result.verdicts
        else result.verdicts.filter(v => reqIds.contains(v.requirementId))

      val responseJson = Json.obj(
        "overallVerdict" -> result.aggregate.overallVerdict.toString.asJson,
        "total"          -> result.aggregate.total.asJson,
        "passCount"      -> result.aggregate.passCount.asJson,
        "failCount"      -> result.aggregate.failCount.asJson,
        "errorCount"     -> result.aggregate.errorCount.asJson,
        "verdicts"       -> verdicts.map { v =>
          Json.obj(
            "requirementId" -> v.requirementId.asJson,
            "status"        -> v.status.toString.asJson,
            "message"       -> v.failureMessage.asJson,
          )
        }.asJson,
      )

      sendCallbackResponse(ctx, callbackId, responseJson)
    } catch {
      case e: Exception =>
        sendCallbackError(ctx, params, s"Verification failed: ${e.getMessage}")
    }
  }

  // Design §6.4: get_service_logs — collect recent logs from a service
  // Uses ServiceProcessManager's in-memory log buffer (same source as LogCollector).
  private def handleGetServiceLogs(params: Json, ctx: AgentToolContext): Unit = {
    val callbackId = params.hcursor.downField("_callbackId").as[String].getOrElse("")
    val serviceId = params.hcursor.downField("serviceId").as[String].getOrElse("")
    val lines = params.hcursor.downField("lines").as[Int].getOrElse(200)

    try {
      val knownService = ctx.runtimePlan.exists(_.services.exists(_.serviceId == serviceId))
      if (!knownService) {
        sendCallbackError(ctx, params, s"Service '$serviceId' not found in runtime plan")
        return
      }

      val allLines = ServiceProcessManager.getLogLines(serviceId)
      val tailLines = allLines.takeRight(lines)
      val logText = if (tailLines.isEmpty) s"No logs captured yet for service '$serviceId'"
                    else tailLines.mkString("\n")

      val responseJson = Json.obj(
        "serviceId"  -> serviceId.asJson,
        "logs"       -> logText.asJson,
        "lineCount"  -> tailLines.size.asJson,
        "totalLines" -> allLines.size.asJson,
      )

      sendCallbackResponse(ctx, callbackId, responseJson)
    } catch {
      case e: Exception =>
        sendCallbackError(ctx, params, s"Failed to get logs: ${e.getMessage}")
    }
  }

  // Design §6.4: restart_service — per-service restart via ServiceProcessManager.
  // Stops the individual service and re-starts it, then runs its readiness probe.
  private def handleRestartService(params: Json, ctx: AgentToolContext): Unit = {
    val callbackId = params.hcursor.downField("_callbackId").as[String].getOrElse("")
    val serviceId = params.hcursor.downField("serviceId").as[String].getOrElse("")

    try {
      ctx.runtimePlan match {
        case Some(plan) =>
          val specOpt = plan.services.find(_.serviceId == serviceId)
          specOpt match {
            case None =>
              sendCallbackError(ctx, params, s"Service '$serviceId' not found in runtime plan")
              return
            case Some(spec) =>
              val pidDir = ctx.repoRoot.resolve(".demiurge").resolve("pids")
              val composeProjectName = s"demiurge-${plan.runId.take(8)}"
              val composePaths = detectComposePaths(ctx.repoRoot)

              // Stop the individual service
              spec.startupMode match {
                case StartupMode.ScriptNative | StartupMode.Hybrid =>
                  ServiceProcessManager.stopScript(serviceId, spec.shutdownTimeoutMs, pidDir)
                case StartupMode.ComposeNative =>
                  ServiceProcessManager.stopCompose(
                    serviceId, composeProjectName, composePaths, java.nio.file.Paths.get(spec.cwd))
                case _ =>
                  ServiceProcessManager.stopScript(serviceId, spec.shutdownTimeoutMs, pidDir)
              }

              // Brief pause for port release
              Thread.sleep(500)

              // Re-start the service
              val startResult = spec.startupMode match {
                case StartupMode.ScriptNative | StartupMode.Hybrid =>
                  ServiceProcessManager.startScript(spec, pidDir)
                case StartupMode.ComposeNative =>
                  ServiceProcessManager.startCompose(spec, composeProjectName, composePaths)
                case _ =>
                  ServiceProcessManager.startScript(spec, pidDir)
              }

              startResult match {
                case Left(error) =>
                  val responseJson = Json.obj(
                    "serviceId" -> serviceId.asJson,
                    "status"    -> "failed".asJson,
                    "error"     -> error.asJson,
                  )
                  sendCallbackResponse(ctx, callbackId, responseJson)

                case Right(_) =>
                  // Run readiness probe
                  val ready = ReadinessChecker.waitUntilReady(
                    spec.readinessProbe, ServiceProcessManager.getLogLines(serviceId))

                  val statusStr = if (ready) "running" else "started_but_not_ready"
                  val logTail = ServiceProcessManager.getLogLines(serviceId).takeRight(10)

                  val responseJson = Json.obj(
                    "serviceId" -> serviceId.asJson,
                    "status"    -> statusStr.asJson,
                    "logTail"   -> logTail.mkString("\n").asJson,
                  )
                  sendCallbackResponse(ctx, callbackId, responseJson)
              }
          }

        case None =>
          sendCallbackError(ctx, params, "No runtime plan available for service restart")
      }
    } catch {
      case e: Exception =>
        sendCallbackError(ctx, params, s"Failed to restart service: ${e.getMessage}")
    }
  }

  private val composeFileNames = List(
    "compose.yaml", "compose.yml",
    "docker-compose.yaml", "docker-compose.yml",
  )

  private def detectComposePaths(repoRoot: Path): List[Path] = {
    composeFileNames.map(repoRoot.resolve).filter(Files.exists(_))
  }

  // Design §6.4: get_requirement_details — return full details of a requirement
  private def handleGetRequirementDetails(params: Json, ctx: AgentToolContext): Unit = {
    val callbackId = params.hcursor.downField("_callbackId").as[String].getOrElse("")
    val reqId = params.hcursor.downField("requirementId").as[String].getOrElse("")

    val reqOpt = ctx.requirementGraph.nodes.find(_.requirementId == reqId)

    reqOpt match {
      case Some(req) =>
        val responseJson = Json.obj(
          "requirementId"    -> req.requirementId.asJson,
          "humanDescription" -> req.humanDescription.asJson,
          "category"         -> req.category.toString.asJson,
          "priority"         -> req.priority.toString.asJson,
          "verifiers"        -> req.verifiers.map { v =>
            Json.obj(
              "verifierType" -> v.verifierType.toString.asJson,
              "description"  -> v.displayName.asJson,
            )
          }.asJson,
        )
        sendCallbackResponse(ctx, callbackId, responseJson)

      case None =>
        sendCallbackError(ctx, params, s"Requirement '$reqId' not found")
    }
  }

  // Design §6.4: check_service_health — return health status of all services.
  // Uses ReadinessChecker.checkOnce for a real probe and ServiceProcessManager for liveness.
  private def handleCheckServiceHealth(params: Json, ctx: AgentToolContext): Unit = {
    val callbackId = params.hcursor.downField("_callbackId").as[String].getOrElse("")

    try {
      val services = ctx.runtimePlan.map { plan =>
        plan.services.map { svc =>
          val managed = ServiceProcessManager.getService(svc.serviceId)
          val processAlive = managed.exists { m =>
            m.process.exists(_.isAlive) || m.containerId.isDefined
          }

          val (status, probeResult) = if (!processAlive && managed.isEmpty) {
            ("not_started", "no process found")
          } else if (!processAlive) {
            ("stopped", "process not alive")
          } else {
            // Run the readiness probe once to check actual health
            ReadinessChecker.checkOnce(svc.readinessProbe) match {
              case ReadinessChecker.ProbeSuccess =>
                ("healthy", "probe passed")
              case ReadinessChecker.ProbeFailure(reason) =>
                ("unhealthy", reason)
              case ReadinessChecker.ProbeTimeout(ms) =>
                ("unhealthy", s"probe timed out after ${ms}ms")
            }
          }

          val logTail = ServiceProcessManager.getLogLines(svc.serviceId).takeRight(5)

          Json.obj(
            "serviceId"   -> svc.serviceId.asJson,
            "status"      -> status.asJson,
            "probeResult" -> probeResult.asJson,
            "logTail"     -> logTail.mkString("\n").asJson,
          )
        }
      }.getOrElse(Nil)

      val responseJson = Json.obj(
        "services" -> services.asJson,
      )
      sendCallbackResponse(ctx, callbackId, responseJson)
    } catch {
      case e: Exception =>
        sendCallbackError(ctx, params, s"Health check failed: ${e.getMessage}")
    }
  }

  private def sendCallbackResponse(ctx: AgentToolContext, callbackId: String, result: Json): Unit = {
    val response = Json.obj(
      "_callbackId" -> callbackId.asJson,
      "_result"     -> result,
    )
    ctx.workerManager.sendNotification("demiurge.callback.response", response)
  }

  private def sendCallbackError(ctx: AgentToolContext, params: Json, error: String): Unit = {
    val callbackId = params.hcursor.downField("_callbackId").as[String].getOrElse("")
    val response = Json.obj(
      "_callbackId" -> callbackId.asJson,
      "_error"      -> error.asJson,
    )
    ctx.workerManager.sendNotification("demiurge.callback.response", response)
  }
}
