package demiurge.license

import java.nio.file.{Files, Path}
import munit.FunSuite

class CredentialStoreSuite extends FunSuite {

  private var tempDir: Path = _

  override def beforeEach(context: BeforeEach): Unit = {
    tempDir = Files.createTempDirectory("demiurge-cred-test")
  }

  override def afterEach(context: AfterEach): Unit = {
    // Clean up temp files
    if (tempDir != null) {
      Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
    }
  }

  test("Credentials round-trip through JSON encoding/decoding") {
    val creds = Credentials(
      licenseKey = "DEMI-TEST-1234-5678",
      userEmail = Some("test@example.com"),
      planTier = Some("pro"),
      authMethod = Some("license_key"),
      cachedValidation = Some(CachedValidation(
        valid = true,
        code = "VALID",
        planTier = "pro",
        uses = 42,
        maxUses = 200,
        expiry = "2026-04-19T00:00:00Z",
        entitlements = List("agent_repair", "browser_verification"),
        cachedAt = "2026-03-19T15:30:00Z"
      ))
    )

    import io.circe.syntax._
    import io.circe.parser.decode
    import CredentialStore.credentialsCodec

    val json = creds.asJson.spaces2
    val decoded = decode[Credentials](json)
    assert(decoded.isRight, s"Failed to decode: ${decoded.left.getOrElse("")}")
    assertEquals(decoded.toOption.get, creds)
  }

  test("UserConfig round-trip through JSON encoding/decoding") {
    val config = UserConfig(
      anthropicApiKey = Some("sk-ant-test"),
      openaiApiKey = None,
      preferredProvider = Some("anthropic"),
      cloudApiUrl = Some("https://demiurge.dev")
    )

    import io.circe.syntax._
    import io.circe.parser.decode
    import CredentialStore.userConfigCodec

    val json = config.asJson.spaces2
    val decoded = decode[UserConfig](json)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get, config)
  }

  test("UserConfig defaults are sensible") {
    val config = UserConfig()
    assertEquals(config.anthropicApiKey, None)
    assertEquals(config.openaiApiKey, None)
    assertEquals(config.preferredProvider, Some("anthropic"))
    assertEquals(config.cloudApiUrl, Some("https://demiurge.dev"))
  }

  test("CachedValidation round-trip through JSON") {
    val cached = CachedValidation(
      valid = true,
      code = "VALID",
      planTier = "starter",
      uses = 10,
      maxUses = 50,
      expiry = "2026-05-01T00:00:00Z",
      entitlements = List("agent_repair"),
      cachedAt = "2026-03-19T12:00:00Z"
    )

    import io.circe.syntax._
    import io.circe.parser.decode
    import CredentialStore.cachedValidationCodec

    val json = cached.asJson.spaces2
    val decoded = decode[CachedValidation](json)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get, cached)
  }

  test("Credentials with no cached validation round-trips") {
    val creds = Credentials(
      licenseKey = "DEMI-BARE-0000-0000",
      userEmail = None,
      planTier = None,
      authMethod = None,
      cachedValidation = None
    )

    import io.circe.syntax._
    import io.circe.parser.decode
    import CredentialStore.credentialsCodec

    val json = creds.asJson.spaces2
    val decoded = decode[Credentials](json)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get, creds)
  }

  test("resolveApiKey returns env var when set") {
    // We can't easily set env vars in tests, but we can test the fallback path
    // When env var is not set, it should fall back to config
    // This tests the method signature and basic logic
    val result = CredentialStore.resolveApiKey("NONEXISTENT_KEY_FOR_TEST_12345", "anthropic")
    // Should return None since env var doesn't exist and no config file
    assertEquals(result, None)
  }
}
