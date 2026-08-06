package demiurge.license

import munit.FunSuite

class LicenseStatusSuite extends FunSuite {

  test("LicenseStatus.Valid contains expected fields") {
    val valid = LicenseStatus.Valid(
      planTier = "pro",
      uses = 42,
      maxUses = 200,
      expiry = "2026-04-19T00:00:00Z",
      entitlements = List("agent_repair", "browser_verification", "build_mode")
    )
    assertEquals(valid.planTier, "pro")
    assertEquals(valid.uses, 42)
    assertEquals(valid.maxUses, 200)
    assertEquals(valid.entitlements.length, 3)
  }

  test("LicenseStatus.Expired contains expiry date") {
    val expired = LicenseStatus.Expired("2025-01-01T00:00:00Z")
    assertEquals(expired.expiry, "2025-01-01T00:00:00Z")
  }

  test("LicenseStatus.OverLimit contains usage info") {
    val over = LicenseStatus.OverLimit(200, 200)
    assertEquals(over.uses, 200)
    assertEquals(over.maxUses, 200)
  }

  test("LicenseStatus.NetworkError contains message") {
    val err = LicenseStatus.NetworkError("Connection refused")
    assertEquals(err.message, "Connection refused")
  }

  test("LicenseStatus sealed trait covers all cases") {
    val statuses: List[LicenseStatus] = List(
      LicenseStatus.Valid("pro", 0, 100, "2026-12-31", Nil),
      LicenseStatus.Expired("2025-01-01"),
      LicenseStatus.Suspended("violation"),
      LicenseStatus.OverLimit(100, 100),
      LicenseStatus.MachineNotActivated,
      LicenseStatus.TooManyMachines,
      LicenseStatus.NotFound,
      LicenseStatus.NoCredentials,
      LicenseStatus.NetworkError("timeout"),
    )
    assertEquals(statuses.length, 9)
  }

  test("CloudApiClient.ValidateResponse decodes correctly") {
    import io.circe.parser.decode
    import CloudApiClient.validateResponseDecoder

    val json = """{"valid":true,"code":"VALID","planTier":"pro","uses":5,"maxUses":200,"expiry":"2026-12-31","entitlements":["agent_repair"]}"""
    val result = decode[CloudApiClient.ValidateResponse](json)
    assert(result.isRight, s"Failed to decode: ${result.left.getOrElse("")}")
    val resp = result.toOption.get
    assert(resp.valid)
    assertEquals(resp.planTier, "pro")
    assertEquals(resp.uses, 5)
    assertEquals(resp.entitlements, List("agent_repair"))
  }

  test("CloudApiClient.DeviceCodeResponse decodes correctly") {
    import io.circe.parser.decode
    import CloudApiClient.deviceCodeResponseDecoder

    val json = """{"deviceCode":"abc123","userCode":"DEMI-1234","verificationUrl":"https://demiurge.dev/activate","expiresIn":900,"pollInterval":5}"""
    val result = decode[CloudApiClient.DeviceCodeResponse](json)
    assert(result.isRight)
    val resp = result.toOption.get
    assertEquals(resp.deviceCode, "abc123")
    assertEquals(resp.userCode, "DEMI-1234")
    assertEquals(resp.pollInterval, 5)
  }

  test("CloudApiClient.DevicePollResponse decodes correctly") {
    import io.circe.parser.decode
    import CloudApiClient.devicePollResponseDecoder

    val json = """{"status":"authorized","licenseKey":"DEMI-XXXX","planTier":"pro","userEmail":"test@example.com"}"""
    val result = decode[CloudApiClient.DevicePollResponse](json)
    assert(result.isRight)
    val resp = result.toOption.get
    assertEquals(resp.status, "authorized")
    assertEquals(resp.licenseKey, Some("DEMI-XXXX"))
    assertEquals(resp.userEmail, Some("test@example.com"))
  }

  test("CloudApiClient.DevicePollResponse decodes pending status") {
    import io.circe.parser.decode
    import CloudApiClient.devicePollResponseDecoder

    val json = """{"status":"pending","licenseKey":null,"planTier":null,"userEmail":null}"""
    val result = decode[CloudApiClient.DevicePollResponse](json)
    assert(result.isRight)
    val resp = result.toOption.get
    assertEquals(resp.status, "pending")
    assertEquals(resp.licenseKey, None)
  }
}
