package lastmile.manifest

import org.yaml.snakeyaml.Yaml
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

// Spec §5: Parse lastmile.yaml into LastmileManifest.
// Phase 3 only needs the fields required for runtime realization.
object ManifestParser {

  sealed trait ParseResult
  case class ParseSuccess(manifest: LastmileManifest) extends ParseResult
  case class ParseFailure(errors: List[String]) extends ParseResult

  def parseFile(path: Path): ParseResult = {
    if (!Files.exists(path)) return ParseFailure(List(s"Manifest file not found: $path"))
    val content = new String(Files.readAllBytes(path), "UTF-8")
    parseString(content)
  }

  def parseString(yaml: String): ParseResult = {
    try {
      val snakeYaml = new Yaml()
      val raw = snakeYaml.load[java.util.Map[String, Any]](yaml)
      if (raw == null) return ParseFailure(List("Empty YAML document"))
      val root = toScalaMap(raw)
      parseRoot(root)
    } catch {
      case e: Exception => ParseFailure(List(s"YAML parse error: ${e.getMessage}"))
    }
  }

  private def parseRoot(root: Map[String, Any]): ParseResult = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    // version
    val version = root.get("version") match {
      case Some(v: java.lang.Integer) => v.intValue()
      case Some(v: java.lang.Long) => v.intValue()
      case Some(v: Int) => v
      case Some(other) =>
        errors += s"version must be an integer, got: $other"
        0
      case None =>
        errors += "version is required"
        0
    }

    // app
    val app = root.get("app") match {
      case Some(m: java.util.Map[_, _]) => parseApp(toScalaMap(m.asInstanceOf[java.util.Map[String, Any]]), errors)
      case Some(m: Map[_, _]) => parseApp(m.asInstanceOf[Map[String, Any]], errors)
      case Some(_) =>
        errors += "app must be a map"
        None
      case None =>
        errors += "app is required"
        None
    }

    // services
    val services = root.get("services") match {
      case Some(m: java.util.Map[_, _]) => parseServices(toScalaMap(m.asInstanceOf[java.util.Map[String, Any]]), errors)
      case Some(m: Map[_, _]) => parseServices(m.asInstanceOf[Map[String, Any]], errors)
      case Some(_) =>
        errors += "services must be a map"
        Map.empty[String, ServiceConfig]
      case None =>
        errors += "services is required"
        Map.empty[String, ServiceConfig]
    }

    // fixtures (optional)
    val fixtures = root.get("fixtures") match {
      case Some(m: java.util.Map[_, _]) => Some(parseFixtures(toScalaMap(m.asInstanceOf[java.util.Map[String, Any]]), errors))
      case Some(m: Map[_, _]) => Some(parseFixtures(m.asInstanceOf[Map[String, Any]], errors))
      case None => None
      case _ =>
        errors += "fixtures must be a map"
        None
    }

    // observability (optional, parsed but no behavior in Phase 3)
    val observability = root.get("observability") match {
      case Some(m: java.util.Map[_, _]) => Some(parseObservability(toScalaMap(m.asInstanceOf[java.util.Map[String, Any]]), errors))
      case Some(m: Map[_, _]) => Some(parseObservability(m.asInstanceOf[Map[String, Any]], errors))
      case None => None
      case _ => None
    }

    if (errors.nonEmpty) ParseFailure(errors.toList)
    else app match {
      case Some(a) => ParseSuccess(LastmileManifest(version, a, services, fixtures, observability))
      case None => ParseFailure(errors.toList :+ "Failed to parse app config")
    }
  }

  private def parseApp(m: Map[String, Any], errors: scala.collection.mutable.ListBuffer[String]): Option[AppConfig] = {
    val appType = getRequiredString(m, "type", "app.type", errors)
    val rootUrl = getRequiredString(m, "root_url", "app.root_url", errors)
    val apiUrl = getOptionalString(m, "api_url")
    appType.flatMap(t => rootUrl.map(r => AppConfig(t, r, apiUrl)))
  }

  private def parseServices(m: Map[String, Any], errors: scala.collection.mutable.ListBuffer[String]): Map[String, ServiceConfig] = {
    m.flatMap { case (name, value) =>
      value match {
        case sm: java.util.Map[_, _] =>
          parseServiceConfig(name, toScalaMap(sm.asInstanceOf[java.util.Map[String, Any]]), errors).map(name -> _)
        case sm: Map[_, _] =>
          parseServiceConfig(name, sm.asInstanceOf[Map[String, Any]], errors).map(name -> _)
        case _ =>
          errors += s"services.$name must be a map"
          None
      }
    }
  }

  private def parseServiceConfig(name: String, m: Map[String, Any], errors: scala.collection.mutable.ListBuffer[String]): Option[ServiceConfig] = {
    val kind = getRequiredString(m, "kind", s"services.$name.kind", errors)
    val startupMode = getRequiredString(m, "startup_mode", s"services.$name.startup_mode", errors)

    // Validate kind enum
    val validKinds = List("api", "cache", "db", "external_mock", "frontend", "queue", "worker")
    kind.foreach { k =>
      if (!validKinds.contains(k.toLowerCase))
        errors += s"services.$name.kind: unknown value '$k', expected one of: ${validKinds.mkString(", ")}"
    }

    // Validate startup_mode enum
    val validStartupModes = List("compose", "hybrid", "script", "verifier_owned_container")
    startupMode.foreach { sm =>
      if (!validStartupModes.contains(sm.toLowerCase))
        errors += s"services.$name.startup_mode: unknown value '$sm', expected one of: ${validStartupModes.mkString(", ")}"
    }

    val startupCommand = getOptionalString(m, "startup_command")
    val composeTarget = getOptionalString(m, "compose_target")
    val cwd = getOptionalString(m, "cwd")
    val env = getOptionalStringMap(m, "env")
    val envFile = getOptionalString(m, "env_file")
    val ports = parsePortConfigs(m, name, errors)
    val dependsOn = getOptionalStringList(m, "depends_on")
    val readiness = parseReadinessConfig(m, name, errors)
    val shutdownMethod = getOptionalString(m, "shutdown_method")
    val shutdownTimeoutMs = getOptionalInt(m, "shutdown_timeout_ms")
    val restart = parseRestartConfig(m, name)
    val logs = getOptionalString(m, "logs")
    val required = getOptionalBoolean(m, "required")
    val startup = parseStartupConfig(m)

    (kind, startupMode) match {
      case (Some(k), Some(sm)) =>
        Some(ServiceConfig(
          kind = k, startupMode = sm, startupCommand = startupCommand,
          composeTarget = composeTarget, cwd = cwd, env = env, envFile = envFile,
          ports = ports, dependsOn = dependsOn, readiness = readiness,
          shutdownMethod = shutdownMethod, shutdownTimeoutMs = shutdownTimeoutMs,
          restart = restart, logs = logs, required = required, startup = startup,
        ))
      case _ => None
    }
  }

  private def parsePortConfigs(m: Map[String, Any], serviceName: String, errors: scala.collection.mutable.ListBuffer[String]): Option[List[PortConfig]] = {
    m.get("ports") match {
      case Some(lst: java.util.List[_]) =>
        val ports = lst.asScala.toList.zipWithIndex.flatMap { case (item, idx) =>
          item match {
            case pm: java.util.Map[_, _] =>
              val sm = toScalaMap(pm.asInstanceOf[java.util.Map[String, Any]])
              val container = getRequiredInt(sm, "container", s"services.$serviceName.ports[$idx].container", errors)
              val host = getOptionalInt(sm, "host")
              val protocol = getOptionalString(sm, "protocol")
              container.map(c => PortConfig(host, c, protocol))
            case n: java.lang.Integer =>
              Some(PortConfig(None, n.intValue(), None))
            case n: Int =>
              Some(PortConfig(None, n, None))
            case _ =>
              errors += s"services.$serviceName.ports[$idx]: expected a map or integer"
              None
          }
        }
        if (ports.nonEmpty) Some(ports) else None
      case None => None
      case _ => None
    }
  }

  private def parseReadinessConfig(m: Map[String, Any], serviceName: String, errors: scala.collection.mutable.ListBuffer[String]): Option[ReadinessConfig] = {
    m.get("readiness") match {
      case Some(rm: java.util.Map[_, _]) =>
        val sm = toScalaMap(rm.asInstanceOf[java.util.Map[String, Any]])
        val probeType = getRequiredString(sm, "probe_type", s"services.$serviceName.readiness.probe_type", errors)
        val target = getRequiredString(sm, "target", s"services.$serviceName.readiness.target", errors)

        // Validate probe type
        val validProbeTypes = List("exec", "http", "log_contains", "tcp")
        probeType.foreach { pt =>
          if (!validProbeTypes.contains(pt.toLowerCase))
            errors += s"services.$serviceName.readiness.probe_type: unknown value '$pt', expected one of: ${validProbeTypes.mkString(", ")}"
        }

        (probeType, target) match {
          case (Some(pt), Some(t)) =>
            Some(ReadinessConfig(
              probeType = pt, target = t,
              intervalMs = getOptionalInt(sm, "interval_ms"),
              timeoutMs = getOptionalInt(sm, "timeout_ms"),
              maxFailures = getOptionalInt(sm, "max_failures"),
              initialDelayMs = getOptionalInt(sm, "initial_delay_ms"),
            ))
          case _ => None
        }
      case None => None
      case _ => None
    }
  }

  private def parseRestartConfig(m: Map[String, Any], serviceName: String): Option[RestartConfig] = {
    m.get("restart") match {
      case Some(rm: java.util.Map[_, _]) =>
        val sm = toScalaMap(rm.asInstanceOf[java.util.Map[String, Any]])
        Some(RestartConfig(
          getOptionalInt(sm, "max_restarts"),
          getOptionalInt(sm, "backoff_base_ms"),
          getOptionalInt(sm, "backoff_max_ms"),
          getOptionalDouble(sm, "backoff_multiplier"),
        ))
      case _ => None
    }
  }

  private def parseStartupConfig(m: Map[String, Any]): Option[StartupConfig] = {
    m.get("startup") match {
      case Some(sm: java.util.Map[_, _]) =>
        val s = toScalaMap(sm.asInstanceOf[java.util.Map[String, Any]])
        Some(StartupConfig(getOptionalInt(s, "order")))
      case _ => None
    }
  }

  private def parseFixtures(m: Map[String, Any], errors: scala.collection.mutable.ListBuffer[String]): FixturesConfig = {
    val seedSteps = m.get("seed_steps") match {
      case Some(lst: java.util.List[_]) =>
        Some(lst.asScala.toList.zipWithIndex.flatMap { case (item, idx) =>
          item match {
            case sm: java.util.Map[_, _] => parseSeedStep(toScalaMap(sm.asInstanceOf[java.util.Map[String, Any]]), idx, errors)
            case _ =>
              errors += s"fixtures.seed_steps[$idx]: expected a map"
              None
          }
        })
      case None => None
      case _ => None
    }
    val resetStrategy = getOptionalString(m, "reset_strategy")
    FixturesConfig(seedSteps, resetStrategy)
  }

  private def parseSeedStep(m: Map[String, Any], idx: Int, errors: scala.collection.mutable.ListBuffer[String]): Option[SeedStepConfig] = {
    val stepId = getRequiredString(m, "step_id", s"fixtures.seed_steps[$idx].step_id", errors)
    val command = getRequiredString(m, "command", s"fixtures.seed_steps[$idx].command", errors)
    (stepId, command) match {
      case (Some(sid), Some(cmd)) =>
        Some(SeedStepConfig(
          stepId = sid, description = getOptionalString(m, "description"), command = cmd,
          cwd = getOptionalString(m, "cwd"), env = getOptionalStringMap(m, "env"),
          timeoutMs = getOptionalInt(m, "timeout_ms"),
          dependsOnServices = getOptionalStringList(m, "depends_on_services"),
          runOnReset = getOptionalBoolean(m, "run_on_reset"),
          runOnInitOnly = getOptionalBoolean(m, "run_on_init_only"),
          order = getOptionalInt(m, "order"),
        ))
      case _ => None
    }
  }

  private def parseObservability(m: Map[String, Any], errors: scala.collection.mutable.ListBuffer[String]): ObservabilityConfig = {
    val taps = m.get("taps") match {
      case Some(lst: java.util.List[_]) =>
        Some(lst.asScala.toList.flatMap {
          case tm: java.util.Map[_, _] =>
            val sm = toScalaMap(tm.asInstanceOf[java.util.Map[String, Any]])
            for {
              tapId <- getOptionalString(sm, "tap_id")
              serviceId <- getOptionalString(sm, "service_id")
              tapType <- getOptionalString(sm, "tap_type")
            } yield ObservabilityTapConfig(tapId, serviceId, tapType, getOptionalStringMap(sm, "config"))
          case _ => None
        })
      case _ => None
    }
    ObservabilityConfig(taps)
  }

  // --- Helper methods ---

  private[manifest] def toScalaMap(jmap: java.util.Map[String, Any]): Map[String, Any] = {
    if (jmap == null) Map.empty
    else jmap.asScala.toMap
  }

  private def getRequiredString(m: Map[String, Any], key: String, path: String, errors: scala.collection.mutable.ListBuffer[String]): Option[String] = {
    m.get(key) match {
      case Some(s: String) => Some(s)
      case Some(other) =>
        errors += s"$path must be a string, got: ${other.getClass.getSimpleName}"
        None
      case None =>
        errors += s"$path is required"
        None
    }
  }

  private def getRequiredInt(m: Map[String, Any], key: String, path: String, errors: scala.collection.mutable.ListBuffer[String]): Option[Int] = {
    m.get(key) match {
      case Some(v: java.lang.Integer) => Some(v.intValue())
      case Some(v: java.lang.Long) => Some(v.intValue())
      case Some(v: Int) => Some(v)
      case Some(other) =>
        errors += s"$path must be an integer, got: ${other.getClass.getSimpleName}"
        None
      case None =>
        errors += s"$path is required"
        None
    }
  }

  private def getOptionalString(m: Map[String, Any], key: String): Option[String] =
    m.get(key).collect { case s: String => s }

  private def getOptionalInt(m: Map[String, Any], key: String): Option[Int] =
    m.get(key).collect {
      case v: java.lang.Integer => v.intValue()
      case v: java.lang.Long => v.intValue()
      case v: Int => v
    }

  private def getOptionalDouble(m: Map[String, Any], key: String): Option[Double] =
    m.get(key).collect {
      case v: java.lang.Double => v.doubleValue()
      case v: java.lang.Float => v.doubleValue()
      case v: java.lang.Integer => v.doubleValue()
      case v: Double => v
    }

  private def getOptionalBoolean(m: Map[String, Any], key: String): Option[Boolean] =
    m.get(key).collect {
      case v: java.lang.Boolean => v.booleanValue()
      case v: Boolean => v
    }

  private def getOptionalStringList(m: Map[String, Any], key: String): Option[List[String]] =
    m.get(key).collect {
      case lst: java.util.List[_] => lst.asScala.toList.collect { case s: String => s }
    }

  private def getOptionalStringMap(m: Map[String, Any], key: String): Option[Map[String, String]] =
    m.get(key).collect {
      case jm: java.util.Map[_, _] =>
        jm.asScala.toMap.collect { case (k: String, v) => k -> String.valueOf(v) }
    }
}
