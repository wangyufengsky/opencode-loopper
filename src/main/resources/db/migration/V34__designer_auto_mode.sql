CREATE TABLE designer_auto_mode (
    designer_session_id TEXT PRIMARY KEY REFERENCES designer_session(id) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK (state IN ('DISABLED', 'ACTIVE', 'BLOCKED', 'COMPLETED')),
    last_action TEXT,
    error_code TEXT,
    error_detail TEXT,
    task_id TEXT REFERENCES task(id),
    authorized_at TEXT,
    disabled_at TEXT,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_designer_auto_mode_state_updated
    ON designer_auto_mode(state, updated_at);
