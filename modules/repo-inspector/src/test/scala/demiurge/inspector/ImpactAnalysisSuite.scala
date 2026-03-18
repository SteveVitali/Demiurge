package demiurge.inspector

import java.nio.file.{Files, Path}

import demiurge.model._

// Phase 8: Tests for changed-file impact analysis (Spec §3.2 ImpactMap)
class ImpactAnalysisSuite extends munit.FunSuite {

  test("impact analysis produces valid ImpactMap from changed files") {
    val tmpDir = Files.createTempDirectory("impact-test")
    try {
      val changedFiles = List("src/login.tsx", "src/api/users.ts", "prisma/schema.prisma")
      val report = RepoInspectorImpl.inspect("run-1", tmpDir, Some(changedFiles))

      assert(report.changedSurfaceMap.isDefined, "ImpactMap should be produced when changedFiles provided")
      val impact = report.changedSurfaceMap.get
      assertEquals(impact.changedFiles, changedFiles)
      assert(impact.affectedFrontendRoutes.nonEmpty, "Should detect frontend route from .tsx file")
      assert(impact.affectedDbModels.nonEmpty, "Should detect DB model from prisma file")
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("impact analysis detects infra-sensitive files per Spec §1") {
    val tmpDir = Files.createTempDirectory("impact-infra-test")
    try {
      val changedFiles = List(
        "docker-compose.yml", "Dockerfile", "package.json",
        "src/app.ts", "db/migrations/001_init.sql",
      )
      val impact = RepoInspectorImpl.buildImpactMap(tmpDir, changedFiles)

      assert(impact.infraSensitiveChanges.contains("docker-compose.yml"))
      assert(impact.infraSensitiveChanges.contains("Dockerfile"))
      assert(impact.infraSensitiveChanges.contains("package.json"))
      assert(impact.infraSensitiveChanges.exists(_.contains("migrations")))
      assert(!impact.infraSensitiveChanges.contains("src/app.ts"), "Non-infra file should not be infra-sensitive")
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("impact analysis detects auth-related modules") {
    val tmpDir = Files.createTempDirectory("impact-auth-test")
    try {
      val changedFiles = List("src/auth/login.ts", "src/session/manager.ts", "src/utils/format.ts")
      val impact = RepoInspectorImpl.buildImpactMap(tmpDir, changedFiles)

      assertEquals(impact.affectedAuthModules.size, 2)
      assert(impact.affectedAuthModules.exists(_.value.contains("auth")))
      assert(impact.affectedAuthModules.exists(_.value.contains("session")))
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("impact analysis detects API handlers from path heuristics") {
    val tmpDir = Files.createTempDirectory("impact-api-test")
    try {
      val changedFiles = List("src/routes/users.ts", "src/controller/auth.js", "src/endpoint/health.py")
      val impact = RepoInspectorImpl.buildImpactMap(tmpDir, changedFiles)

      assertEquals(impact.affectedApiHandlers.size, 3)
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("impact analysis with no changed files produces no ImpactMap") {
    val tmpDir = Files.createTempDirectory("impact-empty-test")
    try {
      val report = RepoInspectorImpl.inspect("run-1", tmpDir, None)
      assert(report.changedSurfaceMap.isEmpty)

      val report2 = RepoInspectorImpl.inspect("run-1", tmpDir, Some(Nil))
      assert(report2.changedSurfaceMap.isEmpty)
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  test("rerun planning uses impact map for infra-sensitivity check") {
    val tmpDir = Files.createTempDirectory("impact-rerun-test")
    try {
      val infraChanges = List("docker-compose.yml", "src/app.ts")
      val impact = RepoInspectorImpl.buildImpactMap(tmpDir, infraChanges)
      assert(impact.infraSensitiveChanges.nonEmpty, "Should detect infra-sensitive changes for rerun planning")

      val noInfraChanges = List("src/app.ts", "src/utils.ts")
      val impact2 = RepoInspectorImpl.buildImpactMap(tmpDir, noInfraChanges)
      assert(impact2.infraSensitiveChanges.isEmpty, "No infra changes should produce empty list")
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val stream = Files.list(path)
      try { stream.forEach(deleteRecursive) } finally { stream.close() }
    }
    Files.deleteIfExists(path)
  }
}
