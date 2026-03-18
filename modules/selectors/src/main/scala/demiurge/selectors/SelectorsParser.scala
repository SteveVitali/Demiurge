package demiurge.selectors

import org.yaml.snakeyaml.Yaml
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

// Phase 4: Parse selectors.yaml into SelectorsFile
object SelectorsParser {

  private val validStrategies: Set[String] = Set(
    "css", "xpath", "role", "text", "test_id", "label",
  )

  def parse(path: Path): Either[String, SelectorsFile] = {
    if (!Files.exists(path)) return Left(s"Selectors file not found: $path")
    parseString(new String(Files.readAllBytes(path), "UTF-8"))
  }

  def parseString(yamlContent: String): Either[String, SelectorsFile] = {
    try {
      val yaml = new Yaml()
      val root = yaml.load[Any](yamlContent)
      root match {
        case map: java.util.Map[_, _] =>
          val scalaMap = map.asScala.toMap.map { case (k, v) => k.toString -> v }
          scalaMap.get("selectors") match {
            case Some(list: java.util.List[_]) =>
              val entries = list.asScala.toList.zipWithIndex.map { case (item, idx) =>
                parseEntry(item, idx)
              }
              val (errors, parsed) = entries.partition(_.isLeft)
              if (errors.nonEmpty) {
                Left(errors.collect { case Left(e) => e }.mkString("; "))
              } else {
                val sels = parsed.collect { case Right(s) => s }
                validate(sels).map(_ => SelectorsFile(sels))
              }
            case Some(_) => Left("'selectors' must be a list")
            case None => Left("Missing 'selectors' key in YAML")
          }
        case _ => Left("YAML root must be a mapping")
      }
    } catch {
      case e: Exception => Left(s"YAML parse error: ${e.getMessage}")
    }
  }

  private def parseEntry(item: Any, idx: Int): Either[String, SelectorEntry] = {
    item match {
      case map: java.util.Map[_, _] =>
        val m = map.asScala.toMap.map { case (k, v) => k.toString -> v }
        for {
          id <- getRequired(m, "id", idx)
          strategy <- getRequired(m, "strategy", idx)
          value <- getRequired(m, "value", idx)
        } yield SelectorEntry(
          id = id,
          strategy = strategy,
          value = value,
          label = m.get("label").collect { case v if v != null => v.toString },
        )
      case _ => Left(s"Selector at index $idx must be a mapping")
    }
  }

  private def getRequired(m: Map[String, Any], key: String, idx: Int): Either[String, String] = {
    m.get(key) match {
      case Some(v) if v != null => Right(v.toString)
      case _ => Left(s"Selector at index $idx: missing required field '$key'")
    }
  }

  private def validate(entries: List[SelectorEntry]): Either[String, Unit] = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    val ids = entries.map(_.id)
    val duplicates = ids.diff(ids.distinct)
    if (duplicates.nonEmpty) {
      errors += s"Duplicate selector IDs: ${duplicates.distinct.mkString(", ")}"
    }

    entries.foreach { entry =>
      if (!validStrategies.contains(entry.strategy)) {
        errors += s"Selector '${entry.id}': invalid strategy '${entry.strategy}'. Valid: ${validStrategies.mkString(", ")}"
      }
      if (entry.id.trim.isEmpty) {
        errors += "Selector has empty id"
      }
      if (entry.value.trim.isEmpty) {
        errors += s"Selector '${entry.id}': value must not be empty"
      }
    }

    if (errors.isEmpty) Right(()) else Left(errors.mkString("; "))
  }
}
