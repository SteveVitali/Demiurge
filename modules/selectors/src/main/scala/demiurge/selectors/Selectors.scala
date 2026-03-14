package demiurge.selectors

// Phase 4: Selectors YAML domain model
// Represents parsed selectors.yaml content

case class SelectorEntry(
  id:       String,
  strategy: String,
  value:    String,
  label:    Option[String],
)

case class SelectorsFile(
  selectors: List[SelectorEntry],
)
