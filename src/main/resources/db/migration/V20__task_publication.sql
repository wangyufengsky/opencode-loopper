CREATE TABLE task_publication (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    state TEXT NOT NULL,
    remote_name TEXT,
    remote_url TEXT,
    provider TEXT NOT NULL,
    source_branch TEXT NOT NULL,
    target_branch TEXT,
    task_commit_sha TEXT,
    commit_message TEXT,
    creation_requested_at TEXT,
    merge_request_iid INTEGER,
    merge_request_url TEXT,
    merge_request_state TEXT,
    merge_request_head_sha TEXT,
    merge_commit_sha TEXT,
    merge_request_opened_at TEXT,
    merged_at TEXT,
    last_checked_at TEXT,
    last_check_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_task_publication_state_updated
    ON task_publication(state, updated_at);
