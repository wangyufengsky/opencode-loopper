CREATE TABLE assist_project_config (
 project_id TEXT PRIMARY KEY REFERENCES project(id), config_json TEXT NOT NULL,
 version INTEGER NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE assist_batch_binding (
 owner_key TEXT PRIMARY KEY, config_json TEXT NOT NULL, created_at TEXT NOT NULL
);
INSERT INTO assist_batch_binding SELECT 'TASK:'||id,'{}',created_at FROM task;
INSERT OR IGNORE INTO assist_batch_binding SELECT owner_key,'{}',created_at FROM assist_resource_binding;
CREATE TRIGGER assist_batch_task_freeze AFTER INSERT ON task BEGIN
 INSERT INTO assist_batch_binding(owner_key,config_json,created_at)
 VALUES('TASK:'||NEW.id,coalesce((SELECT config_json FROM assist_project_config WHERE project_id=NEW.project_id),'{}'),NEW.created_at);
END;
CREATE TABLE execution_evidence (
 id TEXT PRIMARY KEY, owner_key TEXT NOT NULL, task_id TEXT, stage_id TEXT, attempt_id TEXT,
 execution_id TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL, content_path TEXT NOT NULL,
 sha256 TEXT NOT NULL, byte_size INTEGER NOT NULL, status TEXT NOT NULL, metadata_json TEXT NOT NULL,
 created_at TEXT NOT NULL
);
CREATE INDEX idx_execution_evidence_owner ON execution_evidence(owner_key,created_at,id);
CREATE INDEX idx_execution_evidence_attempt ON execution_evidence(attempt_id,created_at,id);
CREATE TABLE evidence_parse_cache (
 sha256 TEXT NOT NULL, parser_version TEXT NOT NULL, result_json TEXT NOT NULL,
 PRIMARY KEY(sha256,parser_version)
);
CREATE TABLE evidence_test_failure (
 id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL REFERENCES execution_evidence(id),
 name TEXT NOT NULL, class_name TEXT NOT NULL, state TEXT NOT NULL, detail_json TEXT NOT NULL
);
CREATE INDEX idx_evidence_failure_snapshot ON evidence_test_failure(snapshot_id,id);
