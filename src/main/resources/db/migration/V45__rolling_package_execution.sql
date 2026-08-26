-- New large software Tasks may close one package at a time. Historical Tasks remain
-- LEGACY_AGGREGATE and historical Designer packages remain in plan revision 1.
ALTER TABLE task ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'LEGACY_AGGREGATE'
    CHECK (execution_mode IN ('LEGACY_AGGREGATE','ROLLING_PACKAGES'));
ALTER TABLE task ADD COLUMN workspace_policy TEXT
    CHECK (workspace_policy IS NULL OR workspace_policy IN ('RELEASE_BETWEEN_PACKAGES','PINNED_DIRECT'));
ALTER TABLE designer_session ADD COLUMN task_id TEXT REFERENCES task(id) ON DELETE SET NULL;

-- Rebuild only the package design table so future plan revisions may reuse the
-- user-facing WP number without mutating the immutable historical row.
ALTER TABLE design_work_package RENAME TO design_work_package_v44;
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
    approved_design_revision INTEGER,
    discussion_round_count INTEGER NOT NULL DEFAULT 0,
    invalidated_by_package_id TEXT,
    approved_at TEXT,
    task_profile_id TEXT REFERENCES designer_task_profile(id),
    role_pack_id TEXT,
    role_pack_version TEXT,
    execution_strategy TEXT,
    test_policy TEXT,
    technologies_json TEXT NOT NULL DEFAULT '[]',
    project_stack_profile_id TEXT REFERENCES project_stack_profile(id),
    component_keys_json TEXT NOT NULL DEFAULT '[]',
    stack_fingerprint TEXT,
    plan_revision INTEGER NOT NULL DEFAULT 1 CHECK (plan_revision > 0),
    correction_of_package_id TEXT,
    superseded_at TEXT,
    UNIQUE(requirement_revision_id, plan_revision, package_id),
    UNIQUE(requirement_revision_id, plan_revision, ordinal)
);
INSERT INTO design_work_package(
    id,designer_session_id,requirement_revision_id,decomposition_id,package_id,ordinal,title,objective,
    scope_in_json,scope_out_json,dependencies_json,deliverables_json,acceptance_intent_json,
    requirement_refs_json,state,designer_external_session_id,designer_external_session_state,
    design_message_id,design_revision,redesign_count,designer_transport_retry_count,compiler_summary,
    handoff_summary,last_error_code,last_error_detail,created_at,updated_at,version,
    approved_design_revision,discussion_round_count,invalidated_by_package_id,approved_at,
    task_profile_id,role_pack_id,role_pack_version,execution_strategy,test_policy,technologies_json,
    project_stack_profile_id,component_keys_json,stack_fingerprint,plan_revision)
SELECT id,designer_session_id,requirement_revision_id,decomposition_id,package_id,ordinal,title,objective,
    scope_in_json,scope_out_json,dependencies_json,deliverables_json,acceptance_intent_json,
    requirement_refs_json,state,designer_external_session_id,designer_external_session_state,
    design_message_id,design_revision,redesign_count,designer_transport_retry_count,compiler_summary,
    handoff_summary,last_error_code,last_error_detail,created_at,updated_at,version,
    approved_design_revision,discussion_round_count,invalidated_by_package_id,approved_at,
    task_profile_id,role_pack_id,role_pack_version,execution_strategy,test_policy,technologies_json,
    project_stack_profile_id,component_keys_json,stack_fingerprint,1
FROM design_work_package_v44;
DROP TABLE design_work_package_v44;
CREATE INDEX idx_design_work_package_revision_ordinal
    ON design_work_package(requirement_revision_id,plan_revision,ordinal);
CREATE INDEX idx_design_work_package_active
    ON design_work_package(state,designer_external_session_id);
CREATE TRIGGER trg_work_package_token_usage_insert
AFTER INSERT ON design_work_package
WHEN NEW.designer_external_session_id IS NOT NULL
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.designer_external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;
CREATE TRIGGER trg_work_package_token_usage_update
AFTER UPDATE OF designer_external_session_id ON design_work_package
WHEN NEW.designer_external_session_id IS NOT NULL
 AND NEW.designer_external_session_id IS NOT OLD.designer_external_session_id
BEGIN
    INSERT OR IGNORE INTO model_token_usage(
        id,designer_session_id,external_session_id,reliable,complete,observed_at)
    VALUES(lower(hex(randomblob(16))),NEW.designer_session_id,NEW.designer_external_session_id,0,0,
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
END;

CREATE TABLE task_package_plan_revision (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE RESTRICT,
    requirement_revision_id TEXT NOT NULL REFERENCES design_requirement_revision(id) ON DELETE RESTRICT,
    revision INTEGER NOT NULL CHECK (revision > 0),
    state TEXT NOT NULL CHECK (state IN ('GENERATING','PROPOSED','ACTIVE','FAILED','SUPERSEDED')),
    origin TEXT NOT NULL DEFAULT 'INITIAL' CHECK (origin IN ('INITIAL','USER','AI','CORRECTION')),
    plan_json TEXT NOT NULL,
    impact_json TEXT NOT NULL DEFAULT '{}',
    external_session_id TEXT,
    external_session_state TEXT,
    last_error_code TEXT,
    last_error_detail TEXT,
    base_checkpoint_id TEXT REFERENCES task_workspace_checkpoint(id) ON DELETE RESTRICT,
    base_task_version INTEGER NOT NULL DEFAULT 0,
    base_package_run_id TEXT,
    base_package_version INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    approved_at TEXT,
    superseded_at TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(task_id,revision)
);
CREATE UNIQUE INDEX ux_task_package_plan_active
    ON task_package_plan_revision(task_id) WHERE state='ACTIVE';

CREATE TABLE task_package_run (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    plan_revision_id TEXT NOT NULL REFERENCES task_package_plan_revision(id) ON DELETE RESTRICT,
    design_work_package_id TEXT NOT NULL REFERENCES design_work_package(id) ON DELETE RESTRICT,
    package_key TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    title TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN (
        'PLANNED','DESIGNING','DESIGN_REVIEW','EXECUTION_READY','QUEUED','RUNNING','VERIFYING',
        'CHECKPOINTING','FACT_FROZEN','WAITING_INPUT','SUPERSEDED','CANCELLED')),
    correction_of_package_run_id TEXT REFERENCES task_package_run(id) ON DELETE RESTRICT,
    discussion_revision INTEGER NOT NULL DEFAULT 0,
    design_revision INTEGER NOT NULL DEFAULT 0,
    accepted_design_revision INTEGER,
    waiting_reason_code TEXT,
    resume_checkpoint_id TEXT REFERENCES task_workspace_checkpoint(id) ON DELETE RESTRICT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(plan_revision_id,package_key),
    UNIQUE(plan_revision_id,ordinal)
);
CREATE INDEX idx_task_package_run_task_ordinal ON task_package_run(task_id,ordinal);
CREATE INDEX idx_task_package_run_task_state ON task_package_run(task_id,state);

CREATE TABLE task_spec_revision (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL CHECK (revision > 0),
    package_run_id TEXT NOT NULL REFERENCES task_package_run(id) ON DELETE RESTRICT,
    spec_json TEXT NOT NULL,
    spec_sha256 TEXT NOT NULL,
    stage_count INTEGER NOT NULL CHECK (stage_count > 0),
    created_at TEXT NOT NULL,
    UNIQUE(task_id,revision)
);
CREATE INDEX idx_task_spec_revision_latest ON task_spec_revision(task_id,revision DESC);

CREATE TABLE package_fact_snapshot (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    package_run_id TEXT NOT NULL UNIQUE REFERENCES task_package_run(id) ON DELETE RESTRICT,
    checkpoint_id TEXT NOT NULL REFERENCES task_workspace_checkpoint(id) ON DELETE RESTRICT,
    successful_attempt_id TEXT NOT NULL REFERENCES attempt(id) ON DELETE RESTRICT,
    input_tree TEXT,
    output_tree TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    diff_sha256 TEXT NOT NULL,
    evidence_sha256 TEXT NOT NULL,
    proven_json TEXT NOT NULL,
    accepted_contract_json TEXT NOT NULL,
    navigation_summary TEXT NOT NULL,
    task_spec_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_package_fact_task_created ON package_fact_snapshot(task_id,created_at);

ALTER TABLE stage ADD COLUMN package_run_id TEXT REFERENCES task_package_run(id) ON DELETE RESTRICT;
CREATE INDEX idx_stage_package_run ON stage(package_run_id,ordinal);
ALTER TABLE task_execution_cycle ADD COLUMN package_run_id TEXT REFERENCES task_package_run(id) ON DELETE RESTRICT;
ALTER TABLE task_execution_cycle ADD COLUMN cycle_type TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (cycle_type IN ('LEGACY','PACKAGE_EXECUTION','PACKAGE_RETRY','FINAL_REVIEW'));
CREATE INDEX idx_task_execution_cycle_package_run ON task_execution_cycle(package_run_id,ordinal DESC);

-- PACKAGE is a durable reason for reacquiring a Git checkout after a package checkpoint.
ALTER TABLE task_queue RENAME TO task_queue_v44;
CREATE TABLE task_queue (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    canonical_root TEXT NOT NULL,
    root_fingerprint TEXT NOT NULL,
    position INTEGER NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('MANUAL','RECOVERY','AUTOMATION','PUBLICATION','PACKAGE')),
    state TEXT NOT NULL CHECK (state IN ('QUEUED','ADMITTED','CANCELLED','FINISHED')),
    enqueued_at TEXT NOT NULL,
    admitted_at TEXT,
    finished_at TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(canonical_root,position)
);
INSERT INTO task_queue SELECT * FROM task_queue_v44;
DROP TABLE task_queue_v44;
CREATE INDEX idx_task_queue_root_state_position ON task_queue(canonical_root,state,position);
