package demiurge.requirements

// Phase 4: Validation rules for parsed requirements
object RequirementsValidation {

  private val validTypes: Set[String] = Set(
    "http", "process", "state", "log", "tcp", "browser", "env_readiness",
  )

  private val validSeverities: Set[String] = Set(
    "required", "important", "nice_to_have",
  )

  def validate(entries: List[RequirementEntry]): Either[String, Unit] = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    // Check for duplicate IDs
    val ids = entries.map(_.id)
    val duplicates = ids.diff(ids.distinct)
    if (duplicates.nonEmpty) {
      errors += s"Duplicate requirement IDs: ${duplicates.distinct.mkString(", ")}"
    }

    entries.foreach { entry =>
      // Validate type
      if (!validTypes.contains(entry.`type`)) {
        errors += s"Requirement '${entry.id}': invalid type '${entry.`type`}'. Valid types: ${validTypes.mkString(", ")}"
      }

      // Validate severity if present
      entry.severity.foreach { sev =>
        if (!validSeverities.contains(sev)) {
          errors += s"Requirement '${entry.id}': invalid severity '$sev'. Valid: ${validSeverities.mkString(", ")}"
        }
      }

      // Validate timeout_ms if present
      entry.timeoutMs.foreach { t =>
        if (t <= 0) errors += s"Requirement '${entry.id}': timeout_ms must be positive"
      }

      // Validate retry if present
      entry.retry.foreach { r =>
        if (r < 0) errors += s"Requirement '${entry.id}': retry must be non-negative"
      }

      // Validate ID is non-empty
      if (entry.id.trim.isEmpty) {
        errors += "Requirement has empty id"
      }
    }

    if (errors.isEmpty) Right(()) else Left(errors.mkString("; "))
  }
}
