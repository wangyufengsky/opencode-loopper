CREATE TABLE source_test_baseline (
    run_id TEXT PRIMARY KEY REFERENCES source_template_run(id),
    snapshot_json TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL
);
