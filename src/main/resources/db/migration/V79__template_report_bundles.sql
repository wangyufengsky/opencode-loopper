-- Counters intentionally survive task deletion so report directory names are never reused.
CREATE TABLE template_report_sequence (
    namespace_key TEXT PRIMARY KEY,
    last_sequence INTEGER NOT NULL CHECK(last_sequence > 0)
);
CREATE TABLE template_report_bundle (
    attempt_id TEXT PRIMARY KEY REFERENCES attempt(id) ON DELETE CASCADE,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    namespace_key TEXT NOT NULL REFERENCES template_report_sequence(namespace_key),
    sequence INTEGER NOT NULL CHECK(sequence > 0),
    project_name TEXT NOT NULL,
    folder_name TEXT NOT NULL,
    main_path TEXT NOT NULL,
    UNIQUE(namespace_key, sequence)
);
CREATE INDEX idx_template_report_bundle_task ON template_report_bundle(task_id);
