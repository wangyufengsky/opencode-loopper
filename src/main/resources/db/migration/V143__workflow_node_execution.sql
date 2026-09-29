CREATE TABLE workflow_node_run (
    id TEXT PRIMARY KEY,
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    node_key TEXT NOT NULL CHECK(length(node_key) BETWEEN 1 AND 80),
    definition_json TEXT NOT NULL CHECK(json_valid(definition_json) AND json_type(definition_json)='object'
        AND length(CAST(definition_json AS BLOB))<=2097152),
    definition_sha256 TEXT NOT NULL CHECK(length(definition_sha256)=64),
    state TEXT NOT NULL CHECK(state IN ('PENDING','ACTIVE','SUCCEEDED','FAILED','SKIPPED','CANCELLED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    latest_attempt_id TEXT REFERENCES workflow_node_attempt(id),
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX idx_workflow_node_requirement ON workflow_node_run(requirement_id,id);
CREATE TABLE workflow_plan_node (
    requirement_id TEXT NOT NULL,
    plan_revision INTEGER NOT NULL,
    node_key TEXT NOT NULL,
    node_run_id TEXT NOT NULL REFERENCES workflow_node_run(id),
    PRIMARY KEY(requirement_id,plan_revision,node_key),
    UNIQUE(requirement_id,plan_revision,node_run_id),
    FOREIGN KEY(requirement_id,plan_revision) REFERENCES workflow_plan_revision(requirement_id,revision)
);
CREATE TRIGGER trg_workflow_plan_node_owner BEFORE INSERT ON workflow_plan_node
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_run n WHERE n.id=NEW.node_run_id
    AND n.requirement_id=NEW.requirement_id AND n.node_key=NEW.node_key)
BEGIN SELECT RAISE(ABORT,'workflow plan node owner mismatch'); END;
CREATE TRIGGER trg_workflow_plan_node_immutable BEFORE UPDATE ON workflow_plan_node
BEGIN SELECT RAISE(ABORT,'workflow plan node binding is immutable'); END;
CREATE TRIGGER trg_workflow_plan_node_retained BEFORE DELETE ON workflow_plan_node
BEGIN SELECT RAISE(ABORT,'workflow plan node history must be retained'); END;
CREATE TRIGGER trg_workflow_node_identity BEFORE UPDATE OF id,requirement_id,node_key,definition_json,definition_sha256,created_at ON workflow_node_run
WHEN OLD.id IS NOT NEW.id OR OLD.requirement_id IS NOT NEW.requirement_id OR OLD.node_key IS NOT NEW.node_key
    OR OLD.definition_json IS NOT NEW.definition_json OR OLD.definition_sha256 IS NOT NEW.definition_sha256 OR OLD.created_at IS NOT NEW.created_at
BEGIN SELECT RAISE(ABORT,'workflow node definition is immutable'); END;

CREATE TABLE workflow_node_attempt (
    id TEXT PRIMARY KEY,
    node_run_id TEXT NOT NULL REFERENCES workflow_node_run(id),
    ordinal INTEGER NOT NULL CHECK(ordinal>=1),
    plan_revision INTEGER NOT NULL CHECK(plan_revision>=1),
    state TEXT NOT NULL CHECK(state IN ('PREPARING','RUNNING','WAITING_INPUT','STOPPING','SUCCEEDED','FAILED','CANCELLED')),
    inputs_json TEXT NOT NULL CHECK(json_valid(inputs_json) AND json_type(inputs_json)='object'
        AND length(CAST(inputs_json AS BLOB))<=2097152),
    inputs_sha256 TEXT NOT NULL CHECK(length(inputs_sha256)=64),
    role_snapshot_json TEXT CHECK(role_snapshot_json IS NULL OR (json_valid(role_snapshot_json) AND json_type(role_snapshot_json)='object')),
    adapter_key TEXT NOT NULL CHECK(length(adapter_key) BETWEEN 1 AND 100),
    external_session_id TEXT,
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(node_run_id,ordinal)
);
CREATE UNIQUE INDEX idx_workflow_one_live_attempt ON workflow_node_attempt(node_run_id)
    WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED');
CREATE TRIGGER trg_workflow_attempt_plan BEFORE INSERT ON workflow_node_attempt
WHEN NOT EXISTS(SELECT 1 FROM workflow_plan_node p WHERE p.node_run_id=NEW.node_run_id AND p.plan_revision=NEW.plan_revision)
BEGIN SELECT RAISE(ABORT,'workflow attempt is not bound to a plan'); END;
CREATE TRIGGER trg_workflow_attempt_snapshot BEFORE UPDATE OF id,node_run_id,ordinal,plan_revision,inputs_json,inputs_sha256,role_snapshot_json,adapter_key,created_at ON workflow_node_attempt
BEGIN SELECT RAISE(ABORT,'workflow attempt snapshot is immutable'); END;
CREATE TRIGGER trg_workflow_attempt_session BEFORE UPDATE OF external_session_id ON workflow_node_attempt
WHEN OLD.external_session_id IS NOT NULL AND OLD.external_session_id IS NOT NEW.external_session_id
BEGIN SELECT RAISE(ABORT,'workflow external session identity is immutable'); END;
CREATE TRIGGER trg_workflow_node_latest_attempt BEFORE UPDATE OF latest_attempt_id ON workflow_node_run
WHEN NEW.latest_attempt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM workflow_node_attempt a
    WHERE a.id=NEW.latest_attempt_id AND a.node_run_id=NEW.id AND a.ordinal=NEW.attempt_count)
BEGIN SELECT RAISE(ABORT,'workflow latest attempt mismatch'); END;

CREATE TABLE workflow_node_delivery (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    content_json TEXT NOT NULL CHECK(json_valid(content_json) AND json_type(content_json)='object'
        AND length(CAST(content_json AS BLOB))<=131072),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    outcome TEXT,
    created_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_delivery_immutable BEFORE UPDATE ON workflow_node_delivery
BEGIN SELECT RAISE(ABORT,'workflow delivery is immutable'); END;
CREATE TRIGGER trg_workflow_delivery_retained BEFORE DELETE ON workflow_node_delivery
BEGIN SELECT RAISE(ABORT,'workflow delivery history must be retained'); END;
CREATE TABLE workflow_attempt_stop (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    kind TEXT NOT NULL CHECK(kind IN ('NO_EXTERNAL_WORK','SESSION_TERMINAL','ABORT_CONFIRMED','SESSION_ABSENT')),
    external_session_id TEXT,
    evidence_json TEXT NOT NULL CHECK(json_valid(evidence_json) AND length(CAST(evidence_json AS BLOB))<=16384),
    created_at TEXT NOT NULL,
    CHECK((kind='NO_EXTERNAL_WORK' AND external_session_id IS NULL) OR (kind<>'NO_EXTERNAL_WORK' AND external_session_id IS NOT NULL))
);
CREATE TRIGGER trg_workflow_stop_owner BEFORE INSERT ON workflow_attempt_stop
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a WHERE a.id=NEW.attempt_id
    AND a.external_session_id IS NEW.external_session_id
    AND (NEW.kind<>'NO_EXTERNAL_WORK' OR a.adapter_key='human.v1'))
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
CREATE TRIGGER trg_workflow_node_terminal BEFORE UPDATE OF state ON workflow_node_run
WHEN NEW.state IN ('SUCCEEDED','FAILED') AND NOT EXISTS(SELECT 1 FROM workflow_node_attempt a
    WHERE a.id=NEW.latest_attempt_id AND a.node_run_id=NEW.id AND a.state=NEW.state)
BEGIN SELECT RAISE(ABORT,'workflow node requires matching terminal attempt'); END;

CREATE TABLE workflow_input_snapshot (
    requirement_id TEXT NOT NULL,
    plan_revision INTEGER NOT NULL,
    content_json TEXT NOT NULL CHECK(json_valid(content_json) AND json_type(content_json)='object'
        AND length(CAST(content_json AS BLOB))<=2097152),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    created_at TEXT NOT NULL,
    PRIMARY KEY(requirement_id,plan_revision),
    FOREIGN KEY(requirement_id,plan_revision) REFERENCES workflow_plan_revision(requirement_id,revision)
);
CREATE TRIGGER trg_workflow_inputs_immutable BEFORE UPDATE ON workflow_input_snapshot
BEGIN SELECT RAISE(ABORT,'workflow requirement inputs are immutable'); END;
CREATE TRIGGER trg_workflow_inputs_retained BEFORE DELETE ON workflow_input_snapshot
BEGIN SELECT RAISE(ABORT,'workflow input history must be retained'); END;
