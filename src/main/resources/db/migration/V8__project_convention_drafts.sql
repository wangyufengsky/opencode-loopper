-- AI proposes project-specific context in a read-only OpenCode session.  The
-- resulting AGENTS.md remains a human-confirmed, program-controlled write.
CREATE TABLE project_convention_draft (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    state TEXT NOT NULL,
    external_session_id TEXT,
    external_session_state TEXT,
    source_exists INTEGER NOT NULL,
    source_sha256 TEXT NOT NULL,
    source_content TEXT NOT NULL,
    proposed_content TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_project_convention_draft_project_created
    ON project_convention_draft(project_id, created_at DESC);

CREATE INDEX idx_project_convention_draft_state
    ON project_convention_draft(state, updated_at);
