-- Freeze each complete requirement revision, decompose it in an independent
-- read-only Session, and persist the serial package design/compile workflow.
ALTER TABLE designer_session ADD COLUMN current_requirement_revision INTEGER;
ALTER TABLE designer_session ADD COLUMN active_work_package_id TEXT;

ALTER TABLE designer_message ADD COLUMN requirement_revision INTEGER;
ALTER TABLE designer_message ADD COLUMN work_package_id TEXT;

ALTER TABLE loop_spec_compilation ADD COLUMN work_package_id TEXT;
ALTER TABLE loop_spec_compilation ADD COLUMN transport_retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE loop_spec_compilation ADD COLUMN compiled_package_json TEXT;

-- This denormalized projection makes package attempt accounting and task DTOs
-- deterministic without re-reading a mutable draft. The LoopSpec remains the
-- authoritative contract.
ALTER TABLE stage ADD COLUMN work_package_id TEXT;
CREATE INDEX idx_stage_task_work_package ON stage(task_id, work_package_id, ordinal);

CREATE TABLE design_requirement_revision (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    source_message_id TEXT NOT NULL REFERENCES designer_message(id),
    requirement_text TEXT NOT NULL,
    requirement_segments_json TEXT NOT NULL,
    source_draft_version INTEGER NOT NULL,
    state TEXT NOT NULL,
    model_calls_used INTEGER NOT NULL DEFAULT 0,
    max_model_calls INTEGER NOT NULL DEFAULT 24,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(designer_session_id, revision)
);
CREATE INDEX idx_design_requirement_revision_current
    ON design_requirement_revision(designer_session_id, revision DESC);

CREATE TABLE task_decomposition (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    requirement_revision_id TEXT NOT NULL REFERENCES design_requirement_revision(id) ON DELETE CASCADE,
    state TEXT NOT NULL,
    result_type TEXT,
    normalized_goal TEXT,
    global_constraints_json TEXT NOT NULL DEFAULT '[]',
    plan_json TEXT NOT NULL DEFAULT '{}',
    external_session_id TEXT,
    external_session_state TEXT,
    repair_count INTEGER NOT NULL DEFAULT 0,
    transport_retry_count INTEGER NOT NULL DEFAULT 0,
    source_draft_version INTEGER NOT NULL,
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_task_decomposition_revision
    ON task_decomposition(requirement_revision_id, created_at DESC);
CREATE INDEX idx_task_decomposition_active
    ON task_decomposition(state, external_session_id);

CREATE TABLE design_work_package (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    requirement_revision_id TEXT NOT NULL REFERENCES design_requirement_revision(id) ON DELETE CASCADE,
    decomposition_id TEXT NOT NULL REFERENCES task_decomposition(id) ON DELETE CASCADE,
    package_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    title TEXT NOT NULL,
    objective TEXT NOT NULL,
    scope_in_json TEXT NOT NULL,
    scope_out_json TEXT NOT NULL,
    dependencies_json TEXT NOT NULL,
    deliverables_json TEXT NOT NULL,
    acceptance_intent_json TEXT NOT NULL,
    requirement_refs_json TEXT NOT NULL,
    state TEXT NOT NULL,
    designer_external_session_id TEXT,
    designer_external_session_state TEXT,
    design_message_id TEXT REFERENCES designer_message(id),
    design_revision INTEGER NOT NULL DEFAULT 0,
    redesign_count INTEGER NOT NULL DEFAULT 0,
    designer_transport_retry_count INTEGER NOT NULL DEFAULT 0,
    compiler_summary TEXT,
    handoff_summary TEXT,
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(requirement_revision_id, package_id),
    UNIQUE(requirement_revision_id, ordinal)
);
CREATE INDEX idx_design_work_package_revision_ordinal
    ON design_work_package(requirement_revision_id, ordinal);
CREATE INDEX idx_design_work_package_active
    ON design_work_package(state, designer_external_session_id);
