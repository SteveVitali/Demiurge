package demiurge.cli.Commands

import demiurge.cli.ExitCodes
import demiurge.cli.CommandParsers.{GlobalOpts, LoginCmd}
import demiurge.license.{CloudApiClient, CredentialStore, Credentials, LicenseStatus, MachineFingerprint}

object LoginCommand {

  def execute(cmd: LoginCmd, global: GlobalOpts): Int = {
    cmd.licenseKey match {
      case Some(key) => loginWithKey(key)
      case None      => loginWithDeviceCode()
    }
  }

  private def loginWithKey(key: String): Int = {
    System.out.println("Validating license key...")
    val fingerprint = CredentialStore.getMachineFingerprint()

    CloudApiClient.validateLicense(key, fingerprint) match {
      case valid: LicenseStatus.Valid =>
        CredentialStore.saveCredentials(Credentials(
          licenseKey = key,
          userEmail = None,
          planTier = Some(valid.planTier),
          authMethod = Some("license_key"),
          cachedValidation = None
        ))
        System.out.println(s"\u2713 Logged in successfully (${valid.planTier} plan)")
        ExitCodes.Success

      case LicenseStatus.MachineNotActivated =>
        // Auto-activate then retry
        val platform = System.getProperty("os.name", "unknown")
        CloudApiClient.activateMachine(key, fingerprint, MachineFingerprint.hostname(), platform) match {
          case Right(_) =>
            CredentialStore.saveCredentials(Credentials(
              licenseKey = key, userEmail = None,
              planTier = None, authMethod = Some("license_key"),
              cachedValidation = None
            ))
            System.out.println("\u2713 Machine activated and logged in")
            ExitCodes.Success
          case Left(err) =>
            System.err.println(s"Error: $err")
            ExitCodes.Errored
        }

      case other =>
        System.err.println(s"Error: License validation failed \u2014 $other")
        ExitCodes.Errored
    }
  }

  private def loginWithDeviceCode(): Int = {
    CloudApiClient.startDeviceAuth() match {
      case Left(err) =>
        System.err.println(s"Error: $err")
        ExitCodes.Errored

      case Right(device) =>
        System.out.println()
        System.out.println(s"  Visit: ${device.verificationUrl}")
        System.out.println(s"  Enter code: ${device.userCode}")
        System.out.println()
        System.out.println("Waiting for authorization...")

        val deadline = System.currentTimeMillis + (device.expiresIn * 1000L)
        val pollMs = device.pollInterval * 1000L

        while (System.currentTimeMillis < deadline) {
          Thread.sleep(pollMs)
          CloudApiClient.pollDeviceAuth(device.deviceCode) match {
            case Right(poll) if poll.status == "authorized" =>
              val key = poll.licenseKey.getOrElse("")
              CredentialStore.saveCredentials(Credentials(
                licenseKey = key,
                userEmail = poll.userEmail,
                planTier = poll.planTier,
                authMethod = Some("device_code"),
                cachedValidation = None
              ))
              System.out.println(s"\u2713 Logged in as ${poll.userEmail.getOrElse("user")} (${poll.planTier.getOrElse("trial")} plan)")
              return ExitCodes.Success

            case Right(poll) if poll.status == "expired" =>
              System.err.println("Error: Authorization code expired. Try again.")
              return ExitCodes.Errored

            case Right(_) => // pending, keep polling
              System.out.print(".")

            case Left(_) =>
              // Network error during poll, keep trying
              System.err.print("!")
          }
        }

        System.err.println("\nError: Authorization timed out. Try again.")
        ExitCodes.Errored
    }
  }
}
