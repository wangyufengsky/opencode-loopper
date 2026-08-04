-- Final review is deliberately separate from an implementation session.  Each
-- retry is retained as an immutable judge_run row so a UI/API caller can see
-- why a task stopped instead of receiving an invented green result.
CREATE TABLE judge_run (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    attempt_id TEXT NOT NULL REFERENCES attempt(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    external_session_id TEXT,
    state TEXT NOT NULL,
    verdict TEXT,
    reason TEXT,
    raw_output TEXT,
    created_at TEXT NOT NULL,
    ended_at TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(task_id, role, ordinal)
);
CREATE INDEX idx_judge_run_task_role ON judge_run(task_id, role, ordinal DESC);
CREATE INDEX idx_judge_run_task_state ON judge_run(task_id, state);

-- Content is stored with metadata rather than a filesystem pointer.  This
-- keeps the diff/verifier/judge evidence available even after a worktree has
-- been cleaned or a local OpenCode runtime has stopped.
CREATE TABLE task_artifact (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    attempt_id TEXT REFERENCES attempt(id) ON DELETE SET NULL,
    judge_run_id TEXT REFERENCES judge_run(id) ON DELETE SET NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    content_type TEXT NOT NULL,
    content TEXT NOT NULL,
    metadata_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_task_artifact_task_created ON task_artifact(task_id, created_at DESC);
