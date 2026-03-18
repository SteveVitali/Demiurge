package demiurge.policy

import java.nio.file.{Path, Paths}
import demiurge.model._

// Spec §6: Policy enforcement engine.
// Validates filesystem access, browser origins, network egress, and tool usage
// against a PolicySnapshot captured at run start.
object PolicyEnforcer {

  sealed trait PolicyViolation {
    def message: String
  }
  case class FilesystemViolation(path: String, action: String, override val message: String) extends PolicyViolation
  case class NetworkViolation(host: String, port: Int, override val message: String) extends PolicyViolation
  case class BrowserViolation(origin: String, override val message: String) extends PolicyViolation
  case class ToolViolation(tool: String, override val message: String) extends PolicyViolation
  case class DestructiveActionViolation(action: String, override val message: String) extends PolicyViolation

  /** Validate a filesystem write against the policy. */
  def validateFilesystemWrite(path: String, policy: FilesystemPolicy, worktreeRoot: String): Option[FilesystemViolation] = {
    val normalized = normalizePath(path, worktreeRoot)

    // Check forbidden paths first
    if (matchesAnyGlob(normalized, policy.forbiddenWritePaths)) {
      return Some(FilesystemViolation(path, "write",
        s"Write to '$path' is forbidden by filesystem policy"))
    }

    // Check allowed paths
    if (policy.allowedWritePaths.nonEmpty && !matchesAnyGlob(normalized, policy.allowedWritePaths)) {
      return Some(FilesystemViolation(path, "write",
        s"Write to '$path' is not in allowed write paths"))
    }

    None
  }

  /** Validate a filesystem delete against the policy. */
  def validateFilesystemDelete(path: String, policy: FilesystemPolicy, worktreeRoot: String): Option[FilesystemViolation] = {
    val normalized = normalizePath(path, worktreeRoot)

    if (policy.allowDeletePaths.isEmpty) {
      return Some(FilesystemViolation(path, "delete",
        s"Delete of '$path' is not permitted — no allowed delete paths configured"))
    }

    if (!matchesAnyGlob(normalized, policy.allowDeletePaths)) {
      return Some(FilesystemViolation(path, "delete",
        s"Delete of '$path' is not in allowed delete paths"))
    }

    None
  }

  /** Validate a network request against the policy. */
  def validateNetworkRequest(host: String, port: Int, policy: NetworkPolicy): Option[NetworkViolation] = {
    // localhost is always allowed
    if (isLocalhost(host)) return None

    // Hosts explicitly listed in allowedHosts are trusted (skip external egress check)
    val inAllowedHosts = policy.allowedHosts.nonEmpty &&
      policy.allowedHosts.exists(h => host.endsWith(h) || host == h)

    if (!inAllowedHosts) {
      val inAllowlist = policy.externalAllowlist.exists(a => host.endsWith(a) || host == a)

      // External egress check applies to hosts not in allowedHosts
      if (!policy.allowExternalEgress && !inAllowlist) {
        return Some(NetworkViolation(host, port,
          s"External network request to '$host:$port' is blocked — external egress disabled"))
      }

      // If allowedHosts is set and host isn't in it or the external allowlist, reject
      if (policy.allowedHosts.nonEmpty && !inAllowlist) {
        return Some(NetworkViolation(host, port,
          s"Network request to '$host:$port' — host not in allowed hosts"))
      }
    }

    if (policy.allowedPorts.nonEmpty && !policy.allowedPorts.contains(port)) {
      return Some(NetworkViolation(host, port,
        s"Network request to '$host:$port' — port not in allowed ports"))
    }

    None
  }

  /** Validate a browser navigation origin against the policy. */
  def validateBrowserOrigin(url: String, policy: BrowserPolicy): Option[BrowserViolation] = {
    val origin = extractOrigin(url)

    if (policy.forbiddenOrigins.exists(fo => origin.contains(fo))) {
      return Some(BrowserViolation(origin,
        s"Browser navigation to '$origin' is forbidden"))
    }

    if (policy.allowedOrigins.nonEmpty && !policy.allowedOrigins.exists(ao => origin.contains(ao))) {
      return Some(BrowserViolation(origin,
        s"Browser navigation to '$origin' is not in allowed origins"))
    }

    None
  }

  /** Validate a tool invocation against the policy. */
  def validateToolUsage(tool: String, policy: ToolPolicy): Either[ToolViolation, Boolean] = {
    if (policy.forbiddenTools.contains(tool)) {
      return Left(ToolViolation(tool, s"Tool '$tool' is forbidden by policy"))
    }

    // requireApprovalTools are implicitly allowed but need approval
    if (policy.requireApprovalTools.contains(tool)) {
      return Right(true)
    }

    if (policy.allowedTools.nonEmpty && !policy.allowedTools.contains(tool)) {
      return Left(ToolViolation(tool, s"Tool '$tool' is not in allowed tools"))
    }

    Right(false)
  }

  /** Validate a destructive action against the policy. */
  def validateDestructiveAction(action: String, policy: DestructiveActionPolicy): Option[DestructiveActionViolation] = {
    action match {
      case "git_commit" if !policy.allowGitCommit =>
        Some(DestructiveActionViolation(action, "Git commit is not allowed by policy"))
      case "git_push" if !policy.allowGitPush =>
        Some(DestructiveActionViolation(action, "Git push is not allowed by policy"))
      case "git_branch" if !policy.allowGitBranch =>
        Some(DestructiveActionViolation(action, "Git branch creation is not allowed by policy"))
      case "db_write" if !policy.allowDbWrite =>
        Some(DestructiveActionViolation(action, "Database write is not allowed by policy"))
      case "db_drop" if !policy.allowDbDrop =>
        Some(DestructiveActionViolation(action, "Database drop is not allowed by policy"))
      case "docker_volume_remove" if !policy.allowDockerVolumeRemove =>
        Some(DestructiveActionViolation(action, "Docker volume removal is not allowed by policy"))
      case "external_submission" if !policy.allowExternalSubmission =>
        Some(DestructiveActionViolation(action, "External submission is not allowed by policy"))
      case _ => None
    }
  }

  /** Create a default permissive PolicySnapshot for a run. */
  def defaultPolicySnapshot(runId: String, worktreeRoot: String, budget: ExecutionBudget): PolicySnapshot = {
    PolicySnapshot(
      policySnapshotId = java.util.UUID.randomUUID().toString,
      runId = runId,
      capturedAt = java.time.Instant.now(),
      filesystemPolicy = FilesystemPolicy(
        allowedWritePaths = List(worktreeRoot + "/**"),
        forbiddenWritePaths = List("/etc/**", "/usr/**", "/System/**"),
        allowDeletePaths = List(worktreeRoot + "/**"),
      ),
      networkPolicy = NetworkPolicy(
        allowedHosts = Nil, // empty = all allowed
        allowedPorts = Nil, // empty = all allowed
        allowExternalEgress = false,
        externalAllowlist = List("api.anthropic.com", "api.openai.com"),
      ),
      browserPolicy = BrowserPolicy(
        allowedOrigins = Nil, // empty = all allowed
        forbiddenOrigins = Nil,
        maxConcurrentContexts = 1,
      ),
      toolPolicy = ToolPolicy(
        allowedTools = List("read_file", "write_file", "list_directory", "search_files", "run_command"),
        forbiddenTools = Nil,
        requireApprovalTools = List("run_command"),
      ),
      destructiveActionPolicy = DestructiveActionPolicy(
        allowGitCommit = true,
        allowGitPush = false,
        allowGitBranch = true,
        allowDbWrite = false,
        allowDbDrop = false,
        allowDockerVolumeRemove = false,
        allowExternalSubmission = false,
      ),
      executionBudget = budget,
    )
  }

  // --- Helpers ---

  private def normalizePath(path: String, worktreeRoot: String): String = {
    val p = Paths.get(path)
    if (p.isAbsolute) p.normalize().toString
    else Paths.get(worktreeRoot, path).normalize().toString
  }

  private def matchesAnyGlob(path: String, patterns: List[String]): Boolean = {
    patterns.exists(pattern => globMatch(path, pattern))
  }

  private def globMatch(path: String, pattern: String): Boolean = {
    if (pattern.endsWith("/**")) {
      path.startsWith(pattern.dropRight(3))
    } else if (pattern.contains("*")) {
      val regex = pattern.replace(".", "\\.").replace("**", ".*").replace("*", "[^/]*")
      path.matches(regex)
    } else {
      path == pattern || path.startsWith(pattern + "/")
    }
  }

  private def isLocalhost(host: String): Boolean = {
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0"
  }

  private def extractOrigin(url: String): String = {
    try {
      val u = new java.net.URL(url)
      s"${u.getProtocol}://${u.getHost}" + (if (u.getPort > 0) s":${u.getPort}" else "")
    } catch {
      case _: Exception => url
    }
  }
}
