package demiurge.persistence

import java.sql.Connection
import io.circe.syntax._
import io.circe.parser._
import demiurge.model._
import demiurge.model.JsonCodecs._

// Phase B: FeaturePlan persistence for Build mode.
object FeaturePlanRepo {

  def insert(plan: FeaturePlan)(implicit conn: Connection): Unit = {
    val ps = conn.prepareStatement(
      """INSERT INTO feature_plans (plan_id, run_id, plan_json, created_at)
        |VALUES (?, ?, ?, ?)""".stripMargin)
    try {
      ps.setString(1, plan.planId)
      ps.setString(2, plan.runId)
      ps.setString(3, plan.asJson.noSpaces)
      ps.setString(4, plan.createdAt.toString)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def getById(planId: String)(implicit conn: Connection): Option[FeaturePlan] = {
    val ps = conn.prepareStatement("SELECT plan_json FROM feature_plans WHERE plan_id = ?")
    try {
      ps.setString(1, planId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) decode[FeaturePlan](rs.getString("plan_json")).toOption
        else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  def getByRunId(runId: String)(implicit conn: Connection): Option[FeaturePlan] = {
    val ps = conn.prepareStatement(
      "SELECT plan_json FROM feature_plans WHERE run_id = ? ORDER BY created_at DESC LIMIT 1")
    try {
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) decode[FeaturePlan](rs.getString("plan_json")).toOption
        else None
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }
}
