package demiurge.cli.Commands

import java.sql.Connection

import demiurge.cli._
import demiurge.cli.CommandParsers._
import demiurge.license.CredentialStore

// Phase 7: `demiurge doctor` command — Spec §14.1
// Canonical checks: Node.js >= 18, package manager, Docker, Docker Compose V2,
// Playwright browsers, Git >= 2.20, disk space >= 1 GB, ANTHROPIC_API_KEY, port checks
object DoctorCommand {

  case class CheckResult(name: String, status: String, detail: String, required: Boolean)

  def execute(global: GlobalOpts, conn: Connection): Int = {
    val checks = scala.collection.mutable.ListBuffer[CheckResult]()

    checks += checkNode()
    checks += checkPackageManager()
    checks += checkDocker()
    checks += checkDockerCompose()
    checks += checkPlaywright()
    checks += checkGit()
    checks += checkDiskSpace(global.repo)
    checks += checkAnthropicKey()
    checks += checkPort(19440)

    val results = checks.toList
    System.out.println(OutputFormatter.formatDoctorResults(
      results.map(r => (r.name, r.status, r.detail)), global.format))

    val hasRequiredFailure = results.exists(r => r.required && r.status == "fail")
    if (hasRequiredFailure) ExitCodes.CommandFailure else ExitCodes.Success
  }

  private def runCommand(cmd: String*): Option[String] = {
    try {
      val pb = new ProcessBuilder(cmd: _*)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val output = new String(proc.getInputStream.readAllBytes()).trim
      val exitCode = proc.waitFor()
      if (exitCode == 0) Some(output) else None
    } catch {
      case _: Exception => None
    }
  }

  private def checkNode(): CheckResult = {
    runCommand("node", "--version") match {
      case Some(v) =>
        val version = v.stripPrefix("v")
        val major = try { version.split("\\.")(0).toInt } catch { case _: Exception => 0 }
        if (major >= 18) CheckResult("Node.js", "pass", s"Node.js $version (>= 18)", required = true)
        else CheckResult("Node.js", "fail", s"Node.js $version (need >= 18)", required = true)
      case None =>
        CheckResult("Node.js", "fail", "Node.js not found", required = true)
    }
  }

  private def checkPackageManager(): CheckResult = {
    val managers = List("npm", "yarn", "pnpm")
    val found = managers.flatMap(m => runCommand(m, "--version").map(v => s"$m $v"))
    if (found.nonEmpty) CheckResult("Package Manager", "pass", found.mkString(", "), required = true)
    else CheckResult("Package Manager", "fail", "No package manager found (npm, yarn, or pnpm)", required = true)
  }

  private def checkDocker(): CheckResult = {
    runCommand("docker", "--version") match {
      case Some(v) => CheckResult("Docker", "pass", v, required = false)
      case None    => CheckResult("Docker", "warn", "Docker not found (needed for compose services)", required = false)
    }
  }

  private def checkDockerCompose(): CheckResult = {
    runCommand("docker", "compose", "version") match {
      case Some(v) => CheckResult("Docker Compose V2", "pass", v, required = false)
      case None    => CheckResult("Docker Compose V2", "warn", "Docker Compose V2 not found", required = false)
    }
  }

  private def checkPlaywright(): CheckResult = {
    runCommand("npx", "playwright", "--version") match {
      case Some(v) => CheckResult("Playwright", "pass", s"Playwright $v", required = false)
      case None    => CheckResult("Playwright", "warn", "Playwright browsers not detected", required = false)
    }
  }

  private def checkGit(): CheckResult = {
    runCommand("git", "--version") match {
      case Some(v) =>
        val versionStr = v.replaceAll("[^0-9.]", "").trim
        val parts = versionStr.split("\\.").take(2)
        val major = try { parts(0).toInt } catch { case _: Exception => 0 }
        val minor = try { parts(1).toInt } catch { case _: Exception => 0 }
        if (major > 2 || (major == 2 && minor >= 20))
          CheckResult("Git", "pass", s"$v (>= 2.20)", required = true)
        else
          CheckResult("Git", "fail", s"$v (need >= 2.20)", required = true)
      case None =>
        CheckResult("Git", "fail", "Git not found", required = true)
    }
  }

  private def checkDiskSpace(repoPath: java.nio.file.Path): CheckResult = {
    try {
      val store = java.nio.file.Files.getFileStore(repoPath)
      val freeGB = store.getUsableSpace / (1024L * 1024L * 1024L)
      if (freeGB >= 1) CheckResult("Disk Space", "pass", s"${freeGB} GB free (>= 1 GB)", required = true)
      else CheckResult("Disk Space", "fail", s"${freeGB} GB free (need >= 1 GB)", required = true)
    } catch {
      case _: Exception => CheckResult("Disk Space", "warn", "Could not determine disk space", required = false)
    }
  }

  private def checkAnthropicKey(): CheckResult = {
    // BYOK: check env var first, then ~/.demiurge/config.json
    CredentialStore.resolveApiKey("ANTHROPIC_API_KEY", "anthropic") match {
      case Some(_) => CheckResult("ANTHROPIC_API_KEY", "pass", "Set", required = false)
      case None    => CheckResult("ANTHROPIC_API_KEY", "warn", "Not set (env var or `demiurge config set anthropic-api-key`)", required = false)
    }
  }

  private def checkPort(port: Int): CheckResult = {
    try {
      val socket = new java.net.ServerSocket(port, 0, java.net.InetAddress.getByName("127.0.0.1"))
      socket.close()
      CheckResult(s"Port $port", "pass", s"Port $port is available", required = false)
    } catch {
      case _: Exception => CheckResult(s"Port $port", "warn", s"Port $port is in use", required = false)
    }
  }
}
