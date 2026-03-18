package demiurge.repair

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.time.Instant

class PatchProposalSuite extends FunSuite {

  private def withTempDir(testFn: Path => Unit): Unit = {
    val tmpDir = Files.createTempDirectory("patch-test-")
    try {
      // Init git repo so PatchApplier.stageChanges works
      import scala.sys.process._
      Process(Seq("git", "init"), tmpDir.toFile).!
      Process(Seq("git", "config", "user.email", "test@test.com"), tmpDir.toFile).!
      Process(Seq("git", "config", "user.name", "Test"), tmpDir.toFile).!
      Files.write(tmpDir.resolve("README.md"), "# Test\n".getBytes)
      Process(Seq("git", "add", "."), tmpDir.toFile).!
      Process(Seq("git", "commit", "-m", "init"), tmpDir.toFile).!
      testFn(tmpDir)
    } finally {
      deleteRecursive(tmpDir)
    }
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      val entries = Files.list(path)
      try { entries.forEach(p => deleteRecursive(p)) } finally { entries.close() }
    }
    Files.deleteIfExists(path)
  }

  test("PatchProposal.isEmpty returns true when no changes") {
    val proposal = PatchProposal(
      patchId = "p1", runId = "r1", attemptNumber = 1, backendId = "test",
      edits = Nil, newFiles = Nil, deletions = Nil,
      summary = "empty", hypotheses = Nil, createdAt = Instant.now(),
    )
    assert(proposal.isEmpty)
  }

  test("PatchProposal.isEmpty returns false with edits") {
    val proposal = PatchProposal(
      patchId = "p2", runId = "r1", attemptNumber = 1, backendId = "test",
      edits = List(FileEdit("a.txt", "old", "new")),
      newFiles = Nil, deletions = Nil,
      summary = "fix", hypotheses = Nil, createdAt = Instant.now(),
    )
    assert(!proposal.isEmpty)
  }

  test("PatchProposal.filesChanged collects all paths") {
    val proposal = PatchProposal(
      patchId = "p3", runId = "r1", attemptNumber = 1, backendId = "test",
      edits = List(FileEdit("a.txt", "old", "new")),
      newFiles = List(NewFile("b.txt", "content")),
      deletions = List(FileDeletion("c.txt")),
      summary = "fix", hypotheses = Nil, createdAt = Instant.now(),
    )
    assertEquals(proposal.filesChanged.sorted, List("a.txt", "b.txt", "c.txt"))
  }

  test("PatchApplier applies file edits") {
    withTempDir { dir =>
      Files.write(dir.resolve("hello.txt"), "Hello World".getBytes)
      import scala.sys.process._
      Process(Seq("git", "add", "hello.txt"), dir.toFile).!
      Process(Seq("git", "commit", "-m", "add hello"), dir.toFile).!

      val proposal = PatchProposal(
        patchId = "p4", runId = "r1", attemptNumber = 1, backendId = "test",
        edits = List(FileEdit("hello.txt", "World", "Scala")),
        newFiles = Nil, deletions = Nil,
        summary = "fix", hypotheses = Nil, createdAt = Instant.now(),
      )

      val result = PatchApplier.apply(proposal, dir)
      assert(result.isInstanceOf[PatchApplier.ApplySuccess])
      val content = new String(Files.readAllBytes(dir.resolve("hello.txt")))
      assertEquals(content, "Hello Scala")
    }
  }

  test("PatchApplier creates new files") {
    withTempDir { dir =>
      val proposal = PatchProposal(
        patchId = "p5", runId = "r1", attemptNumber = 1, backendId = "test",
        edits = Nil,
        newFiles = List(NewFile("src/new_file.txt", "new content")),
        deletions = Nil,
        summary = "add file", hypotheses = Nil, createdAt = Instant.now(),
      )

      val result = PatchApplier.apply(proposal, dir)
      assert(result.isInstanceOf[PatchApplier.ApplySuccess])
      assert(Files.exists(dir.resolve("src/new_file.txt")))
      val content = new String(Files.readAllBytes(dir.resolve("src/new_file.txt")))
      assertEquals(content, "new content")
    }
  }

  test("PatchApplier deletes files") {
    withTempDir { dir =>
      Files.write(dir.resolve("delete_me.txt"), "bye".getBytes)
      import scala.sys.process._
      Process(Seq("git", "add", "delete_me.txt"), dir.toFile).!
      Process(Seq("git", "commit", "-m", "add file"), dir.toFile).!

      val proposal = PatchProposal(
        patchId = "p6", runId = "r1", attemptNumber = 1, backendId = "test",
        edits = Nil, newFiles = Nil,
        deletions = List(FileDeletion("delete_me.txt")),
        summary = "delete file", hypotheses = Nil, createdAt = Instant.now(),
      )

      val result = PatchApplier.apply(proposal, dir)
      assert(result.isInstanceOf[PatchApplier.ApplySuccess])
      assert(!Files.exists(dir.resolve("delete_me.txt")))
    }
  }

  test("PatchApplier rejects empty proposals") {
    withTempDir { dir =>
      val proposal = PatchProposal(
        patchId = "p7", runId = "r1", attemptNumber = 1, backendId = "test",
        edits = Nil, newFiles = Nil, deletions = Nil,
        summary = "empty", hypotheses = Nil, createdAt = Instant.now(),
      )

      val result = PatchApplier.apply(proposal, dir)
      assert(result.isInstanceOf[PatchApplier.ApplyFailure])
    }
  }

  test("PatchApplier fails for edit on non-existent file") {
    withTempDir { dir =>
      val proposal = PatchProposal(
        patchId = "p8", runId = "r1", attemptNumber = 1, backendId = "test",
        edits = List(FileEdit("nonexistent.txt", "old", "new")),
        newFiles = Nil, deletions = Nil,
        summary = "fix", hypotheses = Nil, createdAt = Instant.now(),
      )

      val result = PatchApplier.apply(proposal, dir)
      assert(result.isInstanceOf[PatchApplier.ApplyFailure])
    }
  }

  test("PatchApplier applies edit with trailing whitespace mismatch (fuzzy matching)") {
    withTempDir { dir =>
      // File has trailing whitespace on lines; oldContent does not
      Files.write(dir.resolve("code.js"), "function hello() {  \n  return 'hi';  \n}\n".getBytes)
      import scala.sys.process._
      Process(Seq("git", "add", "code.js"), dir.toFile).!
      Process(Seq("git", "commit", "-m", "add code"), dir.toFile).!

      val proposal = PatchProposal(
        patchId = "p9", runId = "r1", attemptNumber = 1, backendId = "test",
        edits = List(FileEdit("code.js", "function hello() {\n  return 'hi';\n}", "function hello() {\n  return 'hello';\n}")),
        newFiles = Nil, deletions = Nil,
        summary = "fix greeting", hypotheses = Nil, createdAt = Instant.now(),
      )

      val result = PatchApplier.apply(proposal, dir)
      assert(result.isInstanceOf[PatchApplier.ApplySuccess], s"Expected ApplySuccess but got $result")
      val content = new String(Files.readAllBytes(dir.resolve("code.js")))
      assert(content.contains("return 'hello'"), s"Expected patched content but got: $content")
    }
  }

  test("PatchApplier fails when oldContent not found even with fuzzy matching") {
    withTempDir { dir =>
      Files.write(dir.resolve("data.txt"), "line1\nline2\nline3\n".getBytes)
      import scala.sys.process._
      Process(Seq("git", "add", "data.txt"), dir.toFile).!
      Process(Seq("git", "commit", "-m", "add data"), dir.toFile).!

      val proposal = PatchProposal(
        patchId = "p10", runId = "r1", attemptNumber = 1, backendId = "test",
        edits = List(FileEdit("data.txt", "totally different content", "replacement")),
        newFiles = Nil, deletions = Nil,
        summary = "fix", hypotheses = Nil, createdAt = Instant.now(),
      )

      val result = PatchApplier.apply(proposal, dir)
      assert(result.isInstanceOf[PatchApplier.ApplyFailure])
    }
  }

  test("PatchApplier applies edit with empty oldContent as full file replacement") {
    withTempDir { dir =>
      Files.write(dir.resolve("replace.txt"), "original content\n".getBytes)
      import scala.sys.process._
      Process(Seq("git", "add", "replace.txt"), dir.toFile).!
      Process(Seq("git", "commit", "-m", "add file"), dir.toFile).!

      val proposal = PatchProposal(
        patchId = "p11", runId = "r1", attemptNumber = 1, backendId = "test",
        edits = List(FileEdit("replace.txt", "", "completely new content\n")),
        newFiles = Nil, deletions = Nil,
        summary = "replace file", hypotheses = Nil, createdAt = Instant.now(),
      )

      val result = PatchApplier.apply(proposal, dir)
      assert(result.isInstanceOf[PatchApplier.ApplySuccess])
      val content = new String(Files.readAllBytes(dir.resolve("replace.txt")))
      assertEquals(content, "completely new content\n")
    }
  }
}
