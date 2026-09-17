CREATE TABLE template_batch_retry_policy (
    batch_id TEXT PRIMARY KEY REFERENCES template_task_batch(id) ON DELETE CASCADE,
    automatic_retries INTEGER NOT NULL CHECK(automatic_retries BETWEEN 0 AND 3),
    retry_limit INTEGER NOT NULL CHECK(retry_limit BETWEEN 0 AND 3)
);

CREATE TABLE template_batch_transport_issue (
    batch_id TEXT PRIMARY KEY REFERENCES template_task_batch(id) ON DELETE CASCADE,
    session_id TEXT,
    prompt_sha256 TEXT,
    operation TEXT NOT NULL,
    error_code TEXT NOT NULL,
    error_message TEXT NOT NULL,
    first_failed_at TEXT NOT NULL,
    last_failed_at TEXT NOT NULL,
    failures INTEGER NOT NULL CHECK(failures > 0),
    next_check_at TEXT NOT NULL,
    blocks_dispatch INTEGER NOT NULL CHECK(blocks_dispatch IN (0,1)),
    resolved_at TEXT
);
CREATE INDEX template_batch_transport_pending ON template_batch_transport_issue(resolved_at,next_check_at);
