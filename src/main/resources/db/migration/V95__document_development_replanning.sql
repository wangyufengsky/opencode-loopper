-- Multiple immutable Designer revisions may refer to one unchanged document revision.
CREATE TABLE document_development_design_next (
  requirement_revision_id TEXT PRIMARY KEY REFERENCES design_requirement_revision(id),
  run_id TEXT NOT NULL REFERENCES document_template_run(id),
  document_revision INTEGER NOT NULL,
  manifest_sha256 TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY(run_id,document_revision) REFERENCES document_requirement_revision(run_id,revision)
);
INSERT INTO document_development_design_next SELECT * FROM document_development_design;
DROP TABLE document_development_design;
ALTER TABLE document_development_design_next RENAME TO document_development_design;
CREATE INDEX idx_document_development_design_run ON document_development_design(run_id,document_revision);
CREATE TABLE document_development_promotion (
  source_revision_id TEXT PRIMARY KEY REFERENCES design_requirement_revision(id),
  run_id TEXT NOT NULL REFERENCES document_template_run(id),
  designer_id TEXT NOT NULL REFERENCES designer_session(id),
  target_revision_id TEXT NOT NULL UNIQUE,
  profile_json TEXT NOT NULL CHECK(json_valid(profile_json)),
  created_at TEXT NOT NULL
);
