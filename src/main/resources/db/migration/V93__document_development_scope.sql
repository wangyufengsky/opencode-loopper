CREATE TABLE document_development_scope (
 external_session_id TEXT PRIMARY KEY REFERENCES assist_session(external_session_id),
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 requirement_revision INTEGER NOT NULL,
 manifest_sha256 TEXT NOT NULL,
 owner_json TEXT NOT NULL,
 files_json TEXT NOT NULL,
 created_at TEXT NOT NULL
);
