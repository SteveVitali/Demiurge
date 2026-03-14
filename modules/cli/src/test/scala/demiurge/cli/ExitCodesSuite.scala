package demiurge.cli

import munit.FunSuite
import demiurge.model.RunStatus

// Phase 7: Exit code mapping tests — Spec §14.3
class ExitCodesSuite extends FunSuite {

  test("run command exit code: success maps to 0") {
    assertEquals(ExitCodes.fromRunStatus(RunStatus.Succeeded), 0)
  }

  test("run command exit code: exhausted maps to 1") {
    assertEquals(ExitCodes.fromRunStatus(RunStatus.Exhausted), 1)
  }

  test("run command exit code: cancelled maps to 2") {
    assertEquals(ExitCodes.fromRunStatus(RunStatus.Cancelled), 2)
  }

  test("run command exit code: interrupted maps to 2") {
    assertEquals(ExitCodes.fromRunStatus(RunStatus.Interrupted), 2)
  }

  test("run command exit code: other status maps to 3 (errored)") {
    assertEquals(ExitCodes.fromRunStatus(RunStatus.Verifying), 3)
    assertEquals(ExitCodes.fromRunStatus(RunStatus.Created), 3)
  }

  test("exit code constants match canonical spec") {
    assertEquals(ExitCodes.Success, 0)
    assertEquals(ExitCodes.Exhausted, 1)
    assertEquals(ExitCodes.Cancelled, 2)
    assertEquals(ExitCodes.Errored, 3)
    assertEquals(ExitCodes.InputError, 4)
    assertEquals(ExitCodes.ConcurrentRunConflict, 5)
    assertEquals(ExitCodes.ResumeFailed, 10)
  }

  test("non-run command exit codes") {
    assertEquals(ExitCodes.CommandFailure, 1)
    assertEquals(ExitCodes.NotFound, 4)
  }
}
