CREATE TABLE source_template_model (
 id TEXT PRIMARY KEY,
 run_id TEXT NOT NULL REFERENCES source_template_run(id),
 candidate_kind TEXT NOT NULL,
 ordinal INTEGER NOT NULL,
 generation INTEGER NOT NULL,
 attempt INTEGER NOT NULL DEFAULT 0,
 state TEXT NOT NULL,
 input_json TEXT NOT NULL,
 input_sha256 TEXT NOT NULL,
 creation_plan_json TEXT,
 external_session_id TEXT,
 prompt_json TEXT,
 prompt_sha256 TEXT,
 output_json TEXT,
 output_sha256 TEXT,
 accepted_at TEXT,
 error_code TEXT,
 created_at TEXT NOT NULL,
 updated_at TEXT NOT NULL,
 version INTEGER NOT NULL DEFAULT 0,
 UNIQUE(run_id,candidate_kind,ordinal,generation,attempt)
);
CREATE INDEX idx_source_model_run ON source_template_model(run_id,candidate_kind,ordinal,generation,attempt);
CREATE TABLE source_template_read (
 model_id TEXT NOT NULL REFERENCES source_template_model(id),
 path TEXT NOT NULL,
 sha256 TEXT NOT NULL,
 start_line INTEGER NOT NULL,
 end_line INTEGER NOT NULL,
 total_lines INTEGER NOT NULL,
 content TEXT NOT NULL,
 created_at TEXT NOT NULL,
 PRIMARY KEY(model_id,path,start_line,end_line)
);
CREATE TABLE source_template_design_progress (
 run_id TEXT PRIMARY KEY REFERENCES source_template_run(id),
 generation INTEGER NOT NULL,
 plan_json TEXT NOT NULL,
 plan_sha256 TEXT NOT NULL,
 created_at TEXT NOT NULL
);
