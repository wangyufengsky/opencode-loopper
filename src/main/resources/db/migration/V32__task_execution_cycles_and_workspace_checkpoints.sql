CREATE TABLE task_execution_cycle (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    kind TEXT NOT NULL CHECK (kind IN ('INITIAL','CONTINUE_FAILED','CONTINUE_SUCCESS','READ_ONLY_AUDIT')),
    state TEXT NOT NULL CHECK (state IN ('RUNNING','SUCCEEDED','FAILED','INTERRUPTED','AUDIT_COMPLETED')),
    start_stage_id TEXT REFERENCES stage(id) ON DELETE SET NULL,
    start_stage_ordinal INTEGER,
    supplemental_prompt TEXT,
    budget_json TEXT NOT NULL,
    failure_code TEXT,
    failure_message TEXT,
    authorized_at TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(task_id, ordinal)
);

CREATE UNIQUE INDEX ux_task_execution_cycle_running
    ON task_execution_cycle(task_id) WHERE state='RUNNING';
CREATE INDEX idx_task_execution_cycle_task
    ON task_execution_cycle(task_id, ordinal DESC);

CREATE TABLE task_workspace_checkpoint (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    cycle_id TEXT NOT NULL UNIQUE REFERENCES task_execution_cycle(id) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK (state IN ('CAPTURING','READY','RESTORING','RESTORED','BLOCKED')),
    snapshot_id TEXT,
    canonical_root TEXT NOT NULL,
    root_fingerprint TEXT NOT NULL,
    branch_name TEXT NOT NULL,
    source_branch TEXT,
    baseline_commit TEXT,
    checkpoint_ref TEXT,
    checkpoint_commit TEXT,
    checkpoint_tree TEXT,
    manifest_json TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    stash_commit TEXT,
    blocker_code TEXT,
    blocker_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_task_workspace_checkpoint_task
    ON task_workspace_checkpoint(task_id, created_at DESC);

-- A successful result releases the in-place checkout while waiting for user confirmation.
-- Publication later reacquires the same FIFO lease as a distinct, auditable queue source.
CREATE TABLE task_queue_v32 (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    canonical_root TEXT NOT NULL,
    root_fingerprint TEXT NOT NULL,
    position INTEGER NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('MANUAL', 'RECOVERY', 'AUTOMATION', 'PUBLICATION')),
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'ADMITTED', 'CANCELLED', 'FINISHED')),
    enqueued_at TEXT NOT NULL,
    admitted_at TEXT,
    finished_at TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(canonical_root, position)
);
INSERT INTO task_queue_v32 SELECT * FROM task_queue;
DROP TABLE task_queue;
ALTER TABLE task_queue_v32 RENAME TO task_queue;
CREATE INDEX idx_task_queue_root_state_position
    ON task_queue(canonical_root, state, position);

ALTER TABLE attempt ADD COLUMN execution_cycle_id TEXT REFERENCES task_execution_cycle(id) ON DELETE SET NULL;
CREATE INDEX idx_attempt_cycle_stage ON attempt(execution_cycle_id, stage_id, ordinal DESC);

CREATE TABLE task_lineage_v32 (
    child_task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    parent_task_id TEXT NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    recovery_mode TEXT NOT NULL CHECK (recovery_mode IN ('FROM_FAILED_STAGE','ALL_STAGES','VERIFY_ONLY','REWORK_ALL_STAGES','INHERIT_CHANGES')),
    parent_stage_id TEXT REFERENCES stage(id) ON DELETE SET NULL,
    workspace_fingerprint TEXT NOT NULL,
    created_at TEXT NOT NULL
);
INSERT INTO task_lineage_v32 SELECT * FROM task_lineage;
DROP TABLE task_lineage;
ALTER TABLE task_lineage_v32 RENAME TO task_lineage;
CREATE INDEX idx_task_lineage_parent_created ON task_lineage(parent_task_id, created_at DESC);
