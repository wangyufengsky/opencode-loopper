-- Split the read-only design workflow into a Markdown Designer, a separate
-- LoopSpec Compiler, and a deterministic server-side validator.
ALTER TABLE designer_session ADD COLUMN workflow_phase TEXT NOT NULL DEFAULT 'DESIGNING';
ALTER TABLE designer_session ADD COLUMN design_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE designer_session ADD COLUMN redesign_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE designer_message ADD COLUMN actor TEXT NOT NULL DEFAULT 'SYSTEM';
UPDATE designer_message
SET actor = CASE role
    WHEN 'USER' THEN 'USER'
    WHEN 'ASSISTANT' THEN 'DESIGNER'
    ELSE 'SYSTEM'
END;

CREATE TABLE loop_spec_compilation (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    design_revision INTEGER NOT NULL,
    state TEXT NOT NULL,
    external_session_id TEXT,
    external_session_state TEXT,
    repair_count INTEGER NOT NULL DEFAULT 0,
    source_design_message_id TEXT NOT NULL REFERENCES designer_message(id),
    source_draft_version INTEGER NOT NULL,
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_loop_spec_compilation_session_revision
    ON loop_spec_compilation(designer_session_id, design_revision DESC, created_at DESC);
CREATE INDEX idx_loop_spec_compilation_active
    ON loop_spec_compilation(state, external_session_id);

-- The snapshot is immutable: it records the production-Java diff that already
-- existed when a Stage first started, so retries compare against one stable gate.
CREATE TABLE stage_java_baseline (
    stage_id TEXT PRIMARY KEY REFERENCES stage(id) ON DELETE CASCADE,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    snapshot_json TEXT NOT NULL,
    snapshot_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_stage_java_baseline_task ON stage_java_baseline(task_id);
