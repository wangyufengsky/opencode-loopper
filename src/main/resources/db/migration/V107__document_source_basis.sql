-- Source snapshots are independent of model-produced requirement revisions.
ALTER TABLE document_template_run ADD COLUMN source_revision INTEGER NOT NULL DEFAULT 0;
CREATE TABLE document_basis_revision (
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 revision INTEGER NOT NULL,
 source_kind TEXT NOT NULL CHECK(source_kind IN ('REQUIREMENT_LIST','DOCUMENT_SOURCE')),
 manifest_sha256 TEXT NOT NULL,
 source_json TEXT NOT NULL CHECK(json_valid(source_json)),
 created_at TEXT NOT NULL,
 PRIMARY KEY(run_id, revision)
);
INSERT INTO document_basis_revision
 SELECT run_id,revision,'REQUIREMENT_LIST',manifest_sha256,source_json,created_at FROM document_requirement_revision;
CREATE TRIGGER document_legacy_basis_insert AFTER INSERT ON document_requirement_revision
 WHEN (SELECT template_version FROM document_template_run WHERE id=NEW.run_id)='1'
 BEGIN
 INSERT INTO document_basis_revision VALUES(NEW.run_id,NEW.revision,'REQUIREMENT_LIST',NEW.manifest_sha256,NEW.source_json,NEW.created_at);
 END;
CREATE TABLE document_source_read (
 session_id TEXT NOT NULL,
 run_id TEXT NOT NULL REFERENCES document_template_run(id),
 source_revision INTEGER NOT NULL,
 file_id TEXT NOT NULL REFERENCES document_template_file(id),
 section INTEGER NOT NULL,
 sha256 TEXT NOT NULL,
 PRIMARY KEY(session_id,run_id,source_revision,file_id,section),
 FOREIGN KEY(run_id,source_revision) REFERENCES document_basis_revision(run_id,revision)
);
CREATE TABLE document_development_design_basis (
  requirement_revision_id TEXT PRIMARY KEY REFERENCES design_requirement_revision(id),
  run_id TEXT NOT NULL REFERENCES document_template_run(id),
  document_revision INTEGER NOT NULL,
  manifest_sha256 TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY(run_id,document_revision) REFERENCES document_basis_revision(run_id,revision)
);
INSERT INTO document_development_design_basis SELECT * FROM document_development_design;
DROP TABLE document_development_design;
ALTER TABLE document_development_design_basis RENAME TO document_development_design;
CREATE TABLE document_development_plan_source_basis (
    plan_revision_id TEXT PRIMARY KEY REFERENCES task_package_plan_revision(id),
    run_id TEXT NOT NULL,
    document_revision INTEGER NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (run_id, document_revision) REFERENCES document_basis_revision(run_id,revision)
);
INSERT INTO document_development_plan_source_basis SELECT * FROM document_development_plan_source;
DROP TABLE document_development_plan_source;
ALTER TABLE document_development_plan_source_basis RENAME TO document_development_plan_source;
CREATE TABLE document_development_task_source_basis (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    run_id TEXT NOT NULL REFERENCES document_template_run(id) ON DELETE CASCADE,
    document_revision INTEGER NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (run_id, document_revision) REFERENCES document_basis_revision(run_id, revision)
);
INSERT INTO document_development_task_source_basis SELECT * FROM document_development_task_source;
DROP TABLE document_development_task_source;
ALTER TABLE document_development_task_source_basis RENAME TO document_development_task_source;
CREATE TABLE document_development_evidence_basis (
    run_id TEXT PRIMARY KEY REFERENCES document_template_run(id),
    requirement_revision INTEGER NOT NULL,
    task_id TEXT NOT NULL REFERENCES task(id),
    cycle_id TEXT NOT NULL REFERENCES task_execution_cycle(id),
    content_json TEXT NOT NULL CHECK(json_valid(content_json)),
    sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY(run_id, requirement_revision) REFERENCES document_basis_revision(run_id, revision)
);
INSERT INTO document_development_evidence_basis SELECT * FROM document_development_evidence;
DROP TABLE document_development_evidence;
ALTER TABLE document_development_evidence_basis RENAME TO document_development_evidence;
CREATE INDEX idx_document_development_design_run ON document_development_design(run_id,document_revision);
