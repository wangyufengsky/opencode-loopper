CREATE TABLE project (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    root_path TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE loop_draft (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES project(id),
    goal TEXT NOT NULL,
    spec_json TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE task (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES project(id),
    loop_draft_id TEXT REFERENCES loop_draft(id),
    title TEXT NOT NULL,
    state TEXT NOT NULL,
    worktree_path TEXT,
    branch_name TEXT,
    baseline_commit TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_task_project_created ON task(project_id, created_at DESC);
CREATE INDEX idx_task_state ON task(state);

CREATE TABLE stage (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    objective TEXT NOT NULL,
    allowed_paths_json TEXT NOT NULL,
    forbidden_paths_json TEXT NOT NULL,
    deliverables_json TEXT NOT NULL,
    verifiers_json TEXT NOT NULL,
    state TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(task_id, ordinal)
);

CREATE TABLE attempt (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    stage_id TEXT NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    state TEXT NOT NULL,
    failure_kind TEXT,
    summary TEXT,
    created_at TEXT NOT NULL,
    ended_at TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(stage_id, ordinal)
);
CREATE INDEX idx_attempt_task_stage ON attempt(task_id, stage_id, ordinal DESC);

CREATE TABLE execution_session (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    stage_id TEXT NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    attempt_id TEXT NOT NULL REFERENCES attempt(id) ON DELETE CASCADE,
    external_session_id TEXT,
    state TEXT NOT NULL,
    created_at TEXT NOT NULL,
    ended_at TEXT,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_execution_session_attempt ON execution_session(attempt_id);

CREATE TABLE verification_result (
    id TEXT PRIMARY KEY,
    attempt_id TEXT NOT NULL REFERENCES attempt(id) ON DELETE CASCADE,
    verifier_index INTEGER NOT NULL,
    type TEXT NOT NULL,
    state TEXT NOT NULL,
    summary TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(attempt_id, verifier_index)
);

CREATE TABLE error_event (
    id TEXT PRIMARY KEY,
    task_id TEXT REFERENCES task(id) ON DELETE CASCADE,
    stage_id TEXT REFERENCES stage(id) ON DELETE CASCADE,
    attempt_id TEXT REFERENCES attempt(id) ON DELETE CASCADE,
    session_id TEXT REFERENCES execution_session(id) ON DELETE SET NULL,
    layer TEXT NOT NULL,
    code TEXT NOT NULL,
    message TEXT NOT NULL,
    retryable INTEGER NOT NULL,
    evidence_json TEXT,
    occurred_at TEXT NOT NULL
);
CREATE INDEX idx_error_event_task ON error_event(task_id, occurred_at DESC);

CREATE TABLE task_event (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL,
    type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    UNIQUE(task_id, sequence)
);
CREATE INDEX idx_task_event_task_sequence ON task_event(task_id, sequence);
