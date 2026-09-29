-- Preparation is committed before private object I/O. No project file mutation is authorized by this record.
CREATE TABLE workflow_directory_preparation (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    canonical_root TEXT NOT NULL,
    root_fingerprint TEXT NOT NULL,
    object_repository TEXT NOT NULL UNIQUE,
    inputs_sha256 TEXT NOT NULL,
    manifest_json TEXT,
    manifest_sha256 TEXT,
    base_tree TEXT,
    source_commit TEXT,
    created_at TEXT NOT NULL,
    CHECK((manifest_json IS NULL)=(manifest_sha256 IS NULL)),
    CHECK(manifest_json IS NULL OR (json_valid(manifest_json) AND length(CAST(manifest_json AS BLOB))<=16777216 AND length(manifest_sha256)=64)),
    CHECK((base_tree IS NULL)=(source_commit IS NULL)),
    CHECK(base_tree IS NULL OR (manifest_json IS NOT NULL AND length(base_tree)=40 AND length(source_commit)=40))
);
CREATE TRIGGER trg_workflow_directory_owner BEFORE INSERT ON workflow_directory_preparation
WHEN NEW.manifest_json IS NOT NULL OR NEW.base_tree IS NOT NULL OR NOT EXISTS(
    SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id JOIN workspace_lease l ON l.holder_workflow_attempt_id=a.id
    JOIN workflow_writer_queue q ON q.attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND a.adapter_key='model.write.v1' AND a.state='PREPARING' AND a.external_session_id IS NULL
    AND a.inputs_sha256=NEW.inputs_sha256 AND n.latest_attempt_id=a.id AND n.state='ACTIVE'
    AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id AND r.state IN ('RUNNING','PAUSED','STALLED')
    AND l.canonical_root=NEW.canonical_root AND l.root_fingerprint=NEW.root_fingerprint AND l.state='HELD'
    AND q.state='ADMITTED' AND q.canonical_root=l.canonical_root AND q.root_fingerprint=l.root_fingerprint
    AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow directory preparation owner mismatch'); END;
CREATE TRIGGER trg_workflow_directory_identity BEFORE UPDATE OF attempt_id,requirement_id,project_id,
    canonical_root,root_fingerprint,object_repository,inputs_sha256,created_at ON workflow_directory_preparation
BEGIN SELECT RAISE(ABORT,'workflow directory preparation identity is immutable'); END;
CREATE TRIGGER trg_workflow_directory_manifest BEFORE UPDATE OF manifest_json,manifest_sha256 ON workflow_directory_preparation
WHEN OLD.manifest_json IS NOT NULL OR NEW.manifest_json IS NULL
BEGIN SELECT RAISE(ABORT,'workflow directory inventory is immutable'); END;
CREATE TRIGGER trg_workflow_directory_ready BEFORE UPDATE OF base_tree,source_commit ON workflow_directory_preparation
WHEN OLD.base_tree IS NOT NULL OR NEW.base_tree IS NULL OR OLD.manifest_json IS NULL
BEGIN SELECT RAISE(ABORT,'workflow directory baseline is immutable'); END;
CREATE TRIGGER trg_workflow_directory_active BEFORE UPDATE ON workflow_directory_preparation
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id JOIN workspace_lease l ON l.holder_workflow_attempt_id=a.id
    JOIN workflow_writer_queue q ON q.attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND a.adapter_key='model.write.v1' AND a.state='PREPARING' AND a.external_session_id IS NULL
    AND n.latest_attempt_id=a.id AND n.state='ACTIVE' AND r.state IN ('RUNNING','PAUSED','STALLED')
    AND l.canonical_root=NEW.canonical_root AND l.root_fingerprint=NEW.root_fingerprint AND l.state='HELD'
    AND q.state='ADMITTED' AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow directory preparation is no longer active'); END;
CREATE TRIGGER trg_workflow_directory_retained BEFORE DELETE ON workflow_directory_preparation
BEGIN SELECT RAISE(ABORT,'workflow directory preparation history must be retained'); END;
