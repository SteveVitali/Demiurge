package demiurge.runtime

import java.io.{BufferedReader, InputStreamReader}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

import demiurge.model.{ServiceSpec, StartupMode}

// Spec §8: Service process management for Phase 3.
// Handles script-native and compose-native service startup.
object ServiceProcessManager {

  case class ManagedService(
    serviceId: String,
    process: Option[Process],
    pid: Option[Long],
    containerId: Option[String],
    logLines: scala.collection.mutable.ListBuffer[String],
    startupMode: StartupMode,
  )

  private val services = new ConcurrentHashMap[String, ManagedService]()

  def getService(serviceId: String): Option[ManagedService] =
    Option(services.get(serviceId))

  def getLogLines(serviceId: String): List[String] =
    Option(services.get(serviceId)).map(m => m.logLines.synchronized { m.logLines.toList }).getOrElse(Nil)

  def allServices: Map[String, ManagedService] = services.asScala.toMap

  /** Start a script-native service via ProcessBuilder. */
  def startScript(spec: ServiceSpec, pidDir: Path): Either[String, ManagedService] = {
    try {
      val cwd = Paths.get(spec.cwd)
      if (!Files.exists(cwd)) Files.createDirectories(cwd)

      val command = spec.startupCommand.getOrElse(
        return Left(s"Service ${spec.serviceId}: no startup_command for script-native service")
      )

      val pb = new ProcessBuilder("sh", "-c", command)
      pb.directory(cwd.toFile)
      pb.redirectErrorStream(true)

      // Load env file first (base layer), then apply demiurge.yaml env vars (override layer)
      val env = pb.environment()
      spec.envFile.foreach { envFilePath =>
        val envFile = cwd.resolve(envFilePath)
        if (Files.exists(envFile)) {
          Files.readAllLines(envFile).asScala.foreach { line =>
            val trimmed = line.trim
            if (trimmed.nonEmpty && !trimmed.startsWith("#")) {
              val eqIdx = trimmed.indexOf('=')
              if (eqIdx > 0) {
                env.put(trimmed.substring(0, eqIdx), trimmed.substring(eqIdx + 1))
              }
            }
          }
        }
      }
      // demiurge.yaml env vars override .env file values
      spec.env.foreach { case (k, v) => env.put(k, v) }

      val process = pb.start()
      val logLines = scala.collection.mutable.ListBuffer[String]()

      // Capture stdout/stderr in a background thread
      val reader = new BufferedReader(new InputStreamReader(process.getInputStream))
      val logThread = new Thread(s"log-${spec.serviceId}") {
        override def run(): Unit = {
          try {
            var line = reader.readLine()
            while (line != null) {
              logLines.synchronized { logLines += line }
              line = reader.readLine()
            }
          } catch {
            case _: Exception => // process ended
          }
        }
      }
      logThread.setDaemon(true)
      logThread.start()

      val pid = try { process.pid() } catch { case _: Exception => -1L }

      // Write PID file
      Files.createDirectories(pidDir)
      val pidFile = pidDir.resolve(s"${spec.serviceId}.pid")
      Files.write(pidFile, pid.toString.getBytes)

      // Check for quick exit (within 2 seconds) — Spec: treat quick exit 0 as one-shot success
      Thread.sleep(500)
      if (!process.isAlive) {
        val exitCode = process.exitValue()
        if (exitCode != 0) {
          return Left(s"Service ${spec.serviceId}: process exited immediately with code $exitCode")
        }
        // exitCode == 0 within 2 seconds => one-shot success, still track it
      }

      val managed = ManagedService(
        serviceId = spec.serviceId,
        process = Some(process),
        pid = if (pid > 0) Some(pid) else None,
        containerId = None,
        logLines = logLines,
        startupMode = StartupMode.ScriptNative,
      )
      services.put(spec.serviceId, managed)
      Right(managed)
    } catch {
      case e: Exception => Left(s"Service ${spec.serviceId}: failed to start — ${e.getMessage}")
    }
  }

  /** Start a compose-native service via docker compose CLI. */
  def startCompose(spec: ServiceSpec, projectName: String, composePaths: List[Path]): Either[String, ManagedService] = {
    try {
      val composeTarget = spec.composeTarget.getOrElse(
        return Left(s"Service ${spec.serviceId}: no compose_target for compose-native service")
      )

      val cwd = Paths.get(spec.cwd)

      // Find compose file
      val composeFile = composePaths.headOption.orElse {
        val candidates = List("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml")
        candidates.map(cwd.resolve).find(Files.exists(_))
      }

      val composeFileArgs = composeFile.map(f => List("-f", f.toString)).getOrElse(Nil)

      // Run docker compose up -d <service>
      val upCmd = List("docker", "compose") ++ composeFileArgs ++
        List("-p", projectName, "up", "-d", composeTarget)

      val upProcess = new ProcessBuilder(upCmd: _*)
        .directory(cwd.toFile)
        .redirectErrorStream(true)
        .start()

      val upOutput = new BufferedReader(new InputStreamReader(upProcess.getInputStream))
      val upLines = scala.collection.mutable.ListBuffer[String]()
      var line = upOutput.readLine()
      while (line != null) {
        upLines += line
        line = upOutput.readLine()
      }
      val upExit = upProcess.waitFor()
      if (upExit != 0) {
        return Left(s"Service ${spec.serviceId}: docker compose up failed (exit=$upExit): ${upLines.mkString("\n")}")
      }

      // Get container ID via docker compose ps -q
      val psCmd = List("docker", "compose") ++ composeFileArgs ++
        List("-p", projectName, "ps", "-q", composeTarget)
      val psProcess = new ProcessBuilder(psCmd: _*)
        .directory(cwd.toFile)
        .redirectErrorStream(true)
        .start()
      val psReader = new BufferedReader(new InputStreamReader(psProcess.getInputStream))
      val containerId = Option(psReader.readLine()).map(_.trim).filter(_.nonEmpty)
      psProcess.waitFor()

      val logLines = scala.collection.mutable.ListBuffer[String]()
      logLines ++= upLines

      // Start log capture in background
      val logsCmd = List("docker", "compose") ++ composeFileArgs ++
        List("-p", projectName, "logs", "-f", "--no-log-prefix", composeTarget)
      try {
        val logsProcess = new ProcessBuilder(logsCmd: _*)
          .directory(cwd.toFile)
          .redirectErrorStream(true)
          .start()
        val logsReader = new BufferedReader(new InputStreamReader(logsProcess.getInputStream))
        val logThread = new Thread(s"compose-log-${spec.serviceId}") {
          override def run(): Unit = {
            try {
              var l = logsReader.readLine()
              while (l != null) {
                logLines.synchronized { logLines += l }
                l = logsReader.readLine()
              }
            } catch {
              case _: Exception =>
            }
          }
        }
        logThread.setDaemon(true)
        logThread.start()
      } catch {
        case _: Exception => // log capture is best-effort
      }

      val managed = ManagedService(
        serviceId = spec.serviceId,
        process = None,
        pid = None,
        containerId = containerId,
        logLines = logLines,
        startupMode = StartupMode.ComposeNative,
      )
      services.put(spec.serviceId, managed)
      Right(managed)
    } catch {
      case e: Exception => Left(s"Service ${spec.serviceId}: compose start failed — ${e.getMessage}")
    }
  }

  /** Stop a script-native service. Kills the full process tree (not just the shell). */
  def stopScript(serviceId: String, shutdownTimeoutMs: Int, pidDir: Path): Unit = {
    Option(services.remove(serviceId)).foreach { managed =>
      managed.process.foreach { proc =>
        if (proc.isAlive) {
          // Kill the entire process tree — proc.destroy() only kills the shell,
          // leaving child processes (e.g. node) holding the port.
          managed.pid.foreach { pid =>
            try {
              // pkill -P sends signal to all children of the given PID
              new ProcessBuilder("pkill", "-TERM", "-P", pid.toString)
                .redirectErrorStream(true).start().waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch { case _: Exception => }
          }
          proc.destroy()
          val stopped = proc.waitFor(shutdownTimeoutMs.toLong, java.util.concurrent.TimeUnit.MILLISECONDS)
          if (!stopped) {
            // Force kill the process tree
            managed.pid.foreach { pid =>
              try {
                new ProcessBuilder("pkill", "-KILL", "-P", pid.toString)
                  .redirectErrorStream(true).start().waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
              } catch { case _: Exception => }
            }
            proc.destroyForcibly()
          }
        }
      }
      // Remove PID file
      val pidFile = pidDir.resolve(s"$serviceId.pid")
      Files.deleteIfExists(pidFile)
    }
  }

  /** Stop a compose-native service. */
  def stopCompose(serviceId: String, projectName: String, composePaths: List[Path], cwd: Path): Unit = {
    Option(services.remove(serviceId)).foreach { _ =>
      try {
        val composeFileArgs = composePaths.headOption.map(f => List("-f", f.toString)).getOrElse(Nil)
        val cmd = List("docker", "compose") ++ composeFileArgs ++
          List("-p", projectName, "stop", serviceId)
        val proc = new ProcessBuilder(cmd: _*)
          .directory(cwd.toFile)
          .redirectErrorStream(true)
          .start()
        proc.waitFor(30000, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (proc.isAlive) proc.destroyForcibly()
      } catch {
        case _: Exception => // best effort
      }
    }
  }

  /** Check if docker compose is available. */
  def isDockerAvailable: Boolean = {
    try {
      val proc = new ProcessBuilder("docker", "compose", "version")
        .redirectErrorStream(true)
        .start()
      val exited = proc.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
      exited && proc.exitValue() == 0
    } catch {
      case _: Exception => false
    }
  }

  /** Clear all tracked services (for testing). */
  def clear(): Unit = services.clear()
}
