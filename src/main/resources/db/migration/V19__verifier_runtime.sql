CREATE TABLE verifier_runtime (
  id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL,
  stage_id TEXT NOT NULL,
  attempt_id TEXT NOT NULL,
  state TEXT NOT NULL,
  pid INTEGER,
  process_start_instant TEXT,
  port INTEGER,
  argv_sha256 TEXT NOT NULL,
  resolved_argv_json TEXT NOT NULL,
  temp_dir TEXT NOT NULL,
  evidence_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  ended_at TEXT,
  version INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(task_id) REFERENCES task(id),
  FOREIGN KEY(stage_id) REFERENCES stage(id),
  FOREIGN KEY(attempt_id) REFERENCES attempt(id)
);

CREATE INDEX idx_verifier_runtime_task_state ON verifier_runtime(task_id, state, created_at);
CREATE INDEX idx_verifier_runtime_attempt ON verifier_runtime(attempt_id, created_at);
