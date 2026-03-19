package demiurge.license

import munit.FunSuite

class MachineFingerprintSuite extends FunSuite {

  test("generate returns a 64-character hex string (SHA-256)") {
    val fp = MachineFingerprint.generate()
    assertEquals(fp.length, 64)
    assert(fp.matches("[0-9a-f]{64}"), s"Expected hex string, got: $fp")
  }

  test("generate is deterministic for same machine") {
    val fp1 = MachineFingerprint.generate()
    val fp2 = MachineFingerprint.generate()
    assertEquals(fp1, fp2)
  }
}
