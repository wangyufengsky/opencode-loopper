CREATE TABLE document_development_design (
  requirement_revision_id TEXT PRIMARY KEY REFERENCES design_requirement_revision(id),
  run_id TEXT NOT NULL REFERENCES document_template_run(id),
  document_revision INTEGER NOT NULL,
  manifest_sha256 TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(run_id,document_revision),
  FOREIGN KEY(run_id,document_revision) REFERENCES document_requirement_revision(run_id,revision)
);
