CREATE TABLE document_template_upload_ready (
 run_id TEXT PRIMARY KEY REFERENCES document_template_run(id),
 completed_at TEXT NOT NULL
);
