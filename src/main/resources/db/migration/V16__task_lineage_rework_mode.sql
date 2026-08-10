CREATE TABLE task_lineage_v16 (
    child_task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    parent_task_id TEXT NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    recovery_mode TEXT NOT NULL CHECK (recovery_mode IN ('FROM_FAILED_STAGE', 'ALL_STAGES', 'VERIFY_ONLY', 'REWORK_ALL_STAGES')),
    parent_stage_id TEXT REFERENCES stage(id) ON DELETE SET NULL,
    workspace_fingerprint TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO task_lineage_v16(child_task_id, parent_task_id, recovery_mode, parent_stage_id, workspace_fingerprint, created_at)
SELECT child_task_id, parent_task_id, recovery_mode, parent_stage_id, workspace_fingerprint, created_at
FROM task_lineage;

DROP TABLE task_lineage;
ALTER TABLE task_lineage_v16 RENAME TO task_lineage;

CREATE INDEX idx_task_lineage_parent_created
    ON task_lineage(parent_task_id, created_at DESC);
