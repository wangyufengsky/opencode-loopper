ALTER TABLE project_convention_draft ADD COLUMN normalization_notice TEXT;

CREATE TABLE ai_output_handling_event (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT NOT NULL,
    role TEXT NOT NULL,
    workflow_step TEXT NOT NULL,
    event_type TEXT NOT NULL,
    correction_categories_json TEXT NOT NULL,
    response_fingerprint TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(scope_type, scope_id, role, workflow_step, event_type, response_fingerprint)
);

CREATE INDEX idx_ai_output_handling_scope
    ON ai_output_handling_event(scope_type, scope_id, workflow_step, created_at);
