-- Read-model indexes for cursor pagination and batch task/detail projections.
-- These do not change lifecycle data or write-side constraints.
CREATE INDEX idx_task_updated_id
    ON task(updated_at DESC, id DESC);

CREATE INDEX idx_task_project_updated_id
    ON task(project_id, updated_at DESC, id DESC);

CREATE INDEX idx_designer_session_draft_created_id
    ON designer_session(loop_draft_id, created_at DESC, id DESC);

CREATE INDEX idx_designer_session_updated_id
    ON designer_session(updated_at DESC, id DESC);

CREATE INDEX idx_attempt_task_created
    ON attempt(task_id, created_at DESC);

CREATE INDEX idx_execution_session_task_created
    ON execution_session(task_id, created_at DESC);

CREATE INDEX idx_automation_run_detected_id
    ON automation_run(detected_at DESC, id DESC);
