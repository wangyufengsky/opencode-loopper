CREATE UNIQUE INDEX ppt_job_document_identity ON ppt_job(id,document_id);

CREATE TABLE ppt_generation (
 id TEXT PRIMARY KEY,
 document_id TEXT NOT NULL REFERENCES ppt_document(id),
 idempotency_key TEXT NOT NULL,
 input_sha TEXT NOT NULL CHECK(length(input_sha)=64),
 prompt TEXT NOT NULL,
 mode TEXT NOT NULL CHECK(mode IN ('CREATE','REVISE')),
 scope_json TEXT NOT NULL CHECK(json_valid(scope_json)),
 source_revision INTEGER NOT NULL CHECK(source_revision>=0),
 dispatch_revision INTEGER NOT NULL CHECK(dispatch_revision>=0),
 state TEXT NOT NULL CHECK(state IN ('PLANNING','PRODUCING','PREVIEW','EXPORT','WAITING_INPUT','STOPPING','STOPPED','FAILED','COMPLETED')),
 step TEXT NOT NULL CHECK(step IN ('PLANNING','PRODUCING','PREVIEW','EXPORT')),
 attempt INTEGER NOT NULL DEFAULT 0 CHECK(attempt>=0),
 agent_key TEXT NOT NULL,
 run_id TEXT,
 job_id TEXT,
 preview_job_id TEXT,
 output_revision INTEGER CHECK(output_revision>=0),
 detail TEXT NOT NULL DEFAULT '',
 version INTEGER NOT NULL DEFAULT 0,
 created_at TEXT NOT NULL,
 updated_at TEXT NOT NULL,
 UNIQUE(document_id,idempotency_key),
 UNIQUE(id,document_id),
 FOREIGN KEY(document_id,source_revision) REFERENCES ppt_revision(document_id,revision),
 FOREIGN KEY(document_id,output_revision) REFERENCES ppt_revision(document_id,revision),
 FOREIGN KEY(run_id,document_id) REFERENCES ppt_agent_run(id,document_id),
 FOREIGN KEY(job_id,document_id) REFERENCES ppt_job(id,document_id),
 FOREIGN KEY(preview_job_id,document_id) REFERENCES ppt_job(id,document_id)
);
CREATE UNIQUE INDEX ppt_generation_active_document ON ppt_generation(document_id)
 WHERE state NOT IN ('COMPLETED','STOPPED','FAILED');
CREATE INDEX ppt_generation_history ON ppt_generation(document_id,created_at,id);

CREATE TABLE ppt_generation_request (
 document_id TEXT NOT NULL REFERENCES ppt_document(id),
 idempotency_key TEXT NOT NULL,
 input_sha TEXT NOT NULL CHECK(length(input_sha)=64),
 generation_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('GENERATE','RESUME','REVISE')),
 created_at TEXT NOT NULL,
 PRIMARY KEY(document_id,idempotency_key),
 FOREIGN KEY(generation_id,document_id) REFERENCES ppt_generation(id,document_id)
);
