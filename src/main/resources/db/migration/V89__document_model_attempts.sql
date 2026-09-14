-- Preserve prior remote identity and evidence for every explicit recovery attempt.
PRAGMA foreign_keys=OFF;
PRAGMA legacy_alter_table=ON;
SAVEPOINT document_model_v89;
ALTER TABLE document_template_model_run RENAME TO document_template_model_run_v88;
CREATE TABLE document_template_model_run (
 id TEXT PRIMARY KEY,
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 candidate_kind TEXT NOT NULL,
 ordinal INTEGER NOT NULL,
 generation INTEGER NOT NULL,
 state TEXT NOT NULL,
 input_json TEXT NOT NULL,
 input_sha256 TEXT NOT NULL,
 creation_plan_json TEXT,
 external_session_id TEXT,
 prompt_json TEXT,
 prompt_sha256 TEXT,
 output_json TEXT,
 output_sha256 TEXT,
 error_code TEXT,
 created_at TEXT NOT NULL,
 updated_at TEXT NOT NULL,
 version INTEGER NOT NULL DEFAULT 0,
 attempt INTEGER NOT NULL DEFAULT 0 CHECK(attempt>=0),
 UNIQUE(run_id,candidate_kind,ordinal,generation,attempt)
);
INSERT INTO document_template_model_run SELECT *,0 FROM document_template_model_run_v88;
DROP TABLE document_template_model_run_v88;
CREATE INDEX idx_document_template_model_active ON document_template_model_run(run_id,state,id);
CREATE TEMP TABLE document_model_v89_guard (violations INTEGER CHECK(violations=0));
INSERT INTO document_model_v89_guard SELECT count(*) FROM pragma_foreign_key_check
 WHERE "table"='document_template_model_run' OR parent='document_template_model_run';
DROP TABLE document_model_v89_guard;
RELEASE document_model_v89;
PRAGMA legacy_alter_table=OFF;
PRAGMA foreign_keys=ON;
