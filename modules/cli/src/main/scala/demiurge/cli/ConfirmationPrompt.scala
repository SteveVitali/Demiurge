package demiurge.cli

// Phase C: Interactive confirmation prompt for build mode.
// Reads Y/n from stdin when running in an interactive terminal.
object ConfirmationPrompt {

  /**
   * Ask the user for confirmation. Returns true if they accept.
   * In non-interactive mode (no TTY), returns the default value.
   *
   * @param message the prompt message to display
   * @param default the default answer if user just presses Enter
   * @return true if user confirms
   */
  def confirm(message: String, default: Boolean = true): Boolean = {
    if (!isInteractive) return default

    val hint = if (default) "[Y/n]" else "[y/N]"
    System.err.print(s"$message $hint ")
    System.err.flush()

    try {
      val line = scala.io.StdIn.readLine()
      if (line == null) return default
      val trimmed = line.trim.toLowerCase
      if (trimmed.isEmpty) default
      else trimmed.startsWith("y")
    } catch {
      case _: Exception => default
    }
  }

  /** Check if we're running in an interactive terminal. */
  def isInteractive: Boolean = {
    System.console() != null
  }
}
