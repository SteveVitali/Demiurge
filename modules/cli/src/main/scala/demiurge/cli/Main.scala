package demiurge.cli

// Phase 7: CLI main entry point — Spec §14.1
object Main {
  def main(args: Array[String]): Unit = {
    val exitCode = CliApp.run(args)
    System.exit(exitCode)
  }
}
