package lastmile.inspector

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

  test("emits manifest refs for lastmile.yaml") {
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
      Files.write(root.resolve("lastmile.yaml"), yaml.getBytes)

      val report = RepoInspectorImpl.inspect("test-3", root, None)
      val lastmileRef = report.manifestsFound.find(_.manifestType == "lastmile")
      assert(lastmileRef.isDefined, s"Should find lastmile manifest: ${report.manifestsFound}")
      assert(lastmileRef.get.parsedSuccessfully, s"Should parse successfully: ${lastmileRef.get.parseErrors}")
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

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      val entries = Files.list(path)
      try { entries.forEach(p => deleteRecursive(p)) }
      finally { entries.close() }
    }
    Files.deleteIfExists(path)
  }
}
