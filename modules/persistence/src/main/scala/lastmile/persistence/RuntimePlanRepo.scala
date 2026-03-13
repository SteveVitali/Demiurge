package lastmile.persistence

import java.sql.Connection
import io.circe.syntax._
import io.circe.parser._
import lastmile.model._
import lastmile.model.JsonCodecs._

// Spec §7.2: RuntimePlan persistence for Phase 3.
object RuntimePlanRepo {

  def insert(plan: RuntimePlan)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO runtime_plans (plan_id, run_id, plan_json, generated_at)
        |VALUES (?, ?, ?, ?)""".stripMargin)
    try {
      ps.setString(1, plan.planId)
      ps.setString(2, plan.runId)
      ps.setString(3, plan.asJson.noSpaces)
      ps.setString(4, plan.generatedAt.toString)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def deleteByRunId(runId: String)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement("DELETE FROM runtime_plans WHERE run_id = ?")
    try { ps.setString(1, runId); ps.executeUpdate() }
    finally { ps.close() }
  }

  def getById(planId: String)(implicit conn: Connection): Option[RuntimePlan] = {
    val ps = conn.prepareStatement("SELECT plan_json FROM runtime_plans WHERE plan_id = ?")
    try {
      ps.setString(1, planId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) decode[RuntimePlan](rs.getString("plan_json")).toOption
        else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def getByRunId(runId: String)(implicit conn: Connection): Option[RuntimePlan] = {
    val ps = conn.prepareStatement(
      "SELECT plan_json FROM runtime_plans WHERE run_id = ? ORDER BY generated_at DESC LIMIT 1")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) decode[RuntimePlan](rs.getString("plan_json")).toOption
        else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }
}
