package demiurge.license

import java.security.MessageDigest
import java.net.InetAddress

object MachineFingerprint {

  /** Safe hostname lookup with fallback — shared across license module. */
  def hostname(): String = try {
    InetAddress.getLocalHost.getHostName
  } catch {
    case _: Exception => "unknown-host"
  }

  def generate(): String = {
    val osName = System.getProperty("os.name", "unknown")
    val osArch = System.getProperty("os.arch", "unknown")
    val userName = System.getProperty("user.name", "unknown")
    val raw = s"${hostname()}|$osName|$osArch|$userName"
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(raw.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }
}
