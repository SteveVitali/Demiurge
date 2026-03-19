package demiurge.planner

import java.time.Instant
import java.util.UUID

import demiurge.model._
import demiurge.manifest._

// Spec §8: Real EnvironmentPlanner for Phase 3.
// Produces a RuntimePlan from parsed demiurge.yaml + basic RepoInspectionReport.
// Rules: manifest values override inferred values, topological sort by dependency,
// cycle detection fails planning, no LLM planning.
object EnvironmentPlannerImpl extends EnvironmentPlanner {

  case class PlanningError(message: String) extends RuntimeException(message)

  override def plan(runId: String, inspection: RepoInspectionReport, requirements: RequirementGraph): RuntimePlan = {
    // If a demiurge manifest was found and parsed successfully, use it
    val manifestRef = inspection.manifestsFound.find(m => m.manifestType == "demiurge" && m.parsedSuccessfully)

    // Try to load and parse manifest from the repo root
    val manifestOpt = manifestRef.flatMap { _ =>
      val manifestPath = inspection.repoRoot.resolve("demiurge.yaml")
      ManifestParser.parseFile(manifestPath) match {
        case ManifestParser.ParseSuccess(m) =>
          val validation = ManifestValidation.validate(m)
          if (validation.isValid) Some(m)
          else {
            System.err.println(s"[planner] demiurge.yaml validation failed: ${validation.errors.mkString("; ")}")
            None
          }
        case ManifestParser.ParseFailure(errors) =>
          System.err.println(s"[planner] demiurge.yaml parse failed: ${errors.mkString("; ")}")
          None
        case _ => None
      }
    }

    manifestOpt match {
      case Some(manifest) => planFromManifest(runId, manifest, inspection)
      case None => planFromInspection(runId, inspection)
    }
  }

  private def planFromManifest(runId: String, manifest: DemiurgeManifest, inspection: RepoInspectionReport): RuntimePlan = {
    val warnings = scala.collection.mutable.ListBuffer[String]()
    val repoRoot = inspection.repoRoot.toString

    // Build service specs from manifest
    val serviceSpecs = manifest.services.map { case (name, svc) =>
      val kind = parseServiceKind(svc.kind)
      val startupMode = parseStartupMode(svc.startupMode)
      val cwd = svc.cwd.getOrElse(repoRoot)

      val ports = svc.ports.getOrElse(Nil).map { pc =>
        PortMapping(pc.host, pc.container, pc.protocol.getOrElse("tcp"))
      }

      val readinessProbe = svc.readiness match {
        case Some(rc) => ReadinessProbe(
          probeType = rc.probeType,
          target = rc.target,
          intervalMs = rc.intervalMs.getOrElse(1000),
          timeoutMs = rc.timeoutMs.getOrElse(30000),
          maxFailures = rc.maxFailures.getOrElse(10),
          initialDelayMs = rc.initialDelayMs.getOrElse(0),
        )
        case None =>
          // Derive from ports if available
          ports.headOption match {
            case Some(pm) =>
              val port = pm.hostPort.getOrElse(pm.containerPort)
              ReadinessProbe("tcp", s"localhost:$port", 1000, 30000, 10, 0)
            case None =>
              warnings += s"Service $name: no readiness or ports defined, using noop probe"
              ReadinessProbe("tcp", "localhost:0", 1000, 5000, 1, 0)
          }
      }

      val restartPolicy = svc.restart match {
        case Some(rc) => RestartPolicy(
          rc.maxRestarts.getOrElse(3),
          rc.backoffBaseMs.getOrElse(1000),
          rc.backoffMaxMs.getOrElse(30000),
          rc.backoffMultiplier.getOrElse(2.0),
        )
        case None => RestartPolicy(3, 1000, 30000, 2.0)
      }

      name -> ServiceSpec(
        serviceId = name,
        kind = kind,
        startupMode = startupMode,
        startupCommand = svc.startupCommand,
        composeTarget = svc.composeTarget,
        cwd = cwd,
        env = svc.env.getOrElse(Map.empty),
        envFile = svc.envFile,
        ports = ports,
        dependencyServices = svc.dependsOn.getOrElse(Nil),
        readinessProbe = readinessProbe,
        shutdownMethod = svc.shutdownMethod.getOrElse("sigterm"),
        shutdownTimeoutMs = svc.shutdownTimeoutMs.getOrElse(10000),
        restartPolicy = restartPolicy,
        logsSource = svc.logs.getOrElse("stdout"),
        required = svc.required.getOrElse(true),
      )
    }

    // Topological sort
    val sorted = topologicalSort(serviceSpecs.values.toList) match {
      case Right(s) => s
      case Left(cycle) => throw PlanningError(s"Dependency cycle detected: ${cycle.mkString(" → ")}")
    }

    // Build fixture steps
    val fixtureSteps = manifest.fixtures.flatMap(_.seedSteps).getOrElse(Nil).zipWithIndex.map { case (step, idx) =>
      FixtureStep(
        stepId = step.stepId,
        description = step.description.getOrElse(s"Seed step ${step.stepId}"),
        command = step.command,
        cwd = step.cwd.getOrElse(repoRoot),
        env = step.env.getOrElse(Map.empty),
        timeoutMs = step.timeoutMs.getOrElse(60000),
        dependsOnServices = step.dependsOnServices.getOrElse(Nil),
        runOnReset = step.runOnReset.getOrElse(false),
        runOnInitOnly = step.runOnInitOnly.getOrElse(false),
        order = step.order.getOrElse(idx),
      )
    }.sortBy(_.order)

    // Teardown order is reverse of startup order
    val teardownOrder = sorted.map(_.serviceId).reverse

    RuntimePlan(
      planId = s"plan-$runId-${UUID.randomUUID().toString.take(8)}",
      runId = runId,
      services = sorted,
      fixtureSteps = fixtureSteps,
      authBootstrapPlan = None,
      resetStrategy = manifest.fixtures.flatMap(_.resetStrategy) match {
        case Some("soft_reset") => ResetStrategy.SoftReset
        case Some("hard_reset") => ResetStrategy.HardReset
        case Some("full_rebuild") => ResetStrategy.FullRebuild
        case _ => ResetStrategy.SoftReset
      },
      teardownOrder = teardownOrder,
      observabilityTaps = Nil,
      generatedAt = Instant.now(),
      warnings = warnings.toList,
    )
  }

  private def planFromInspection(runId: String, inspection: RepoInspectionReport): RuntimePlan = {
    val warnings = scala.collection.mutable.ListBuffer[String]()
    warnings += "No valid demiurge.yaml found; planning from inspection hints only"

    // Build minimal service specs from candidate services
    val services = inspection.candidateServices.map { cs =>
      ServiceSpec(
        serviceId = cs.serviceId,
        kind = cs.kind,
        startupMode = StartupMode.ScriptNative,
        startupCommand = cs.startupHint,
        composeTarget = None,
        cwd = inspection.repoRoot.toString,
        env = Map.empty,
        envFile = None,
        ports = cs.portHint.map(p => List(PortMapping(Some(p), p, "tcp"))).getOrElse(Nil),
        dependencyServices = Nil,
        readinessProbe = cs.healthHint match {
          case Some(h) => ReadinessProbe("http", h, 1000, 30000, 10, 1000)
          case None => cs.portHint match {
            case Some(p) => ReadinessProbe("tcp", s"localhost:$p", 1000, 30000, 10, 0)
            case None => ReadinessProbe("tcp", "localhost:0", 1000, 5000, 1, 0)
          }
        },
        shutdownMethod = "sigterm",
        shutdownTimeoutMs = 10000,
        restartPolicy = RestartPolicy(3, 1000, 30000, 2.0),
        logsSource = "stdout",
        required = true,
      )
    }

    RuntimePlan(
      planId = s"plan-$runId-${UUID.randomUUID().toString.take(8)}",
      runId = runId,
      services = services,
      fixtureSteps = Nil,
      authBootstrapPlan = None,
      resetStrategy = ResetStrategy.SoftReset,
      teardownOrder = services.map(_.serviceId).reverse,
      observabilityTaps = Nil,
      generatedAt = Instant.now(),
      warnings = warnings.toList,
    )
  }

  // Topological sort using Kahn's algorithm with cycle detection
  private[planner] def topologicalSort(services: List[ServiceSpec]): Either[List[String], List[ServiceSpec]] = {
    val serviceMap = services.map(s => s.serviceId -> s).toMap
    val inDegree = scala.collection.mutable.Map[String, Int]()
    val adjList = scala.collection.mutable.Map[String, List[String]]()

    services.foreach { s =>
      inDegree.getOrElseUpdate(s.serviceId, 0)
      adjList.getOrElseUpdate(s.serviceId, Nil)
      s.dependencyServices.foreach { dep =>
        if (serviceMap.contains(dep)) {
          adjList(dep) = adjList.getOrElse(dep, Nil) :+ s.serviceId
          inDegree(s.serviceId) = inDegree.getOrElse(s.serviceId, 0) + 1
        }
      }
    }

    val queue = scala.collection.mutable.Queue[String]()
    inDegree.filter(_._2 == 0).keys.toList.sorted.foreach(queue.enqueue(_))

    val result = scala.collection.mutable.ListBuffer[String]()

    while (queue.nonEmpty) {
      val node = queue.dequeue()
      result += node
      adjList.getOrElse(node, Nil).foreach { neighbor =>
        inDegree(neighbor) -= 1
        if (inDegree(neighbor) == 0) queue.enqueue(neighbor)
      }
    }

    if (result.size != services.size) {
      // Find a cycle for error reporting
      val remaining = services.map(_.serviceId).filterNot(result.contains)
      Left(remaining)
    } else {
      Right(result.toList.flatMap(id => serviceMap.get(id)))
    }
  }

  private def parseServiceKind(kind: String): ServiceKind = kind.toLowerCase match {
    case "frontend" => ServiceKind.Frontend
    case "api" => ServiceKind.Api
    case "db" => ServiceKind.Db
    case "cache" => ServiceKind.Cache
    case "queue" => ServiceKind.Queue
    case "worker" => ServiceKind.Worker
    case "external_mock" => ServiceKind.ExternalMock
    case _ => ServiceKind.Api
  }

  private def parseStartupMode(mode: String): StartupMode = mode.toLowerCase match {
    case "script" => StartupMode.ScriptNative
    case "compose" => StartupMode.ComposeNative
    case "hybrid" => StartupMode.Hybrid
    case "verifier_owned_container" => StartupMode.VerifierOwnedContainer
    case _ => StartupMode.ScriptNative
  }
}
