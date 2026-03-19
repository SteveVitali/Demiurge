package demiurge.license

import java.time.{Duration, Instant}

object LicenseManager {

  private val CACHE_TTL_HOURS = 72 // Offline grace period
  private val ONLINE_CACHE_TTL_MINUTES = 30 // Re-validate every 30 min when online

  /**
   * Validate the user's license. Called before every `run` or `build` command.
   * Returns LicenseStatus.Valid on success, or an error status.
   *
   * Flow:
   * 1. Load credentials from ~/.demiurge/credentials.json
   * 2. If no credentials → return NoCredentials
   * 3. If cached validation exists and is fresh enough → return cached
   * 4. Call cloud API to validate
   * 5. If network error and cached validation < 72h old → return cached (offline grace)
   * 6. If network error and no cache → return NetworkError
   * 7. On success → update cache → return Valid
   * 8. If MachineNotActivated → auto-activate → retry
   */
  def validate(): LicenseStatus = {
    val creds = CredentialStore.loadCredentials() match {
      case Some(c) => c
      case None    => return LicenseStatus.NoCredentials
    }

    val fingerprint = CredentialStore.getMachineFingerprint()

    // Check cache freshness
    creds.cachedValidation match {
      case Some(cached) if isFresh(cached.cachedAt, ONLINE_CACHE_TTL_MINUTES) && cached.valid =>
        return LicenseStatus.Valid(cached.planTier, cached.uses, cached.maxUses, cached.expiry, cached.entitlements)
      case _ => // Cache stale or missing, need to validate online
    }

    // Online validation
    CloudApiClient.validateLicense(creds.licenseKey, fingerprint) match {
      case valid: LicenseStatus.Valid =>
        // Update cache
        val newCached = CachedValidation(
          valid = true, code = "VALID", planTier = valid.planTier,
          uses = valid.uses, maxUses = valid.maxUses,
          expiry = valid.expiry, entitlements = valid.entitlements,
          cachedAt = Instant.now.toString
        )
        CredentialStore.saveCredentials(creds.copy(
          planTier = Some(valid.planTier),
          cachedValidation = Some(newCached)
        ))
        valid

      case LicenseStatus.MachineNotActivated =>
        // Auto-activate this machine
        val hostname = try {
          java.net.InetAddress.getLocalHost.getHostName
        } catch {
          case _: Exception => "unknown-host"
        }
        val platform = System.getProperty("os.name", "unknown")
        CloudApiClient.activateMachine(creds.licenseKey, fingerprint, hostname, platform) match {
          case Right(_) =>
            // Retry validation after activation
            CloudApiClient.validateLicense(creds.licenseKey, fingerprint) match {
              case valid: LicenseStatus.Valid =>
                val newCached = CachedValidation(
                  valid = true, code = "VALID", planTier = valid.planTier,
                  uses = valid.uses, maxUses = valid.maxUses,
                  expiry = valid.expiry, entitlements = valid.entitlements,
                  cachedAt = Instant.now.toString
                )
                CredentialStore.saveCredentials(creds.copy(
                  planTier = Some(valid.planTier),
                  cachedValidation = Some(newCached)
                ))
                valid
              case other => other
            }
          case Left(err) => LicenseStatus.NetworkError(s"Machine activation failed: $err")
        }

      case LicenseStatus.NetworkError(_) =>
        // Offline fallback: use cached validation if within grace period
        creds.cachedValidation match {
          case Some(cached) if isWithinGracePeriod(cached.cachedAt) && cached.valid =>
            System.err.println("[demiurge] Warning: Using cached license validation (offline mode)")
            LicenseStatus.Valid(cached.planTier, cached.uses, cached.maxUses, cached.expiry, cached.entitlements)
          case _ =>
            LicenseStatus.NetworkError("Cannot validate license: no network and cache expired")
        }

      case other => other
    }
  }

  private def isFresh(cachedAt: String, minutes: Int): Boolean = {
    try {
      val cached = Instant.parse(cachedAt)
      Duration.between(cached, Instant.now).toMinutes < minutes
    } catch { case _: Exception => false }
  }

  private def isWithinGracePeriod(cachedAt: String): Boolean = {
    try {
      val cached = Instant.parse(cachedAt)
      Duration.between(cached, Instant.now).toHours < CACHE_TTL_HOURS
    } catch { case _: Exception => false }
  }
}
