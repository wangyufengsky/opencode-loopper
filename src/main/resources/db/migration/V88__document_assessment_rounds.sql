CREATE TABLE document_assessment_progress (
 run_id TEXT PRIMARY KEY REFERENCES document_template_run(id),
 round INTEGER NOT NULL DEFAULT 0,
 version INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE document_assessment_batch (
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 revision INTEGER NOT NULL,
 ordinal INTEGER NOT NULL,
 round INTEGER NOT NULL,
 model_id TEXT NOT NULL REFERENCES document_template_model_run(id),
 review_model_id TEXT NOT NULL REFERENCES document_template_model_run(id),
 candidate_json TEXT NOT NULL,
 candidate_sha256 TEXT NOT NULL,
 created_at TEXT NOT NULL,
 PRIMARY KEY(run_id,revision,ordinal)
);
