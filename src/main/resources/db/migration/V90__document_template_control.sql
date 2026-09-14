CREATE TABLE document_template_control (
 run_id TEXT PRIMARY KEY REFERENCES document_template_run(id),
 stop_target TEXT CHECK(stop_target IN ('WAITING_INPUT','CANCELLED')),
 resume_state TEXT,
 error_code TEXT,
 error_message TEXT,
 consecutive_errors INTEGER NOT NULL DEFAULT 0,
 recoveries INTEGER NOT NULL DEFAULT 0,
 version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_document_template_list ON document_template_run(archived,created_at,id);
