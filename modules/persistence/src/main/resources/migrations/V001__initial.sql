-- Spec §7.2: Full SQLite schema

CREATE TABLE schema_version (
  version     INTEGER NOT NULL,
  applied_at  TEXT    NOT NULL
);

CREATE TABLE task_runs (
  run_id              TEXT    PRIMARY KEY,
  repo_path           TEXT    NOT NULL,
  worktree_path       TEXT    NOT NULL,
  git_ref             TEXT,
  task_text           TEXT    NOT NULL,
  changed_files_json  TEXT,
  status              TEXT    NOT NULL,
  run_mode            TEXT    NOT NULL,
  created_at          TEXT    NOT NULL,
  started_at          TEXT,
  ended_at            TEXT,
  max_attempts        INTEGER NOT NULL DEFAULT 5,
  attempt_count       INTEGER NOT NULL DEFAULT 0,
  env_boot_attempts   INTEGER NOT NULL DEFAULT 0,
  current_attempt_id  TEXT,
  final_verdict       TEXT,
  final_summary       TEXT,
  policy_snapshot_id  TEXT    NOT NULL,
  lock_file_path      TEXT    NOT NULL,
  artifact_root_path  TEXT    NOT NULL
);

CREATE INDEX idx_task_runs_status ON task_runs(status);
CREATE INDEX idx_task_runs_repo ON task_runs(repo_path);

CREATE TABLE attempts (
  attempt_id          TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  attempt_number      INTEGER NOT NULL,
  status              TEXT    NOT NULL,
  started_at          TEXT    NOT NULL,
  ended_at            TEXT,
  repair_backend      TEXT,
  patch_record_id     TEXT,
  failure_packet_id   TEXT,
  rerun_plan_id       TEXT,
  repair_retries_used INTEGER NOT NULL DEFAULT 0,
  verdict_summary_json TEXT,
  UNIQUE(run_id, attempt_number)
);

CREATE INDEX idx_attempts_run ON attempts(run_id);

CREATE TABLE requirement_graphs (
  graph_id            TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  graph_json          TEXT    NOT NULL,
  generated_at        TEXT    NOT NULL,
  inference_request_id TEXT
);

CREATE TABLE requirement_verdicts (
  verdict_id          TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  attempt_number      INTEGER NOT NULL,
  requirement_id      TEXT    NOT NULL,
  verifier_id         TEXT    NOT NULL,
  status              TEXT    NOT NULL,
  execution_duration_ms INTEGER NOT NULL,
  retry_count         INTEGER NOT NULL DEFAULT 0,
  observations_json   TEXT    NOT NULL,
  evidence_refs_json  TEXT    NOT NULL,
  failure_class       TEXT,
  failure_message     TEXT,
  suggested_rerun_scope_json TEXT,
  confidence          REAL    NOT NULL,
  produced_at         TEXT    NOT NULL
);

CREATE INDEX idx_verdicts_run_attempt ON requirement_verdicts(run_id, attempt_number);
CREATE INDEX idx_verdicts_requirement ON requirement_verdicts(requirement_id);

CREATE TABLE runtime_plans (
  plan_id             TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  plan_json           TEXT    NOT NULL,
  generated_at        TEXT    NOT NULL
);

CREATE TABLE runtime_snapshots (
  snapshot_id         TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  snapshot_json       TEXT    NOT NULL,
  captured_at         TEXT    NOT NULL
);

CREATE INDEX idx_snapshots_run ON runtime_snapshots(run_id);

CREATE TABLE failure_packets (
  failure_packet_id   TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  attempt_number      INTEGER NOT NULL,
  packet_json         TEXT    NOT NULL,
  produced_at         TEXT    NOT NULL
);

CREATE TABLE rerun_plans (
  rerun_plan_id       TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  plan_json           TEXT    NOT NULL,
  generated_at        TEXT    NOT NULL
);

CREATE TABLE patch_records (
  patch_record_id     TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  attempt_number      INTEGER NOT NULL,
  diff_artifact_id    TEXT    NOT NULL,
  files_changed_json  TEXT    NOT NULL,
  total_lines_added   INTEGER NOT NULL,
  total_lines_removed INTEGER NOT NULL,
  repair_backend      TEXT    NOT NULL,
  repair_summary      TEXT    NOT NULL,
  hypotheses_json     TEXT    NOT NULL,
  requires_env_rebuild INTEGER NOT NULL DEFAULT 0,
  infra_sensitive_files_json TEXT NOT NULL,
  transcript_artifact_id TEXT,
  usage_record_id     TEXT    NOT NULL,
  applied_at          TEXT    NOT NULL,
  patch_application_method TEXT NOT NULL,
  pre_apply_commit_sha TEXT   NOT NULL,
  post_apply_commit_sha TEXT
);

CREATE TABLE artifact_records (
  artifact_id         TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  attempt_number      INTEGER,
  artifact_type       TEXT    NOT NULL,
  producer_component  TEXT    NOT NULL,
  logical_scope       TEXT,
  relative_path       TEXT    NOT NULL,
  content_type        TEXT    NOT NULL,
  size_bytes          INTEGER NOT NULL,
  checksum_sha256     TEXT    NOT NULL,
  compressed          INTEGER NOT NULL DEFAULT 0,
  compression_format  TEXT,
  created_at          TEXT    NOT NULL,
  metadata_json       TEXT
);

CREATE INDEX idx_artifacts_run ON artifact_records(run_id);
CREATE INDEX idx_artifacts_run_attempt ON artifact_records(run_id, attempt_number);
CREATE INDEX idx_artifacts_type ON artifact_records(artifact_type);

CREATE TABLE policy_snapshots (
  policy_snapshot_id  TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  snapshot_json       TEXT    NOT NULL,
  captured_at         TEXT    NOT NULL
);

CREATE TABLE usage_records (
  usage_record_id     TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  attempt_number      INTEGER,
  component           TEXT    NOT NULL,
  provider            TEXT    NOT NULL,
  model               TEXT    NOT NULL,
  input_tokens        INTEGER NOT NULL,
  output_tokens       INTEGER NOT NULL,
  total_tokens        INTEGER NOT NULL,
  duration_ms         INTEGER NOT NULL,
  estimated_cost_usd  REAL,
  request_count       INTEGER NOT NULL,
  cached_tokens       INTEGER NOT NULL DEFAULT 0,
  created_at          TEXT    NOT NULL
);

CREATE INDEX idx_usage_run ON usage_records(run_id);
CREATE INDEX idx_usage_component ON usage_records(component);

CREATE TABLE inference_cache (
  cache_key           TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  request_json        TEXT    NOT NULL,
  response_json       TEXT    NOT NULL,
  created_at          TEXT    NOT NULL
);

CREATE TABLE repo_inspection_reports (
  report_id           TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL REFERENCES task_runs(run_id),
  report_json         TEXT    NOT NULL,
  inspected_at        TEXT    NOT NULL
);

CREATE TABLE events (
  event_id            TEXT    PRIMARY KEY,
  run_id              TEXT    NOT NULL,
  attempt_number      INTEGER,
  event_type          TEXT    NOT NULL,
  component           TEXT    NOT NULL,
  severity            TEXT    NOT NULL,
  payload_json        TEXT    NOT NULL,
  correlation_fields_json TEXT,
  human_message       TEXT    NOT NULL,
  timestamp           TEXT    NOT NULL
);

CREATE INDEX idx_events_run ON events(run_id);
CREATE INDEX idx_events_type ON events(event_type);
CREATE INDEX idx_events_timestamp ON events(timestamp);
