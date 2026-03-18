package demiurge.model

// Spec §10.3: Tool taxonomy for repair backend.
// Defines the tools that may be exposed to the LLM during interactive repair sessions.

// Spec §10.3: Tool definition
case class RepairTool(
  name:             String,
  description:      String,
  category:         ToolCategory,
  requiresApproval: Boolean,
  parameters:       List[ToolParameter],
)

// Spec §10.3: Tool parameter schema
case class ToolParameter(
  name:         String,
  paramType:    String,
  description:  String,
  required:     Boolean,
)

// Spec §10.3: Tool categories
sealed trait ToolCategory
object ToolCategory {
  case object FileRead  extends ToolCategory  // read_file, list_directory, search_files
  case object FileWrite extends ToolCategory  // write_file, create_file, delete_file
  case object Command   extends ToolCategory  // run_command
  case object Browser   extends ToolCategory  // capture_screenshot, navigate
  case object Analysis  extends ToolCategory  // grep_codebase, find_references
}

// Spec §10.3: Tool call record (for transcript)
case class ToolCallRecord(
  toolName:       String,
  arguments:      Map[String, String],
  result:         Option[String],
  durationMs:     Long,
  approved:       Boolean,
)

// Spec §10.3: Standard repair tool definitions
object RepairTools {

  val readFile: RepairTool = RepairTool(
    name = "read_file",
    description = "Read the contents of a file at a given path",
    category = ToolCategory.FileRead,
    requiresApproval = false,
    parameters = List(
      ToolParameter("path", "string", "Relative path to the file", required = true),
      ToolParameter("offset", "integer", "Line offset to start reading from", required = false),
      ToolParameter("limit", "integer", "Maximum number of lines to read", required = false),
    ),
  )

  val writeFile: RepairTool = RepairTool(
    name = "write_file",
    description = "Write content to a file, creating it if necessary",
    category = ToolCategory.FileWrite,
    requiresApproval = false,
    parameters = List(
      ToolParameter("path", "string", "Relative path to the file", required = true),
      ToolParameter("content", "string", "Content to write", required = true),
    ),
  )

  val listDirectory: RepairTool = RepairTool(
    name = "list_directory",
    description = "List files and subdirectories in a directory",
    category = ToolCategory.FileRead,
    requiresApproval = false,
    parameters = List(
      ToolParameter("path", "string", "Relative path to the directory", required = true),
      ToolParameter("maxDepth", "integer", "Maximum depth to recurse", required = false),
    ),
  )

  val searchFiles: RepairTool = RepairTool(
    name = "search_files",
    description = "Search for text patterns across files in the repository",
    category = ToolCategory.Analysis,
    requiresApproval = false,
    parameters = List(
      ToolParameter("query", "string", "Search query or regex pattern", required = true),
      ToolParameter("includes", "string", "Glob pattern to filter files", required = false),
      ToolParameter("path", "string", "Directory to search in", required = false),
    ),
  )

  val runCommand: RepairTool = RepairTool(
    name = "run_command",
    description = "Execute a shell command in the worktree",
    category = ToolCategory.Command,
    requiresApproval = true,
    parameters = List(
      ToolParameter("command", "string", "Command to execute", required = true),
      ToolParameter("cwd", "string", "Working directory (relative to worktree)", required = false),
      ToolParameter("timeoutMs", "integer", "Timeout in milliseconds", required = false),
    ),
  )

  // Spec §10.3: Default tool set for repair sessions
  val defaultToolSet: List[RepairTool] = List(
    readFile, writeFile, listDirectory, searchFiles, runCommand,
  )

  // Spec §10.3: Tool names for policy filtering
  val allToolNames: Set[String] = defaultToolSet.map(_.name).toSet
}
