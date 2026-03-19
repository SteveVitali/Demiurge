package demiurge.license

import java.security.MessageDigest
import java.net.InetAddress

object MachineFingerprint {
  def generate(): String = {
    val hostname = try {
      InetAddress.getLocalHost.getHostName
    } catch {
      case _: Exception => "unknown-host"
    }
    val osName = System.getProperty("os.name", "unknown")
    val osArch = System.getProperty("os.arch", "unknown")
    val userName = System.getProperty("user.name", "unknown")
    val raw = s"$hostname|$osName|$osArch|$userName"
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(raw.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }
}
