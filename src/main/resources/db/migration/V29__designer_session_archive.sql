CREATE TABLE designer_session_archive (
    designer_session_id TEXT PRIMARY KEY REFERENCES designer_session(id) ON DELETE CASCADE,
    archived_at TEXT NOT NULL
);

CREATE INDEX idx_designer_session_archive_archived_at
    ON designer_session_archive(archived_at DESC);
