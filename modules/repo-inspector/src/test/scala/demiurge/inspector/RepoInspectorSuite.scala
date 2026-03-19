package demiurge.inspector

import munit.FunSuite
import java.nio.file.{Files, Path}

class RepoInspectorSuite extends FunSuite {

  private def withTempDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("inspector-test-")
    try { testFn(tmpDir) }
    finally { deleteRecursive(tmpDir) }
  }

  test("detects compose file") {
    withTempDir { root =>
      Files.write(root.resolve("docker-compose.yml"), "version: '3'\nservices:\n  db:\n    image: postgres\n".getBytes)
      val report = RepoInspectorImpl.inspect("test-1", root, None)
      assert(report.manifestsFound.exists(_.manifestType == "compose"),
        s"Should detect compose file: ${report.manifestsFound}")
    }
  }

  test("detects package.json") {
    withTempDir { root =>
      Files.write(root.resolve("package.json"),
        """{"name":"test","scripts":{"start":"node index.js"},"dependencies":{"express":"^4.0.0"}}""".getBytes)
      val report = RepoInspectorImpl.inspect("test-2", root, None)
      assert(report.manifestsFound.exists(_.manifestType == "npm"),
        s"Should detect npm manifest: ${report.manifestsFound}")
      assert(report.languages.exists(_.value == "javascript"),
        s"Should detect JavaScript: ${report.languages}")
      assert(report.frameworks.exists(_.value == "express"),
        s"Should detect Express framework: ${report.frameworks}")
      assert(report.startupCommands.exists(_.value == "npm start"),
        s"Should detect npm start command: ${report.startupCommands}")
    }
  }

  test("emits manifest refs for demiurge.yaml") {
    withTempDir { root =>
      val yaml =
        """version: 1
          |app:
          |  type: web
          |  root_url: http://localhost:3000
          |services:
          |  api:
          |    kind: api
          |    startup_mode: script
          |    startup_command: "node server.js"
          |    ports:
          |      - container: 3000
          |    readiness:
          |      probe_type: http
          |      target: http://localhost:3000/health
          |""".stripMargin
      Files.write(root.resolve("demiurge.yaml"), yaml.getBytes)

      val report = RepoInspectorImpl.inspect("test-3", root, None)
      val demiurgeRef = report.manifestsFound.find(_.manifestType == "demiurge")
      assert(demiurgeRef.isDefined, s"Should find demiurge manifest: ${report.manifestsFound}")
      assert(demiurgeRef.get.parsedSuccessfully, s"Should parse successfully: ${demiurgeRef.get.parseErrors}")
    }
  }

  test("produces deterministic report for same fixture repo") {
    withTempDir { root =>
      Files.write(root.resolve("package.json"),
        """{"name":"test","scripts":{"start":"node index.js"},"dependencies":{"express":"^4.0.0"}}""".getBytes)
      Files.write(root.resolve("docker-compose.yml"), "version: '3'\n".getBytes)

      val report1 = RepoInspectorImpl.inspect("run-1", root, None)
      val report2 = RepoInspectorImpl.inspect("run-1", root, None)

      // Languages, frameworks, manifests should be identical
      assertEquals(report1.languages.map(_.value).sorted, report2.languages.map(_.value).sorted)
      assertEquals(report1.frameworks.map(_.value).sorted, report2.frameworks.map(_.value).sorted)
      assertEquals(report1.manifestsFound.map(_.manifestType).sorted, report2.manifestsFound.map(_.manifestType).sorted)
      assertEquals(report1.startupCommands.map(_.value).sorted, report2.startupCommands.map(_.value).sorted)
    }
  }

  test("detects candidate services from package.json with start script") {
    withTempDir { root =>
      Files.write(root.resolve("package.json"),
        """{"name":"test","scripts":{"start":"node index.js"},"dependencies":{"express":"^4.0.0"}}""".getBytes)
      val report = RepoInspectorImpl.inspect("test-5", root, None)
      assert(report.candidateServices.exists(_.serviceId == "node-app"),
        s"Should detect node-app candidate: ${report.candidateServices}")
    }
  }

  test("returns empty report for empty directory") {
    withTempDir { root =>
      val report = RepoInspectorImpl.inspect("empty-1", root, None)
      assert(report.languages.isEmpty)
      assert(report.frameworks.isEmpty)
      assert(report.candidateServices.isEmpty)
      assert(report.manifestsFound.isEmpty)
    }
  }

  test("detects database dependencies from package.json") {
    withTempDir { root =>
      Files.write(root.resolve("package.json"),
        """{"name":"test","dependencies":{"pg":"^8.0.0","redis":"^4.0.0"}}""".getBytes)
      val report = RepoInspectorImpl.inspect("db-1", root, None)
      assert(report.dbDependencies.exists(_.value == "postgresql"),
        s"Should detect PostgreSQL: ${report.dbDependencies}")
      assert(report.dbDependencies.exists(_.value == "redis"),
        s"Should detect Redis: ${report.dbDependencies}")
    }
  }

  test("detects database dependencies from compose file") {
    withTempDir { root =>
      val compose = "services:\n  db:\n    image: postgres:15\n  cache:\n    image: redis:7\n"
      Files.write(root.resolve("docker-compose.yml"), compose.getBytes)
      val report = RepoInspectorImpl.inspect("db-2", root, None)
      assert(report.dbDependencies.exists(_.value == "postgresql"),
        s"Should detect PostgreSQL from compose: ${report.dbDependencies}")
      assert(report.dbDependencies.exists(_.value == "redis"),
        s"Should detect Redis from compose: ${report.dbDependencies}")
    }
  }

  test("detects auth hints from package.json") {
    withTempDir { root =>
      Files.write(root.resolve("package.json"),
        """{"name":"test","dependencies":{"passport":"^0.6.0","jsonwebtoken":"^9.0.0"}}""".getBytes)
      val report = RepoInspectorImpl.inspect("auth-1", root, None)
      assert(report.authHints.exists(_.value == "passport"),
        s"Should detect passport: ${report.authHints}")
      assert(report.authHints.exists(_.value == "jwt"),
        s"Should detect JWT: ${report.authHints}")
    }
  }

  test("detects test frameworks from package.json") {
    withTempDir { root =>
      Files.write(root.resolve("package.json"),
        """{"name":"test","devDependencies":{"jest":"^29.0.0","@playwright/test":"^1.40.0"}}""".getBytes)
      val report = RepoInspectorImpl.inspect("test-fw-1", root, None)
      assert(report.testFrameworkHints.exists(_.value == "jest"),
        s"Should detect jest: ${report.testFrameworkHints}")
      assert(report.testFrameworkHints.exists(_.value == "playwright"),
        s"Should detect playwright: ${report.testFrameworkHints}")
    }
  }

  test("detects monorepo from workspaces in package.json") {
    withTempDir { root =>
      Files.write(root.resolve("package.json"),
        """{"name":"monorepo","workspaces":["packages/*"]}""".getBytes)
      val report = RepoInspectorImpl.inspect("mono-1", root, None)
      assert(report.warnings.exists(_.contains("Monorepo")),
        s"Should warn about monorepo: ${report.warnings}")
    }
  }

  test("detects monorepo from lerna.json") {
    withTempDir { root =>
      Files.write(root.resolve("lerna.json"), "{}".getBytes)
      val report = RepoInspectorImpl.inspect("mono-2", root, None)
      assert(report.warnings.exists(_.contains("Monorepo")),
        s"Should warn about monorepo: ${report.warnings}")
    }
  }

  test("detects compose services as candidate services with correct kinds") {
    withTempDir { root =>
      val compose = "services:\n  api:\n    image: node:20\n    ports:\n      - \"3000:3000\"\n  db:\n    image: postgres:15\n    ports:\n      - \"5432:5432\"\n"
      Files.write(root.resolve("docker-compose.yml"), compose.getBytes)
      val report = RepoInspectorImpl.inspect("compose-svc-1", root, None)
      val apiSvc = report.candidateServices.find(_.serviceId == "api")
      val dbSvc = report.candidateServices.find(_.serviceId == "db")
      assert(apiSvc.isDefined, s"Should find api service: ${report.candidateServices}")
      assert(dbSvc.isDefined, s"Should find db service: ${report.candidateServices}")
      assertEquals(dbSvc.get.kind, demiurge.model.ServiceKind.Db)
    }
  }

  test("detects Dockerfile as manifest") {
    withTempDir { root =>
      Files.write(root.resolve("Dockerfile"), "FROM node:20\n".getBytes)
      val report = RepoInspectorImpl.inspect("docker-1", root, None)
      assert(report.manifestsFound.exists(_.manifestType == "dockerfile"),
        s"Should detect Dockerfile: ${report.manifestsFound}")
    }
  }

  test("detects Python frameworks from requirements.txt") {
    withTempDir { root =>
      Files.write(root.resolve("requirements.txt"), "django==4.2\ncelery==5.3\n".getBytes)
      val report = RepoInspectorImpl.inspect("python-1", root, None)
      assert(report.frameworks.exists(_.value == "django"),
        s"Should detect Django: ${report.frameworks}")
      assert(report.languages.exists(_.value == "python"),
        s"Should detect Python: ${report.languages}")
    }
  }

  test("detects candidate service port from compose ports config") {
    withTempDir { root =>
      val compose = "services:\n  web:\n    image: nginx\n    ports:\n      - \"8080:80\"\n"
      Files.write(root.resolve("docker-compose.yml"), compose.getBytes)
      val report = RepoInspectorImpl.inspect("port-1", root, None)
      val webSvc = report.candidateServices.find(_.serviceId == "web")
      assert(webSvc.isDefined, s"Should find web service: ${report.candidateServices}")
      assertEquals(webSvc.get.portHint, Some(8080))
    }
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      val entries = Files.list(path)
      try { entries.forEach(p => deleteRecursive(p)) }
      finally { entries.close() }
    }
    Files.deleteIfExists(path)
  }
}
