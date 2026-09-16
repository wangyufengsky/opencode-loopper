CREATE TABLE template_batch_recovery (
    batch_id TEXT PRIMARY KEY REFERENCES template_task_batch(id) ON DELETE CASCADE,
    session_id TEXT NOT NULL,
    prompt_sha256 TEXT NOT NULL,
    action TEXT NOT NULL CHECK(action IN ('FINALIZE','STOP')),
    command_id TEXT NOT NULL,
    requested_at TEXT NOT NULL,
    not_before TEXT NOT NULL,
    proof TEXT CHECK(proof IN ('REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')),
    proof_at TEXT,
    last_attempt_at TEXT,
    error_code TEXT
);
CREATE TABLE template_batch_observation (
    batch_id TEXT PRIMARY KEY REFERENCES template_task_batch(id) ON DELETE CASCADE,
    session_id TEXT NOT NULL,
    prompt_sha256 TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    last_activity_at TEXT,
    last_progress_at TEXT,
    fingerprint TEXT,
    progress_fingerprint TEXT,
    remote_state TEXT,
    connected INTEGER NOT NULL CHECK(connected IN (0,1))
);
CREATE INDEX template_batch_recovery_pending ON template_batch_recovery(proof,not_before);
CREATE TABLE template_batch_recovery_command (
    command_id TEXT PRIMARY KEY,
    batch_id TEXT NOT NULL REFERENCES template_task_batch(id) ON DELETE CASCADE,
    action TEXT NOT NULL CHECK(action IN ('FINALIZE','STOP')),
    expected_version INTEGER NOT NULL,
    created_at TEXT NOT NULL
);
