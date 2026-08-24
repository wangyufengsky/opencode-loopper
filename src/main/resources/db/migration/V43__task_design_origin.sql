ALTER TABLE task_lineage
    ADD COLUMN design_source_task_id TEXT REFERENCES task(id) ON DELETE RESTRICT;

ALTER TABLE task_lineage
    ADD COLUMN design_source_loop_draft_id TEXT REFERENCES loop_draft(id) ON DELETE RESTRICT;

ALTER TABLE task_lineage
    ADD COLUMN design_source_designer_session_id TEXT REFERENCES designer_session(id) ON DELETE RESTRICT;

CREATE INDEX idx_task_lineage_design_source
    ON task_lineage(design_source_task_id, design_source_designer_session_id);

CREATE TABLE project_convention_runtime (
    draft_id TEXT PRIMARY KEY REFERENCES project_convention_draft(id) ON DELETE CASCADE,
    last_progress_at TEXT NOT NULL,
    progress_fingerprint TEXT NOT NULL,
    stop_reason TEXT,
    stop_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_project_convention_runtime_progress
    ON project_convention_runtime(last_progress_at);
