CREATE TABLE workflow_model_launch (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    state TEXT NOT NULL CHECK(state IN ('PREPARING','CREATING','DISPATCHING','RUNNING','STOPPING','SUCCEEDED','FAILED','CANCELLED')),
    directory TEXT NOT NULL,
    model_json TEXT NOT NULL CHECK(json_valid(model_json)),
    creation_plan_json TEXT CHECK(creation_plan_json IS NULL OR json_valid(creation_plan_json)),
    prompt_json TEXT CHECK(prompt_json IS NULL OR json_valid(prompt_json)),
    prompt_sha256 TEXT CHECK(prompt_sha256 IS NULL OR length(prompt_sha256)=64),
    suspended INTEGER NOT NULL DEFAULT 0 CHECK(suspended IN (0,1)),
    last_error_code TEXT,
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK((creation_plan_json IS NULL AND prompt_json IS NULL AND prompt_sha256 IS NULL)
        OR (creation_plan_json IS NOT NULL AND prompt_json IS NOT NULL AND prompt_sha256 IS NOT NULL))
);
CREATE TRIGGER trg_workflow_launch_owner BEFORE INSERT ON workflow_model_launch
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND a.adapter_key='model.readonly.v1')
BEGIN SELECT RAISE(ABORT,'workflow model owner mismatch'); END;
CREATE TRIGGER trg_workflow_launch_identity BEFORE UPDATE OF attempt_id,requirement_id,directory,model_json,created_at ON workflow_model_launch
BEGIN SELECT RAISE(ABORT,'workflow model identity is immutable'); END;
CREATE TRIGGER trg_workflow_launch_prompt BEFORE UPDATE OF creation_plan_json,prompt_json,prompt_sha256 ON workflow_model_launch
WHEN OLD.creation_plan_json IS NOT NULL OR OLD.state<>'PREPARING'
BEGIN SELECT RAISE(ABORT,'workflow model launch is already frozen'); END;
CREATE TRIGGER trg_workflow_launch_retained BEFORE DELETE ON workflow_model_launch
BEGIN SELECT RAISE(ABORT,'workflow model history must be retained'); END;
CREATE INDEX idx_workflow_launch_active ON workflow_model_launch(suspended,state,attempt_id);

-- Add proofs for cancellation before remote creation and exact absence after a lost create response.
-- Preserve all V143 proof bytes and identities; only the admissible new proof shapes change.
CREATE TABLE workflow_attempt_stop_v2 (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    kind TEXT NOT NULL CHECK(kind IN ('NO_EXTERNAL_WORK','NO_SESSION_CREATED','SESSION_TERMINAL','ABORT_CONFIRMED','SESSION_ABSENT','CREATION_STOP_CONFIRMED')),
    external_session_id TEXT,
    evidence_json TEXT NOT NULL CHECK(json_valid(evidence_json) AND length(CAST(evidence_json AS BLOB))<=16384),
    created_at TEXT NOT NULL,
    CHECK((kind IN ('NO_EXTERNAL_WORK','NO_SESSION_CREATED') AND external_session_id IS NULL)
        OR kind IN ('SESSION_ABSENT','CREATION_STOP_CONFIRMED') OR (kind IN ('SESSION_TERMINAL','ABORT_CONFIRMED') AND external_session_id IS NOT NULL))
);
INSERT INTO workflow_attempt_stop_v2 SELECT * FROM workflow_attempt_stop;
DROP TRIGGER trg_workflow_attempt_terminal;
DROP TABLE workflow_attempt_stop;
ALTER TABLE workflow_attempt_stop_v2 RENAME TO workflow_attempt_stop;
CREATE TRIGGER trg_workflow_stop_owner BEFORE INSERT ON workflow_attempt_stop
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a WHERE a.id=NEW.attempt_id
    AND a.external_session_id IS NEW.external_session_id
    AND (NEW.kind<>'NO_EXTERNAL_WORK' OR a.adapter_key='human.v1')
    AND (NEW.kind<>'NO_SESSION_CREATED' OR EXISTS(SELECT 1 FROM workflow_model_launch l
        WHERE l.attempt_id=a.id AND l.creation_plan_json IS NULL AND l.state='STOPPING'))
    AND (NEW.kind NOT IN ('SESSION_ABSENT','CREATION_STOP_CONFIRMED') OR EXISTS(SELECT 1 FROM workflow_model_launch l
        WHERE l.attempt_id=a.id AND l.creation_plan_json IS NOT NULL AND l.state='STOPPING')))
BEGIN SELECT RAISE(ABORT,'workflow stop identity mismatch'); END;
CREATE TRIGGER trg_workflow_stop_immutable BEFORE UPDATE ON workflow_attempt_stop
BEGIN SELECT RAISE(ABORT,'workflow stop proof is immutable'); END;
CREATE TRIGGER trg_workflow_stop_retained BEFORE DELETE ON workflow_attempt_stop
BEGIN SELECT RAISE(ABORT,'workflow stop proof must be retained'); END;
CREATE TRIGGER trg_workflow_attempt_terminal BEFORE UPDATE OF state ON workflow_node_attempt
WHEN NEW.state IN ('SUCCEEDED','FAILED','CANCELLED') AND (
    NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=NEW.id)
    OR (NEW.state='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM workflow_node_delivery d WHERE d.attempt_id=NEW.id)))
BEGIN SELECT RAISE(ABORT,'workflow attempt requires stop proof and successful delivery'); END;
CREATE TRIGGER trg_workflow_model_terminal BEFORE UPDATE OF state ON workflow_model_launch
WHEN NEW.state IN ('SUCCEEDED','FAILED','CANCELLED') AND NOT EXISTS(
    SELECT 1 FROM workflow_node_attempt a WHERE a.id=NEW.attempt_id AND a.state=NEW.state)
BEGIN SELECT RAISE(ABORT,'workflow model requires matching terminal attempt'); END;
