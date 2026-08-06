package demiurge.cli.Commands

import demiurge.cli.ExitCodes
import demiurge.cli.CommandParsers.GlobalOpts
import demiurge.license.CredentialStore

object LogoutCommand {
  def execute(global: GlobalOpts): Int = {
    CredentialStore.clearCredentials()
    System.out.println("Logged out. Credentials cleared.")
    ExitCodes.Success
  }
}
