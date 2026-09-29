-- Persist exact object identities before external Git/file I/O; publish a manifest only after all bytes verify.
CREATE TABLE workflow_code_snapshot (
    id TEXT PRIMARY KEY,
    attempt_id TEXT NOT NULL UNIQUE REFERENCES workflow_node_attempt(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    repository TEXT NOT NULL,
    root_fingerprint TEXT NOT NULL,
    project_prefix TEXT NOT NULL,
    base_tree TEXT NOT NULL,
    result_tree TEXT NOT NULL,
    inputs_sha256 TEXT NOT NULL CHECK(length(inputs_sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_code_owner BEFORE INSERT ON workflow_code_snapshot
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id JOIN workflow_attempt_stop s ON s.attempt_id=a.id
    JOIN workspace_lease l ON l.holder_workflow_attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id
    AND a.inputs_sha256=NEW.inputs_sha256 AND a.adapter_key='model.write.v1'
    AND l.canonical_root=NEW.repository AND l.root_fingerprint=NEW.root_fingerprint AND l.state<>'RELEASED')
BEGIN SELECT RAISE(ABORT,'workflow code snapshot owner mismatch'); END;
CREATE TRIGGER trg_workflow_code_immutable BEFORE UPDATE ON workflow_code_snapshot
BEGIN SELECT RAISE(ABORT,'workflow code snapshot is immutable'); END;
CREATE TRIGGER trg_workflow_code_retained BEFORE DELETE ON workflow_code_snapshot
BEGIN SELECT RAISE(ABORT,'workflow code snapshot must be retained'); END;

CREATE TABLE workflow_code_manifest (
    snapshot_id TEXT PRIMARY KEY REFERENCES workflow_code_snapshot(id),
    content_json TEXT NOT NULL CHECK(json_valid(content_json)),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_code_manifest_immutable BEFORE UPDATE ON workflow_code_manifest
BEGIN SELECT RAISE(ABORT,'workflow code manifest is immutable'); END;
CREATE TRIGGER trg_workflow_code_manifest_retained BEFORE DELETE ON workflow_code_manifest
BEGIN SELECT RAISE(ABORT,'workflow code manifest must be retained'); END;
