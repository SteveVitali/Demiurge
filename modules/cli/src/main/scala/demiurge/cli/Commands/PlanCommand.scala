package demiurge.cli.Commands

import java.sql.Connection

import demiurge.cli._
import demiurge.cli.CommandParsers._
import demiurge.model._
import demiurge.persistence._
import demiurge.inspector.RepoInspectorImpl
import demiurge.planner.EnvironmentPlannerImpl

// Phase 10: `demiurge plan` command — real planning via repo inspection + compilation + env planning
object PlanCommand {

  def execute(cmd: PlanCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    val runId = s"plan-${java.util.UUID.randomUUID().toString.take(8)}"

    // Step 1: Real repo inspection
    if (!global.quiet) System.err.println("Inspecting repository...")
    val inspection = RepoInspectorImpl.inspect(runId, global.repo, cmd.changedFiles)

    // Step 2: Real requirement compilation
    if (!global.quiet) System.err.println("Compiling requirements...")
    val compiler = RunCommand.buildCompiler(global.repo)
    val graph = compiler.compile(runId, inspection, cmd.task)

    // Step 3: Real environment planning
    if (!global.quiet) System.err.println("Planning environment...")
    val plan = EnvironmentPlannerImpl.plan(runId, inspection, graph)

    // Output the plan
    val output = global.format match {
      case OutputFormat.Json =>
        import io.circe.syntax._
        import demiurge.model.JsonCodecs._
        io.circe.Json.obj(
          "command" -> io.circe.Json.fromString("plan"),
          "task" -> io.circe.Json.fromString(cmd.task),
          "repo" -> io.circe.Json.fromString(global.repo.toString),
          "inspection" -> io.circe.Json.obj(
            "languages" -> io.circe.Json.arr(inspection.languages.map(l => io.circe.Json.fromString(l.value)): _*),
            "frameworks" -> io.circe.Json.arr(inspection.frameworks.map(f => io.circe.Json.fromString(f.value)): _*),
            "manifests" -> io.circe.Json.arr(inspection.manifestsFound.map(m => io.circe.Json.fromString(s"${m.manifestType}:${m.relativePath}")): _*),
            "candidateServices" -> io.circe.Json.fromInt(inspection.candidateServices.size),
          ),
          "requirements" -> io.circe.Json.obj(
            "nodeCount" -> io.circe.Json.fromInt(graph.nodes.size),
            "verifierCount" -> io.circe.Json.fromInt(graph.nodes.flatMap(_.verifiers).size),
          ),
          "plan" -> io.circe.Json.obj(
            "planId" -> io.circe.Json.fromString(plan.planId),
            "serviceCount" -> io.circe.Json.fromInt(plan.services.size),
            "fixtureStepCount" -> io.circe.Json.fromInt(plan.fixtureSteps.size),
            "services" -> io.circe.Json.arr(plan.services.map(s => io.circe.Json.fromString(s"${s.serviceId} (${s.startupMode})")): _*),
            "warnings" -> io.circe.Json.arr(plan.warnings.map(io.circe.Json.fromString): _*),
          ),
        ).noSpaces

      case OutputFormat.Human =>
        val lines = scala.collection.mutable.ListBuffer[String]()
        lines += s"Plan for task: ${cmd.task}"
        lines += s"Repository: ${global.repo}"
        lines += ""
        lines += "--- Inspection ---"
        lines += s"  Languages: ${inspection.languages.map(_.value).mkString(", ")}"
        lines += s"  Frameworks: ${inspection.frameworks.map(_.value).mkString(", ")}"
        lines += s"  Manifests: ${inspection.manifestsFound.map(m => s"${m.manifestType}:${m.relativePath}").mkString(", ")}"
        lines += s"  Candidate services: ${inspection.candidateServices.size}"
        lines += ""
        lines += "--- Requirements ---"
        lines += s"  Nodes: ${graph.nodes.size}"
        lines += s"  Verifiers: ${graph.nodes.flatMap(_.verifiers).size}"
        if (graph.warnings.nonEmpty) {
          graph.warnings.foreach(w => lines += s"  Warning: ${w.message}")
        }
        lines += ""
        lines += "--- Environment Plan ---"
        lines += s"  Plan ID: ${plan.planId}"
        lines += s"  Services: ${plan.services.size}"
        plan.services.foreach(s => lines += s"    - ${s.serviceId} (${s.startupMode}, ${s.kind})")
        lines += s"  Fixture steps: ${plan.fixtureSteps.size}"
        if (plan.warnings.nonEmpty) {
          plan.warnings.foreach(w => lines += s"  Warning: $w")
        }
        lines.mkString("\n")
    }

    System.out.println(output)
    ExitCodes.Success
  }
}
