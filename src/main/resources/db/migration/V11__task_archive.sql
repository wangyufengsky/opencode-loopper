CREATE TABLE task_archive (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    archived_at TEXT NOT NULL
);

CREATE INDEX idx_task_archive_archived_at ON task_archive(archived_at DESC);
