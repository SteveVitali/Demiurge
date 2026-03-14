package demiurge.requirements

// Phase 4: Requirements YAML domain model
// Represents parsed requirements.yaml content

case class RequirementEntry(
  id:          String,
  `type`:      String,
  description: String,
  selector:    Option[String],
  expected:    Option[String],
  timeoutMs:   Option[Long],
  retry:       Option[Int],
  severity:    Option[String],
)

case class RequirementsFile(
  requirements: List[RequirementEntry],
)
