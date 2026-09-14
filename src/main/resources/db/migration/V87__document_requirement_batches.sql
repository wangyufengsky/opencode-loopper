CREATE TABLE document_requirement_batch (
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 ordinal INTEGER NOT NULL,
 round INTEGER NOT NULL,
 extraction_model_id TEXT NOT NULL REFERENCES document_template_model_run(id),
 review_model_id TEXT NOT NULL REFERENCES document_template_model_run(id),
 candidate_json TEXT NOT NULL,
 candidate_sha256 TEXT NOT NULL,
 created_at TEXT NOT NULL,
 PRIMARY KEY(run_id,ordinal,round)
);
CREATE TABLE document_template_command (
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 request_key TEXT NOT NULL,
 request_sha256 TEXT NOT NULL,
 command TEXT NOT NULL,
 resulting_version INTEGER NOT NULL,
 created_at TEXT NOT NULL,
 PRIMARY KEY(run_id,request_key)
);
