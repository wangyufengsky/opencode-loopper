CREATE TABLE project_stack_profile (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    analysis_state TEXT NOT NULL CHECK (analysis_state IN ('READY','PARTIAL','FAILED')),
    manifest_fingerprint TEXT NOT NULL,
    technology_families_json TEXT NOT NULL DEFAULT '[]',
    technologies_json TEXT NOT NULL DEFAULT '[]',
    evidence_json TEXT NOT NULL DEFAULT '[]',
    files_scanned INTEGER NOT NULL DEFAULT 0 CHECK (files_scanned >= 0),
    component_count INTEGER NOT NULL DEFAULT 0 CHECK (component_count >= 0),
    error_code TEXT,
    error_detail TEXT,
    analyzed_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_project_stack_profile_project_analyzed
    ON project_stack_profile(project_id, analyzed_at DESC, id DESC);

CREATE TABLE project_stack_component (
    profile_id TEXT NOT NULL REFERENCES project_stack_profile(id) ON DELETE CASCADE,
    component_key TEXT NOT NULL,
    relative_root TEXT NOT NULL,
    technology_families_json TEXT NOT NULL DEFAULT '[]',
    technologies_json TEXT NOT NULL DEFAULT '[]',
    build_tools_json TEXT NOT NULL DEFAULT '[]',
    test_frameworks_json TEXT NOT NULL DEFAULT '[]',
    manifest_sources_json TEXT NOT NULL DEFAULT '[]',
    evidence_json TEXT NOT NULL DEFAULT '[]',
    PRIMARY KEY (profile_id, component_key)
);

CREATE INDEX idx_project_stack_component_profile_root
    ON project_stack_component(profile_id, relative_root);

ALTER TABLE designer_task_profile ADD COLUMN project_stack_profile_id TEXT REFERENCES project_stack_profile(id);
ALTER TABLE designer_task_profile ADD COLUMN component_keys_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE designer_task_profile ADD COLUMN stack_fingerprint TEXT;

ALTER TABLE task_profile_router_run ADD COLUMN project_stack_profile_id TEXT REFERENCES project_stack_profile(id);
ALTER TABLE task_profile_router_run ADD COLUMN component_keys_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE task_profile_router_run ADD COLUMN stack_fingerprint TEXT;

ALTER TABLE design_work_package ADD COLUMN project_stack_profile_id TEXT REFERENCES project_stack_profile(id);
ALTER TABLE design_work_package ADD COLUMN component_keys_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE design_work_package ADD COLUMN stack_fingerprint TEXT;

ALTER TABLE stage ADD COLUMN project_stack_profile_id TEXT REFERENCES project_stack_profile(id);
ALTER TABLE stage ADD COLUMN component_keys_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE stage ADD COLUMN stack_fingerprint TEXT;

ALTER TABLE project_convention_draft ADD COLUMN project_stack_profile_id TEXT REFERENCES project_stack_profile(id);
ALTER TABLE project_convention_draft ADD COLUMN stack_fingerprint TEXT;
