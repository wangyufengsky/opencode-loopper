CREATE TABLE source_template_run (
 id TEXT PRIMARY KEY,
 request_key TEXT NOT NULL UNIQUE,
 request_sha256 TEXT NOT NULL,
 project_id TEXT NOT NULL REFERENCES project(id),
 template_id TEXT NOT NULL CHECK(template_id IN ('UNIT_TEST_DEVELOPMENT','DETAILED_DESIGN_WRITING')),
 template_version TEXT NOT NULL,
 title TEXT NOT NULL,
 state TEXT NOT NULL,
 resume_state TEXT,
 parameters_json TEXT NOT NULL,
 contract_json TEXT NOT NULL,
 snapshot_json TEXT,
 designer_id TEXT UNIQUE REFERENCES designer_session(id),
 task_id TEXT UNIQUE REFERENCES task(id),
 stop_target TEXT,
 waiting_reason_code TEXT,
 waiting_message TEXT,
 archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),
 created_at TEXT NOT NULL,
 updated_at TEXT NOT NULL,
 version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_source_template_project ON source_template_run(project_id,created_at DESC,id DESC);
CREATE INDEX idx_source_template_active ON source_template_run(state,id);
CREATE TABLE source_template_file (
 run_id TEXT NOT NULL REFERENCES source_template_run(id),
 ordinal INTEGER NOT NULL,
 path TEXT NOT NULL,
 target INTEGER NOT NULL CHECK(target IN (0,1)),
 size_bytes INTEGER NOT NULL,
 sha256 TEXT,
 exclusion TEXT,
 PRIMARY KEY(run_id,path),
 UNIQUE(run_id,ordinal)
);
CREATE TABLE source_template_coverage (
 run_id TEXT NOT NULL,
 path TEXT NOT NULL,
 status TEXT NOT NULL,
 result_json TEXT NOT NULL,
 updated_at TEXT NOT NULL,
 PRIMARY KEY(run_id,path),
 FOREIGN KEY(run_id,path) REFERENCES source_template_file(run_id,path)
);
CREATE TABLE source_template_command (
 run_id TEXT NOT NULL REFERENCES source_template_run(id),
 request_key TEXT NOT NULL,
 digest TEXT NOT NULL,
 created_at TEXT NOT NULL,
 PRIMARY KEY(run_id,request_key)
);
CREATE TABLE source_template_artifact (
 id TEXT PRIMARY KEY,
 run_id TEXT NOT NULL REFERENCES source_template_run(id),
 name TEXT NOT NULL,
 kind TEXT NOT NULL,
 content TEXT NOT NULL,
 sha256 TEXT NOT NULL,
 created_at TEXT NOT NULL,
 UNIQUE(run_id,name)
);
