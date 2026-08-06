CREATE TABLE task_lineage (
    child_task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    parent_task_id TEXT NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    recovery_mode TEXT NOT NULL CHECK (recovery_mode IN ('FROM_FAILED_STAGE', 'ALL_STAGES', 'VERIFY_ONLY')),
    parent_stage_id TEXT REFERENCES stage(id) ON DELETE SET NULL,
    workspace_fingerprint TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_task_lineage_parent_created
    ON task_lineage(parent_task_id, created_at DESC);

CREATE TABLE session_todo (
    id TEXT PRIMARY KEY,
    execution_session_id TEXT NOT NULL REFERENCES execution_session(id) ON DELETE CASCADE,
    external_todo_id TEXT NOT NULL,
    content TEXT NOT NULL,
    status TEXT NOT NULL,
    priority TEXT,
    ordinal INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(execution_session_id, external_todo_id)
);

CREATE INDEX idx_session_todo_session_ordinal
    ON session_todo(execution_session_id, ordinal);

CREATE TABLE session_checkpoint (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    execution_session_id TEXT NOT NULL REFERENCES execution_session(id) ON DELETE CASCADE,
    attempt_id TEXT NOT NULL REFERENCES attempt(id) ON DELETE CASCADE,
    external_message_id TEXT,
    message_refs_json TEXT NOT NULL,
    todo_refs_json TEXT NOT NULL,
    diff_ref_json TEXT NOT NULL,
    content_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_session_checkpoint_session_created
    ON session_checkpoint(execution_session_id, created_at DESC);

CREATE TABLE session_usage (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    execution_session_id TEXT REFERENCES execution_session(id) ON DELETE CASCADE,
    judge_run_id TEXT REFERENCES judge_run(id) ON DELETE CASCADE,
    external_message_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    provider_id TEXT,
    model_id TEXT,
    input_tokens INTEGER,
    output_tokens INTEGER,
    total_tokens INTEGER,
    cost_amount TEXT,
    currency TEXT,
    reliable INTEGER NOT NULL CHECK (reliable IN (0, 1)),
    observed_at TEXT NOT NULL,
    CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CHECK (output_tokens IS NULL OR output_tokens >= 0),
    CHECK (total_tokens IS NULL OR total_tokens >= 0),
    CHECK (
        (execution_session_id IS NOT NULL AND judge_run_id IS NULL)
        OR (execution_session_id IS NULL AND judge_run_id IS NOT NULL)
    )
);

CREATE INDEX idx_session_usage_task_observed
    ON session_usage(task_id, observed_at);

CREATE TRIGGER trg_session_usage_task_scope_insert
BEFORE INSERT ON session_usage
WHEN (NEW.execution_session_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM execution_session s WHERE s.id = NEW.execution_session_id AND s.task_id = NEW.task_id
    ))
    OR (NEW.judge_run_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM judge_run j WHERE j.id = NEW.judge_run_id AND j.task_id = NEW.task_id
    ))
BEGIN
    SELECT RAISE(ABORT, 'session usage must reference a session from the same task');
END;

CREATE TRIGGER trg_session_usage_identity_immutable
BEFORE UPDATE OF task_id, execution_session_id, judge_run_id, external_message_id, idempotency_key ON session_usage
BEGIN
    SELECT RAISE(ABORT, 'session usage identity is immutable');
END;

CREATE TABLE binary_artifact (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    attempt_id TEXT REFERENCES attempt(id) ON DELETE SET NULL,
    execution_session_id TEXT REFERENCES execution_session(id) ON DELETE SET NULL,
    verification_result_id TEXT REFERENCES verification_result(id) ON DELETE SET NULL,
    kind TEXT NOT NULL,
    media_type TEXT NOT NULL,
    relative_path TEXT NOT NULL UNIQUE,
    sha256 TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    metadata_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_binary_artifact_task_created
    ON binary_artifact(task_id, created_at DESC);
