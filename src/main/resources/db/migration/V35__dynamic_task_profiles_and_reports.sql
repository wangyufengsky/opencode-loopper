CREATE TABLE designer_task_profile (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    requirement_revision_id TEXT REFERENCES design_requirement_revision(id) ON DELETE SET NULL,
    state TEXT NOT NULL CHECK (state IN ('PROVISIONAL','FROZEN','SUPERSEDED')),
    intent TEXT NOT NULL,
    workflow_template TEXT NOT NULL,
    mutation_mode TEXT NOT NULL,
    artifact_kinds_json TEXT NOT NULL DEFAULT '[]',
    technologies_json TEXT NOT NULL DEFAULT '[]',
    test_policy TEXT NOT NULL,
    execution_strategy TEXT NOT NULL,
    role_pack_id TEXT NOT NULL,
    role_pack_version TEXT NOT NULL,
    confidence INTEGER NOT NULL CHECK (confidence BETWEEN 0 AND 100),
    evidence_json TEXT NOT NULL DEFAULT '[]',
    resolution_source TEXT NOT NULL,
    decision_required INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_designer_task_profile_session_state
    ON designer_task_profile(designer_session_id,state,created_at DESC);

CREATE TABLE analysis_report (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    task_profile_id TEXT NOT NULL REFERENCES designer_task_profile(id),
    state TEXT NOT NULL CHECK (state IN ('RUNNING','VALIDATING','READY','FAILED','SUPERSEDED')),
    title TEXT NOT NULL,
    markdown TEXT NOT NULL DEFAULT '',
    evidence_json TEXT NOT NULL DEFAULT '[]',
    content_sha256 TEXT,
    source_snapshot_sha256 TEXT,
    error_code TEXT,
    error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_analysis_report_session_created
    ON analysis_report(designer_session_id,created_at DESC);

CREATE TABLE artifact_plan (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    task_profile_id TEXT NOT NULL REFERENCES designer_task_profile(id),
    kind TEXT NOT NULL CHECK (kind IN ('DOCUMENT','TABULAR_CONVERSION')),
    state TEXT NOT NULL CHECK (state IN ('PROVISIONAL','FROZEN','SUPERSEDED')),
    plan_json TEXT NOT NULL,
    plan_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_artifact_plan_session_state ON artifact_plan(designer_session_id,state,created_at DESC);

ALTER TABLE design_requirement_revision ADD COLUMN task_profile_id TEXT REFERENCES designer_task_profile(id);
ALTER TABLE task_decomposition ADD COLUMN task_profile_id TEXT REFERENCES designer_task_profile(id);
ALTER TABLE design_work_package ADD COLUMN task_profile_id TEXT REFERENCES designer_task_profile(id);
ALTER TABLE design_work_package ADD COLUMN role_pack_id TEXT;
ALTER TABLE design_work_package ADD COLUMN role_pack_version TEXT;
ALTER TABLE task ADD COLUMN task_profile_id TEXT REFERENCES designer_task_profile(id);
ALTER TABLE task ADD COLUMN role_pack_id TEXT;
ALTER TABLE task ADD COLUMN role_pack_version TEXT;
ALTER TABLE stage ADD COLUMN stage_kind TEXT;
ALTER TABLE stage ADD COLUMN execution_strategy TEXT;
ALTER TABLE stage ADD COLUMN artifact_plan_id TEXT;
