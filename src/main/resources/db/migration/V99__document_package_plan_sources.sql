-- A new remaining-package plan binds its own document revision without rewriting earlier package facts.
CREATE TABLE document_development_plan_source (
    plan_revision_id TEXT PRIMARY KEY REFERENCES task_package_plan_revision(id),
    run_id TEXT NOT NULL,
    document_revision INTEGER NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (run_id, document_revision) REFERENCES document_requirement_revision(run_id,revision)
);
