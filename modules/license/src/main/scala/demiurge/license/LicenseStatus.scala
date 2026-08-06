package demiurge.license

sealed trait LicenseStatus
object LicenseStatus {
  case class Valid(
    planTier: String,
    uses: Int,
    maxUses: Int,
    expiry: String,
    entitlements: List[String]
  ) extends LicenseStatus

  case class Expired(expiry: String) extends LicenseStatus
  case class Suspended(reason: String) extends LicenseStatus
  case class OverLimit(uses: Int, maxUses: Int) extends LicenseStatus
  case object MachineNotActivated extends LicenseStatus
  case object TooManyMachines extends LicenseStatus
  case object NotFound extends LicenseStatus
  case object NoCredentials extends LicenseStatus
  case class NetworkError(message: String) extends LicenseStatus
}
