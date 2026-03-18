-- V002: Build mode support
-- Adds feature_plans table and generation_mode column to patch_records

CREATE TABLE IF NOT EXISTS feature_plans (
  plan_id        TEXT PRIMARY KEY,
  run_id         TEXT NOT NULL,
  plan_json      TEXT NOT NULL,
  created_at     TEXT NOT NULL,
  FOREIGN KEY (run_id) REFERENCES task_runs(run_id)
);

CREATE INDEX IF NOT EXISTS idx_feature_plans_run_id ON feature_plans(run_id);
