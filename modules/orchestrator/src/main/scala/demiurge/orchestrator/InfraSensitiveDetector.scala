package demiurge.orchestrator

// Spec §13.11: Infra-sensitive file detection.
// Compares patch filesChanged against known infra-sensitive patterns to determine
// whether a full environment rebuild is needed (vs soft reset).
object InfraSensitiveDetector {

  // Spec §13.11: Files that require full environment rebuild when modified
  private val infraSensitivePatterns: List[String => Boolean] = List(
    // Docker/container files
    _.endsWith("Dockerfile"),
    _.endsWith("docker-compose.yml"),
    _.endsWith("docker-compose.yaml"),
    _.endsWith("docker-compose.override.yml"),
    _.endsWith("docker-compose.override.yaml"),
    _.contains("docker-compose"),

    // Package manager lock files
    _.endsWith("package-lock.json"),
    _.endsWith("yarn.lock"),
    _.endsWith("pnpm-lock.yaml"),
    _.endsWith("Gemfile.lock"),
    _.endsWith("poetry.lock"),
    _.endsWith("Pipfile.lock"),
    _.endsWith("go.sum"),
    _.endsWith("Cargo.lock"),
    _.endsWith("composer.lock"),

    // Dependency declaration files (only when dependencies section changes)
    _.endsWith("package.json"),
    _.endsWith("Gemfile"),
    _.endsWith("requirements.txt"),
    _.endsWith("pyproject.toml"),
    _.endsWith("build.gradle"),
    _.endsWith("build.gradle.kts"),
    _.endsWith("pom.xml"),
    _.endsWith("go.mod"),
    _.endsWith("Cargo.toml"),

    // Environment files
    _.endsWith(".env"),
    _.endsWith(".env.local"),
    _.endsWith(".env.production"),
    _.endsWith(".env.development"),

    // Database migrations
    f => f.contains("/migrations/") || f.contains("/migrate/"),

    // CI/Infrastructure config
    _.endsWith("nginx.conf"),
    _.endsWith("Procfile"),
    _.endsWith("Makefile"),
  )

  /**
   * Spec §13.11: Determine if any changed files are infra-sensitive.
   * Returns true if the patch modified files that require a full environment rebuild.
   */
  def requiresRebuild(filesChanged: List[String]): Boolean = {
    filesChanged.exists(file =>
      infraSensitivePatterns.exists(pattern => pattern(file))
    )
  }

  /**
   * Return the subset of files that are infra-sensitive.
   */
  def infraSensitiveFiles(filesChanged: List[String]): List[String] = {
    filesChanged.filter(file =>
      infraSensitivePatterns.exists(pattern => pattern(file))
    )
  }
}
