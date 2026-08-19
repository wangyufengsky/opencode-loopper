CREATE TABLE task_profile_router_run (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK (state IN ('PENDING','RUNNING','COMPLETED','FAILED','SUPERSEDED')),
    requirement_snapshot TEXT NOT NULL,
    repository_evidence_json TEXT NOT NULL DEFAULT '[]',
    external_session_id TEXT,
    external_session_state TEXT,
    response_mode TEXT,
    semantic_labels_json TEXT,
    error_code TEXT,
    error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_task_profile_router_run_active
    ON task_profile_router_run(designer_session_id)
    WHERE state IN ('PENDING','RUNNING');
CREATE INDEX idx_task_profile_router_run_state
    ON task_profile_router_run(state,updated_at);
