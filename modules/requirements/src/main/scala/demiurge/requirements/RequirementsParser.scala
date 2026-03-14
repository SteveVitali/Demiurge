package demiurge.requirements

import org.yaml.snakeyaml.Yaml
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

// Phase 4: Parse requirements.yaml into RequirementsFile
object RequirementsParser {

  def parse(path: Path): Either[String, RequirementsFile] = {
    if (!Files.exists(path)) return Left(s"Requirements file not found: $path")
    parseString(new String(Files.readAllBytes(path), "UTF-8"))
  }

  def parseString(yamlContent: String): Either[String, RequirementsFile] = {
    try {
      val yaml = new Yaml()
      val root = yaml.load[Any](yamlContent)
      root match {
        case map: java.util.Map[_, _] =>
          val scalaMap = map.asScala.toMap.map { case (k, v) => k.toString -> v }
          scalaMap.get("requirements") match {
            case Some(list: java.util.List[_]) =>
              val entries = list.asScala.toList.zipWithIndex.map { case (item, idx) =>
                parseEntry(item, idx)
              }
              val (errors, parsed) = entries.partition(_.isLeft)
              if (errors.nonEmpty) {
                Left(errors.collect { case Left(e) => e }.mkString("; "))
              } else {
                val reqs = parsed.collect { case Right(r) => r }
                RequirementsValidation.validate(reqs).map(_ => RequirementsFile(reqs))
              }
            case Some(_) => Left("'requirements' must be a list")
            case None => Left("Missing 'requirements' key in YAML")
          }
        case _ => Left("YAML root must be a mapping")
      }
    } catch {
      case e: Exception => Left(s"YAML parse error: ${e.getMessage}")
    }
  }

  private def parseEntry(item: Any, idx: Int): Either[String, RequirementEntry] = {
    item match {
      case map: java.util.Map[_, _] =>
        val m = map.asScala.toMap.map { case (k, v) => k.toString -> v }
        for {
          id <- getRequired(m, "id", idx)
          tpe <- getRequired(m, "type", idx)
          desc <- getRequired(m, "description", idx)
        } yield RequirementEntry(
          id = id,
          `type` = tpe,
          description = desc,
          selector = getOptionalString(m, "selector"),
          expected = getOptionalString(m, "expected"),
          timeoutMs = getOptionalLong(m, "timeout_ms"),
          retry = getOptionalInt(m, "retry"),
          severity = getOptionalString(m, "severity"),
        )
      case _ => Left(s"Requirement at index $idx must be a mapping")
    }
  }

  private def getRequired(m: Map[String, Any], key: String, idx: Int): Either[String, String] = {
    m.get(key) match {
      case Some(v) if v != null => Right(v.toString)
      case _ => Left(s"Requirement at index $idx: missing required field '$key'")
    }
  }

  private def getOptionalString(m: Map[String, Any], key: String): Option[String] =
    m.get(key).collect { case v if v != null => v.toString }

  private def getOptionalLong(m: Map[String, Any], key: String): Option[Long] =
    m.get(key).flatMap {
      case v: java.lang.Number => Some(v.longValue())
      case v: String => scala.util.Try(v.toLong).toOption
      case _ => None
    }

  private def getOptionalInt(m: Map[String, Any], key: String): Option[Int] =
    m.get(key).flatMap {
      case v: java.lang.Number => Some(v.intValue())
      case v: String => scala.util.Try(v.toInt).toOption
      case _ => None
    }
}
