-- Designer sessions deliberately carry no write authority.  They preserve the
-- human/agent design conversation separately from execution sessions.
CREATE TABLE designer_session (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    state TEXT NOT NULL,
    access_mode TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_designer_session_project_created ON designer_session(project_id, created_at DESC);

CREATE TABLE designer_message (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    delivery_state TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(designer_session_id, ordinal)
);
CREATE INDEX idx_designer_message_session_ordinal ON designer_message(designer_session_id, ordinal);
