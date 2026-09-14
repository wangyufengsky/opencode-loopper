CREATE TABLE document_template_run (
 id TEXT PRIMARY KEY,
 request_key TEXT NOT NULL UNIQUE,
 request_sha256 TEXT NOT NULL,
 project_id TEXT NOT NULL REFERENCES project(id),
 template_id TEXT NOT NULL CHECK(template_id IN ('REQUIREMENT_DEVELOPMENT','REQUIREMENT_CODE_REVIEW')),
 template_version TEXT NOT NULL,
 title TEXT NOT NULL,
 state TEXT NOT NULL,
 resume_state TEXT,
 branch_json TEXT,
 snapshot_json TEXT,
 contract_json TEXT NOT NULL,
 designer_id TEXT UNIQUE REFERENCES designer_session(id),
 task_id TEXT UNIQUE REFERENCES task(id),
 requirement_revision INTEGER NOT NULL DEFAULT 0,
 waiting_reason_code TEXT,
 waiting_message TEXT,
 archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),
 created_at TEXT NOT NULL,
 updated_at TEXT NOT NULL,
 version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_document_template_run_project ON document_template_run(project_id,created_at DESC,id DESC);
CREATE INDEX idx_document_template_run_active ON document_template_run(state,updated_at,id);

CREATE TABLE document_template_file (
 id TEXT PRIMARY KEY,
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 ordinal INTEGER NOT NULL,
 filename TEXT NOT NULL,
 format TEXT NOT NULL,
 size_bytes INTEGER NOT NULL,
 sha256 TEXT NOT NULL,
 representation_sha256 TEXT NOT NULL,
 parser_version TEXT NOT NULL,
 relative_path TEXT NOT NULL,
 section_count INTEGER NOT NULL,
 limitations_json TEXT NOT NULL,
 UNIQUE(run_id,ordinal)
);
CREATE TABLE document_template_section (
 file_id TEXT NOT NULL REFERENCES document_template_file(id),
 ordinal INTEGER NOT NULL,
 title TEXT NOT NULL,
 content TEXT NOT NULL,
 sha256 TEXT NOT NULL,
 PRIMARY KEY(file_id,ordinal)
);
CREATE TABLE document_requirement_revision (
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 revision INTEGER NOT NULL,
 manifest_sha256 TEXT NOT NULL,
 source_json TEXT NOT NULL,
 created_at TEXT NOT NULL,
 PRIMARY KEY(run_id,revision)
);
CREATE TABLE document_requirement (
 run_id TEXT NOT NULL,
 revision INTEGER NOT NULL,
 requirement_key TEXT NOT NULL,
 ordinal INTEGER NOT NULL,
 title TEXT NOT NULL,
 group_name TEXT NOT NULL,
 kind TEXT NOT NULL,
 statement TEXT NOT NULL,
 sources_json TEXT NOT NULL,
 acceptance_json TEXT NOT NULL,
 issues_json TEXT NOT NULL,
 PRIMARY KEY(run_id,revision,requirement_key),
 UNIQUE(run_id,revision,ordinal),
 FOREIGN KEY(run_id,revision) REFERENCES document_requirement_revision(run_id,revision)
);
CREATE TABLE document_requirement_assessment (
 run_id TEXT NOT NULL,
 revision INTEGER NOT NULL,
 requirement_key TEXT NOT NULL,
 assessment_json TEXT NOT NULL,
 evidence_sha256 TEXT NOT NULL,
 PRIMARY KEY(run_id,revision,requirement_key),
 FOREIGN KEY(run_id,revision,requirement_key) REFERENCES document_requirement(run_id,revision,requirement_key)
);
CREATE TABLE document_template_artifact (
 id TEXT PRIMARY KEY,
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 requirement_revision INTEGER NOT NULL,
 name TEXT NOT NULL,
 kind TEXT NOT NULL,
 content TEXT NOT NULL,
 sha256 TEXT NOT NULL,
 created_at TEXT NOT NULL
);
CREATE INDEX idx_document_template_artifact_run ON document_template_artifact(run_id,created_at,id);
