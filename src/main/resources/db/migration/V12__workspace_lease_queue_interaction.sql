CREATE TABLE workspace_lease (
    canonical_root TEXT PRIMARY KEY,
    root_fingerprint TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode = 'DIRECT'),
    holder_task_id TEXT REFERENCES task(id) ON DELETE SET NULL,
    writer_session_id TEXT REFERENCES execution_session(id) ON DELETE SET NULL,
    state TEXT NOT NULL CHECK (state IN ('HELD', 'RELEASE_PENDING', 'RELEASED')),
    acquired_at TEXT,
    heartbeat_at TEXT NOT NULL,
    released_at TEXT,
    release_reason TEXT,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_workspace_lease_holder
    ON workspace_lease(holder_task_id)
    WHERE holder_task_id IS NOT NULL AND state <> 'RELEASED';

CREATE INDEX idx_workspace_lease_state_heartbeat
    ON workspace_lease(state, heartbeat_at);

CREATE TABLE task_queue (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    canonical_root TEXT NOT NULL,
    root_fingerprint TEXT NOT NULL,
    position INTEGER NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('MANUAL', 'RECOVERY', 'AUTOMATION')),
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'ADMITTED', 'CANCELLED', 'FINISHED')),
    enqueued_at TEXT NOT NULL,
    admitted_at TEXT,
    finished_at TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(canonical_root, position)
);

CREATE INDEX idx_task_queue_root_state_position
    ON task_queue(canonical_root, state, position);

CREATE TABLE interaction (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL CHECK (scope_type IN ('TASK', 'DESIGNER')),
    scope_id TEXT NOT NULL,
    task_id TEXT REFERENCES task(id) ON DELETE CASCADE,
    designer_session_id TEXT REFERENCES designer_session(id) ON DELETE CASCADE,
    local_session_id TEXT,
    external_session_id TEXT NOT NULL,
    external_request_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('QUESTION', 'PERMISSION')),
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'RESOLVING', 'RESOLVED', 'REJECTED', 'HARD_DENIED', 'STALE')),
    payload_json TEXT NOT NULL,
    resolved_action TEXT,
    response_json TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    resolved_at TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(external_session_id, external_request_id, kind),
    CHECK (
        (scope_type = 'TASK' AND task_id IS NOT NULL AND designer_session_id IS NULL AND scope_id = task_id)
        OR
        (scope_type = 'DESIGNER' AND task_id IS NULL AND designer_session_id IS NOT NULL AND scope_id = designer_session_id)
    )
);

CREATE INDEX idx_interaction_pending
    ON interaction(state, created_at);

CREATE INDEX idx_interaction_scope
    ON interaction(scope_type, scope_id, state, created_at);
