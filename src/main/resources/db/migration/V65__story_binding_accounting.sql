CREATE TABLE story_binding (
    id TEXT PRIMARY KEY,
    system_code TEXT NOT NULL CHECK (length(system_code) BETWEEN 1 AND 128),
    story_code TEXT NOT NULL CHECK (length(story_code) BETWEEN 1 AND 128),
    next_session_ordinal INTEGER NOT NULL DEFAULT 0 CHECK (next_session_ordinal >= 0),
    created_at TEXT NOT NULL
);

CREATE TABLE designer_story_binding (
    designer_session_id TEXT PRIMARY KEY REFERENCES designer_session(id) ON DELETE CASCADE,
    binding_id TEXT NOT NULL REFERENCES story_binding(id) ON DELETE RESTRICT
);

CREATE TABLE task_story_binding (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    binding_id TEXT NOT NULL REFERENCES story_binding(id) ON DELETE RESTRICT
);

CREATE TRIGGER trg_task_story_binding_freeze
AFTER INSERT ON task
BEGIN
    INSERT INTO task_story_binding(task_id,binding_id)
    SELECT NEW.id,link.binding_id FROM designer_session session
    JOIN designer_story_binding link ON link.designer_session_id=session.id
    WHERE session.loop_draft_id=NEW.loop_draft_id
    ORDER BY session.created_at DESC,session.id DESC LIMIT 1;
END;

CREATE TABLE story_accounting_session (
    id TEXT PRIMARY KEY,
    binding_id TEXT NOT NULL REFERENCES story_binding(id) ON DELETE RESTRICT,
    designer_session_id TEXT REFERENCES designer_session(id) ON DELETE CASCADE,
    task_id TEXT REFERENCES task(id) ON DELETE CASCADE,
    external_session_id TEXT NOT NULL UNIQUE,
    runtime_generation_id TEXT,
    worktree_path TEXT NOT NULL,
    role TEXT NOT NULL CHECK (length(role) BETWEEN 1 AND 96),
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    bind_operation TEXT NOT NULL CHECK (bind_operation IN ('start','continue')),
    owner_observed INTEGER NOT NULL DEFAULT 0 CHECK (owner_observed IN (0,1)),
    state TEXT NOT NULL CHECK (state IN ('BINDING','ACTIVE','BIND_FAILED','COMPLETING','COMPLETED','COMPLETE_FAILED')),
    plugin_run_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK ((designer_session_id IS NOT NULL) + (task_id IS NOT NULL) = 1),
    UNIQUE(binding_id,ordinal)
);

CREATE TABLE story_accounting_call (
    id TEXT PRIMARY KEY,
    accounting_session_id TEXT NOT NULL REFERENCES story_accounting_session(id) ON DELETE CASCADE,
    phase TEXT NOT NULL CHECK (phase IN ('BEGIN','COMPLETE')),
    message_id TEXT NOT NULL UNIQUE,
    operation TEXT NOT NULL CHECK (operation IN ('start','continue','complete')),
    arguments_text TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PREPARED','SUCCEEDED','FAILED','UNKNOWN')),
    plugin_run_id TEXT,
    result_text TEXT,
    error_code TEXT,
    error_detail TEXT,
    notification_emitted INTEGER NOT NULL DEFAULT 0 CHECK (notification_emitted IN (0,1)),
    started_at TEXT NOT NULL,
    finished_at TEXT,
    UNIQUE(accounting_session_id,phase)
);

CREATE INDEX idx_story_accounting_scope
    ON story_accounting_session(designer_session_id,task_id,created_at);
CREATE INDEX idx_story_accounting_call_state
    ON story_accounting_call(state,started_at);

CREATE VIEW story_accounting_active_remote AS
    SELECT external_session_id FROM designer_session
    WHERE external_session_id IS NOT NULL AND state IN ('PENDING_HANDOFF','RUNNING','REVIEWING','WAITING_INPUT','STOPPING')
      AND workflow_phase IN ('DISCUSSING_REQUIREMENT','DESIGNING','REDESIGNING','QUESTIONING_PACKAGE','REVIEWING_PACKAGE')
    UNION SELECT external_session_id FROM task_profile_router_run WHERE state IN ('PENDING','RUNNING')
    UNION SELECT external_session_id FROM task_decomposition WHERE state IN ('PENDING_HANDOFF','RUNNING','VALIDATING','NEEDS_INPUT')
    UNION SELECT designer_external_session_id FROM design_work_package WHERE state IN ('QUESTIONING','DESIGNING','REVIEWING','WAITING_INPUT')
    UNION SELECT external_session_id FROM loop_spec_compilation WHERE state IN ('PENDING_HANDOFF','RUNNING')
    UNION SELECT external_session_id FROM analysis_report WHERE state IN ('RUNNING','VALIDATING')
    UNION SELECT external_session_id FROM execution_session WHERE state IN ('CREATING','RUNNING','DISCONNECTED')
    UNION SELECT external_session_id FROM judge_run WHERE state IN ('CREATING','RUNNING')
    UNION SELECT external_session_id FROM task_package_plan_revision WHERE state='GENERATING'
    UNION SELECT external_session_id FROM ai_candidate_submission_run WHERE state IN ('OPEN','WAITING_INPUT');

-- Historical sessions are deliberately not backfilled. Story accounting is
-- opt-in at Designer creation and only later remote Sessions acquire calls.
