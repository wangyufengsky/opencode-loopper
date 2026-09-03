-- Existing designers deliberately keep their original lifecycle.
CREATE TABLE designer_conversation_policy (
    designer_session_id TEXT PRIMARY KEY REFERENCES designer_session(id) ON DELETE CASCADE,
    policy TEXT NOT NULL CHECK(policy='PER_PACKAGE_V1')
);
CREATE TABLE designer_conversation (
    id TEXT PRIMARY KEY,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    scope_key TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK(generation>0),
    external_session_id TEXT UNIQUE,
    runtime_generation_id TEXT,
    internal_mcp_server TEXT,
    root_path TEXT NOT NULL,
    profile TEXT NOT NULL,
    model_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('CREATING','OPEN','RETIRED','UNKNOWN')),
    reason TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(designer_session_id,scope_key,generation)
);
CREATE UNIQUE INDEX ux_designer_conversation_active
    ON designer_conversation(designer_session_id,scope_key) WHERE state!='RETIRED';
CREATE TABLE designer_conversation_turn (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES designer_conversation(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL UNIQUE,
    phase TEXT NOT NULL CHECK(phase IN ('REQUIREMENT','PACKAGE_QUESTION','PACKAGE_DESIGN')),
    candidate_run_id TEXT NOT NULL UNIQUE,
    request_json TEXT,
    request_sha256 TEXT,
    state TEXT NOT NULL CHECK(state IN ('PREPARED','SENDING','SENT','SETTLED','UNKNOWN')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_designer_conversation_active_turn
    ON designer_conversation_turn(conversation_id) WHERE state!='SETTLED';
CREATE INDEX idx_designer_turn_history ON designer_conversation_turn(conversation_id,created_at,id);

-- A reusable designer stays alive during compilation/validation and human review.
DROP VIEW story_accounting_active_remote;
CREATE VIEW story_accounting_active_remote AS
SELECT external_session_id FROM (
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
    UNION SELECT external_session_id FROM ai_candidate_submission_run WHERE state IN ('OPEN','WAITING_INPUT')
) legacy WHERE NOT EXISTS (SELECT 1 FROM designer_conversation c WHERE c.external_session_id=legacy.external_session_id)
UNION SELECT c.external_session_id FROM designer_conversation c JOIN designer_session d ON d.id=c.designer_session_id
WHERE c.state!='RETIRED' AND c.external_session_id IS NOT NULL AND d.state NOT IN ('COMPLETED','CANCELLED');

CREATE TRIGGER retire_approved_designer_conversation AFTER UPDATE OF state ON design_work_package
WHEN NEW.state IN ('STALE','SUPERSEDED')
BEGIN
  UPDATE designer_conversation SET state='RETIRED',reason='PACKAGE_' || NEW.state,
    updated_at=NEW.updated_at,version=version+1
  WHERE scope_key=NEW.id AND state!='RETIRED';
END;
CREATE TRIGGER retire_terminal_designer_conversation AFTER UPDATE OF state ON designer_session
WHEN NEW.state IN ('COMPLETED','CANCELLED')
BEGIN
  UPDATE designer_conversation SET state='RETIRED',reason='DESIGNER_' || NEW.state,
    updated_at=NEW.updated_at,version=version+1
  WHERE designer_session_id=NEW.id AND state!='RETIRED';
END;

CREATE TRIGGER require_open_designer_turn BEFORE INSERT ON designer_conversation_turn
WHEN NOT EXISTS (SELECT 1 FROM designer_conversation WHERE id=NEW.conversation_id AND state='OPEN')
BEGIN SELECT RAISE(ABORT,'design conversation is retired or not bound'); END;
CREATE TRIGGER require_open_designer_dispatch BEFORE UPDATE OF state ON designer_conversation_turn
WHEN NEW.state='SENDING' AND NOT EXISTS (SELECT 1 FROM designer_conversation WHERE id=NEW.conversation_id AND state='OPEN')
BEGIN SELECT RAISE(ABORT,'design conversation is retired or not bound'); END;

-- Direct software's automatic package approval is only an internal aggregation step.
-- Its actual design handoff is the user's (or authorized auto-mode's) draft confirmation.
CREATE TRIGGER retire_confirmed_draft_designer_conversation AFTER UPDATE OF status ON loop_draft
WHEN NEW.status='CONFIRMED' AND OLD.status!='CONFIRMED'
BEGIN
  UPDATE designer_conversation SET state='RETIRED',reason='DRAFT_CONFIRMED',
    updated_at=NEW.updated_at,version=version+1
  WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=NEW.id)
    AND state!='RETIRED';
END;
