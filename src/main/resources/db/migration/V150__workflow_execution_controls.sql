CREATE TABLE workflow_run_control (
    requirement_id TEXT PRIMARY KEY REFERENCES workflow_requirement(id),
    plan_revision INTEGER NOT NULL,
    mode TEXT NOT NULL CHECK(mode IN ('SINGLE','UNTIL','CONTINUOUS')),
    target_key TEXT,
    model_json TEXT CHECK(model_json IS NULL OR (json_valid(model_json) AND json_type(model_json)='object' AND length(model_json)<=2048)),
    manual_retry_node TEXT REFERENCES workflow_node_run(id),
    manual_retry_ordinal INTEGER NOT NULL DEFAULT 0 CHECK(manual_retry_ordinal>=0),
    state TEXT NOT NULL CHECK(state IN ('ACTIVE','PAUSED','WAITING','STALLED','DONE')),
    reason_code TEXT,
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY(requirement_id,plan_revision) REFERENCES workflow_plan_revision(requirement_id,revision),
    FOREIGN KEY(requirement_id,plan_revision,target_key) REFERENCES workflow_plan_node(requirement_id,plan_revision,node_key),
    CHECK((mode='CONTINUOUS' AND target_key IS NULL) OR (mode<>'CONTINUOUS' AND target_key IS NOT NULL)),
    CHECK((manual_retry_node IS NULL AND manual_retry_ordinal=0) OR (manual_retry_node IS NOT NULL AND manual_retry_ordinal>0))
);
CREATE INDEX idx_workflow_control_dispatch ON workflow_run_control(state,requirement_id);
CREATE TRIGGER trg_workflow_control_retry_owner_insert BEFORE INSERT ON workflow_run_control
WHEN NEW.manual_retry_node IS NOT NULL AND NOT EXISTS(SELECT 1 FROM workflow_node_run n WHERE n.id=NEW.manual_retry_node AND n.requirement_id=NEW.requirement_id)
BEGIN SELECT RAISE(ABORT,'workflow retry owner mismatch'); END;
CREATE TRIGGER trg_workflow_control_retry_owner_update BEFORE UPDATE ON workflow_run_control
WHEN NEW.manual_retry_node IS NOT NULL AND NOT EXISTS(SELECT 1 FROM workflow_node_run n WHERE n.id=NEW.manual_retry_node AND n.requirement_id=NEW.requirement_id)
BEGIN SELECT RAISE(ABORT,'workflow retry owner mismatch'); END;
CREATE TRIGGER trg_workflow_control_identity BEFORE UPDATE OF requirement_id,created_at ON workflow_run_control
BEGIN SELECT RAISE(ABORT,'workflow control identity is immutable'); END;
CREATE TRIGGER trg_workflow_control_retained BEFORE DELETE ON workflow_run_control
BEGIN SELECT RAISE(ABORT,'workflow control must be retained'); END;

CREATE TABLE workflow_node_checkpoint (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    requirement_id TEXT NOT NULL REFERENCES workflow_run_control(requirement_id),
    node_key TEXT NOT NULL,
    created_at TEXT NOT NULL,
    acknowledged_at TEXT
);
CREATE INDEX idx_workflow_checkpoint_pending ON workflow_node_checkpoint(requirement_id,acknowledged_at,attempt_id);
CREATE TRIGGER trg_workflow_checkpoint_owner BEFORE INSERT ON workflow_node_checkpoint
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND n.node_key=NEW.node_key
    AND a.state='SUCCEEDED' AND json_extract(n.definition_json,'$.kind')='WORK' AND json_extract(n.definition_json,'$.pauseAfter')=1)
BEGIN SELECT RAISE(ABORT,'workflow checkpoint requires completed work'); END;
CREATE TRIGGER trg_workflow_checkpoint_identity BEFORE UPDATE OF attempt_id,requirement_id,node_key,created_at ON workflow_node_checkpoint
BEGIN SELECT RAISE(ABORT,'workflow checkpoint identity is immutable'); END;
CREATE TRIGGER trg_workflow_checkpoint_ack BEFORE UPDATE OF acknowledged_at ON workflow_node_checkpoint
WHEN OLD.acknowledged_at IS NOT NULL OR NEW.acknowledged_at IS NULL
BEGIN SELECT RAISE(ABORT,'workflow checkpoint acknowledgement is immutable'); END;
CREATE TRIGGER trg_workflow_checkpoint_retained BEFORE DELETE ON workflow_node_checkpoint
BEGIN SELECT RAISE(ABORT,'workflow checkpoint history must be retained'); END;
