-- Separate process supervision state from node, model Session and requirement state.
CREATE TABLE workflow_command_run (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    state TEXT NOT NULL CHECK(state IN ('PREPARING','READY','RUNNING','STOPPING','SUCCEEDED','FAILED','CANCELLED')),
    request_json TEXT CHECK(request_json IS NULL OR json_valid(request_json)),
    request_sha256 TEXT CHECK(request_sha256 IS NULL OR length(request_sha256)=64),
    registration_json TEXT CHECK(registration_json IS NULL OR json_valid(registration_json)),
    result_json TEXT CHECK(result_json IS NULL OR json_valid(result_json)),
    result_sha256 TEXT CHECK(result_sha256 IS NULL OR length(result_sha256)=64),
    suspended INTEGER NOT NULL DEFAULT 0 CHECK(suspended IN (0,1)),
    last_error_code TEXT,
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK((request_json IS NULL)=(request_sha256 IS NULL)),
    CHECK((result_json IS NULL)=(result_sha256 IS NULL)),
    CHECK(registration_json IS NULL OR request_json IS NOT NULL),
    CHECK(result_json IS NULL OR registration_json IS NOT NULL),
    CHECK(state<>'READY' OR request_json IS NOT NULL),
    CHECK(state<>'RUNNING' OR registration_json IS NOT NULL)
);
CREATE TRIGGER trg_workflow_command_owner BEFORE INSERT ON workflow_command_run
WHEN NEW.state<>'PREPARING' OR NEW.request_json IS NOT NULL OR NEW.registration_json IS NOT NULL OR NEW.result_json IS NOT NULL
    OR NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
        JOIN workflow_requirement r ON r.id=n.requirement_id
        WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id
        AND a.adapter_key='system.verify.command.v1' AND a.role_snapshot_json IS NULL AND a.external_session_id IS NULL
        AND json_extract(n.definition_json,'$.kind')='SYSTEM' AND json_extract(n.definition_json,'$.moduleId')='system.verify.command'
        AND json_extract(n.definition_json,'$.moduleVersion')=1)
BEGIN SELECT RAISE(ABORT,'workflow command owner mismatch'); END;
CREATE TRIGGER trg_workflow_command_identity BEFORE UPDATE OF attempt_id,requirement_id,project_id,created_at ON workflow_command_run
BEGIN SELECT RAISE(ABORT,'workflow command identity is immutable'); END;
CREATE TRIGGER trg_workflow_command_request BEFORE UPDATE OF request_json,request_sha256 ON workflow_command_run
WHEN OLD.request_json IS NOT NULL OR OLD.state<>'PREPARING' OR NEW.request_json IS NULL
    OR json_extract(NEW.request_json,'$.id') IS NOT NEW.attempt_id
BEGIN SELECT RAISE(ABORT,'workflow command request is frozen'); END;
CREATE TRIGGER trg_workflow_command_registration BEFORE UPDATE OF registration_json ON workflow_command_run
WHEN OLD.registration_json IS NOT NULL OR OLD.state NOT IN ('READY','STOPPING') OR NEW.registration_json IS NULL
    OR json_extract(NEW.registration_json,'$.requestSha256') IS NOT NEW.request_sha256
BEGIN SELECT RAISE(ABORT,'workflow command registration differs'); END;
CREATE TRIGGER trg_workflow_command_result BEFORE UPDATE OF result_json,result_sha256 ON workflow_command_run
WHEN OLD.result_json IS NOT NULL OR OLD.state NOT IN ('READY','RUNNING','STOPPING') OR NEW.result_json IS NULL
    OR json_extract(NEW.result_json,'$.requestSha256') IS NOT NEW.request_sha256
    OR json_extract(NEW.result_json,'$.worker') IS NOT json_extract(NEW.registration_json,'$.worker')
    OR json_extract(NEW.result_json,'$.stopConfirmed') IS NOT 1
BEGIN SELECT RAISE(ABORT,'workflow command result differs'); END;
CREATE TRIGGER trg_workflow_command_terminal BEFORE UPDATE OF state ON workflow_command_run
WHEN NEW.state IN ('SUCCEEDED','FAILED','CANCELLED') AND NOT EXISTS(SELECT 1 FROM workflow_node_attempt a
    WHERE a.id=NEW.attempt_id AND a.state=NEW.state)
BEGIN SELECT RAISE(ABORT,'workflow command requires matching terminal attempt'); END;
CREATE TRIGGER trg_workflow_command_retained BEFORE DELETE ON workflow_command_run
BEGIN SELECT RAISE(ABORT,'workflow command history must be retained'); END;
CREATE INDEX idx_workflow_command_active ON workflow_command_run(suspended,state,attempt_id);
CREATE INDEX idx_workflow_command_run_owner ON workflow_command_run(requirement_id,state);

-- Preserve all previous proof bytes, including model creation guards, while admitting process proofs.
CREATE TABLE workflow_attempt_stop_v3 (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    kind TEXT NOT NULL CHECK(kind IN ('NO_EXTERNAL_WORK','NO_SESSION_CREATED','SESSION_TERMINAL','ABORT_CONFIRMED','SESSION_ABSENT','CREATION_STOP_CONFIRMED','COMMAND_TERMINAL','COMMAND_NOT_LAUNCHED')),
    external_session_id TEXT,
    evidence_json TEXT NOT NULL CHECK(json_valid(evidence_json) AND length(CAST(evidence_json AS BLOB))<=16384),
    created_at TEXT NOT NULL,
    CHECK((kind IN ('NO_EXTERNAL_WORK','NO_SESSION_CREATED','COMMAND_TERMINAL','COMMAND_NOT_LAUNCHED') AND external_session_id IS NULL)
        OR kind IN ('SESSION_ABSENT','CREATION_STOP_CONFIRMED') OR (kind IN ('SESSION_TERMINAL','ABORT_CONFIRMED') AND external_session_id IS NOT NULL))
);
INSERT INTO workflow_attempt_stop_v3 SELECT * FROM workflow_attempt_stop;
DROP TRIGGER trg_workflow_attempt_terminal;
DROP TRIGGER trg_workflow_code_owner;
DROP TRIGGER trg_workflow_writer_candidate_owner;
DROP TABLE workflow_attempt_stop;
ALTER TABLE workflow_attempt_stop_v3 RENAME TO workflow_attempt_stop;
CREATE TRIGGER trg_workflow_stop_owner BEFORE INSERT ON workflow_attempt_stop
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND a.external_session_id IS NEW.external_session_id
    AND (a.adapter_key<>'system.verify.command.v1' OR NEW.kind IN ('COMMAND_TERMINAL','COMMAND_NOT_LAUNCHED'))
    AND (NEW.kind<>'NO_EXTERNAL_WORK' OR a.adapter_key='human.v1'
        OR (a.adapter_key='system.verify.files.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.verify.files'
            AND json_extract(n.definition_json,'$.moduleVersion')=1))
    AND (NEW.kind<>'NO_SESSION_CREATED' OR EXISTS(SELECT 1 FROM workflow_model_launch l
        WHERE l.attempt_id=a.id AND l.creation_plan_json IS NULL AND l.state='STOPPING'))
    AND (NEW.kind NOT IN ('SESSION_ABSENT','CREATION_STOP_CONFIRMED') OR EXISTS(SELECT 1 FROM workflow_model_launch l
        WHERE l.attempt_id=a.id AND l.creation_plan_json IS NOT NULL AND l.state='STOPPING'))
    AND (NEW.kind NOT IN ('COMMAND_TERMINAL','COMMAND_NOT_LAUNCHED') OR EXISTS(SELECT 1 FROM workflow_command_run c
        WHERE c.attempt_id=a.id AND a.adapter_key='system.verify.command.v1' AND a.role_snapshot_json IS NULL
        AND ((NEW.kind='COMMAND_TERMINAL' AND c.result_json IS NOT NULL
            AND json_extract(c.result_json,'$.stopConfirmed')=1
            AND json_extract(NEW.evidence_json,'$.requestSha256')=c.request_sha256
            AND json_extract(NEW.evidence_json,'$.resultSha256')=c.result_sha256)
        OR (NEW.kind='COMMAND_NOT_LAUNCHED' AND c.state='STOPPING' AND c.request_json IS NULL AND c.registration_json IS NULL)))))
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

CREATE TRIGGER trg_workflow_code_owner BEFORE INSERT ON workflow_code_snapshot
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id JOIN workflow_attempt_stop s ON s.attempt_id=a.id
    JOIN workspace_lease l ON l.holder_workflow_attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id
    AND a.inputs_sha256=NEW.inputs_sha256 AND a.adapter_key='model.write.v1'
    AND l.canonical_root=NEW.repository AND l.root_fingerprint=NEW.root_fingerprint AND l.state<>'RELEASED')
BEGIN SELECT RAISE(ABORT,'workflow code snapshot owner mismatch'); END;

CREATE TRIGGER trg_workflow_writer_candidate_owner BEFORE INSERT ON workflow_writer_candidate
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workflow_writer_queue q ON q.attempt_id=a.id
    JOIN workspace_lease w ON w.canonical_root=q.canonical_root
    WHERE a.id=NEW.attempt_id AND a.version=NEW.attempt_version AND a.adapter_key='model.write.v1'
    AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
    AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0 AND q.state='ADMITTED'
    AND w.holder_workflow_attempt_id=a.id AND w.state='HELD'
    AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow writer candidate owner mismatch'); END;
