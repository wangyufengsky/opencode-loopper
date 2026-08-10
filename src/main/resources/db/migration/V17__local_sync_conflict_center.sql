CREATE TABLE local_sync_conflict_session (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    source_root TEXT NOT NULL,
    baseline_commit TEXT NOT NULL,
    task_commit TEXT NOT NULL,
    source_head TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN (
        'OPEN', 'READY', 'APPLYING', 'VERIFYING', 'APPLIED', 'STALE',
        'ROLLED_BACK', 'ROLLBACK_FAILED'
    )),
    conflict_count INTEGER NOT NULL,
    resolved_count INTEGER NOT NULL DEFAULT 0,
    backup_dir TEXT,
    recovery_log_json TEXT,
    verification_evidence_json TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_local_sync_session_task_updated
    ON local_sync_conflict_session(task_id, updated_at DESC);
CREATE INDEX idx_local_sync_session_recovery
    ON local_sync_conflict_session(state, updated_at)
    WHERE state IN ('APPLYING', 'VERIFYING');

CREATE TABLE local_sync_conflict_file (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES local_sync_conflict_session(id) ON DELETE CASCADE,
    path TEXT NOT NULL,
    source_path TEXT NOT NULL,
    task_path TEXT NOT NULL,
    change_type TEXT NOT NULL CHECK (change_type IN (
        'ADD', 'MODIFY', 'DELETE', 'RENAME', 'ADD_ADD', 'MODIFY_DELETE',
        'DELETE_MODIFY', 'RENAME_CONFLICT'
    )),
    content_type TEXT NOT NULL CHECK (content_type IN ('TEXT', 'LARGE_TEXT', 'BINARY')),
    base_hash TEXT NOT NULL,
    source_hash TEXT NOT NULL,
    task_hash TEXT NOT NULL,
    base_mode TEXT NOT NULL,
    source_mode TEXT NOT NULL,
    task_mode TEXT NOT NULL,
    base_content TEXT,
    source_content TEXT,
    task_content TEXT,
    merged_content TEXT,
    resolution TEXT CHECK (resolution IN ('AUTO', 'SOURCE', 'TASK', 'MANUAL')),
    resolved_content TEXT,
    ai_suggestion TEXT,
    ai_suggestion_hash TEXT,
    external_dir TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(session_id, path)
);

CREATE INDEX idx_local_sync_file_session_path
    ON local_sync_conflict_file(session_id, path);
