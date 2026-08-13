-- Explicit GIT_DIFF is a Stage-local gate. Persist one immutable workspace
-- tree before the Stage's first writable Attempt while final Task diff evidence
-- continues to use the Task admission baseline.
CREATE TABLE stage_workspace_baseline (
    stage_id TEXT PRIMARY KEY REFERENCES stage(id) ON DELETE CASCADE,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    baseline_ref TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_stage_workspace_baseline_task ON stage_workspace_baseline(task_id);
