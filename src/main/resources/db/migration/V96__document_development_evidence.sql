CREATE TABLE document_development_evidence (
    run_id TEXT PRIMARY KEY REFERENCES document_template_run(id),
    requirement_revision INTEGER NOT NULL,
    task_id TEXT NOT NULL REFERENCES task(id),
    cycle_id TEXT NOT NULL REFERENCES task_execution_cycle(id),
    content_json TEXT NOT NULL CHECK(json_valid(content_json)),
    sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY(run_id, requirement_revision) REFERENCES document_requirement_revision(run_id, revision)
);
