CREATE TABLE source_unit_evidence (
    run_id TEXT PRIMARY KEY REFERENCES source_template_run(id),
    task_id TEXT NOT NULL REFERENCES task(id),
    cycle_id TEXT NOT NULL,
    content_json TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL
);
