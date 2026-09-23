ALTER TABLE source_template_model ADD COLUMN started_at TEXT;
CREATE TABLE source_template_control (
    run_id TEXT PRIMARY KEY REFERENCES source_template_run(id),
    recoveries INTEGER NOT NULL DEFAULT 0,
    consecutive_errors INTEGER NOT NULL DEFAULT 0
);
