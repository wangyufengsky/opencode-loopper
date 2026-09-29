ALTER TABLE workflow_workspace ADD COLUMN object_repository TEXT;
CREATE TRIGGER trg_workflow_workspace_objects BEFORE INSERT ON workflow_workspace
WHEN NEW.object_repository IS NOT NULL AND NOT EXISTS(SELECT 1 FROM workflow_directory_preparation p
    WHERE p.attempt_id=NEW.attempt_id AND p.requirement_id=NEW.requirement_id AND p.project_id=NEW.project_id
    AND p.canonical_root=NEW.canonical_root AND NEW.project_directory=NEW.canonical_root
    AND p.root_fingerprint=NEW.root_fingerprint AND p.object_repository=NEW.object_repository
    AND p.base_tree=NEW.base_tree AND p.source_commit=NEW.source_commit)
BEGIN SELECT RAISE(ABORT,'workflow directory workspace preparation mismatch'); END;
CREATE TRIGGER trg_workflow_workspace_objects_immutable BEFORE UPDATE OF object_repository ON workflow_workspace
BEGIN SELECT RAISE(ABORT,'workflow workspace object repository is immutable'); END;

CREATE TABLE workflow_directory_result (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_directory_preparation(attempt_id),
    manifest_json TEXT NOT NULL CHECK(json_valid(manifest_json) AND length(CAST(manifest_json AS BLOB))<=16777216),
    manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_directory_result_owner BEFORE INSERT ON workflow_directory_result
WHEN NOT EXISTS(SELECT 1 FROM workflow_workspace w JOIN workflow_node_attempt a ON a.id=w.attempt_id
    JOIN workflow_attempt_stop s ON s.attempt_id=a.id JOIN workspace_lease l ON l.holder_workflow_attempt_id=a.id
    JOIN workflow_writer_queue q ON q.attempt_id=a.id JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE w.attempt_id=NEW.attempt_id AND w.object_repository IS NOT NULL AND w.state='CAPTURING'
    AND a.state IN ('RUNNING','STOPPING') AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
    AND l.canonical_root=w.canonical_root AND l.root_fingerprint=w.root_fingerprint AND l.state<>'RELEASED' AND q.state='ADMITTED')
BEGIN SELECT RAISE(ABORT,'workflow directory result requires stopped owner'); END;
CREATE TRIGGER trg_workflow_directory_result_immutable BEFORE UPDATE ON workflow_directory_result
BEGIN SELECT RAISE(ABORT,'workflow directory result is immutable'); END;
CREATE TRIGGER trg_workflow_directory_result_retained BEFORE DELETE ON workflow_directory_result
BEGIN SELECT RAISE(ABORT,'workflow directory result must be retained'); END;

CREATE TABLE workflow_directory_apply (
    id TEXT PRIMARY KEY,
    attempt_id TEXT NOT NULL REFERENCES workflow_directory_preparation(attempt_id),
    phase TEXT NOT NULL CHECK(phase IN ('SEED','RESTORE')),
    before_json TEXT NOT NULL CHECK(json_valid(before_json) AND length(CAST(before_json AS BLOB))<=16777216),
    before_sha256 TEXT NOT NULL CHECK(length(before_sha256)=64),
    after_json TEXT NOT NULL CHECK(json_valid(after_json) AND length(CAST(after_json AS BLOB))<=16777216),
    after_sha256 TEXT NOT NULL CHECK(length(after_sha256)=64),
    created_at TEXT NOT NULL,
    UNIQUE(attempt_id,phase)
);
CREATE TRIGGER trg_workflow_directory_apply_owner BEFORE INSERT ON workflow_directory_apply
WHEN NOT EXISTS(SELECT 1 FROM workflow_workspace w JOIN workflow_node_attempt a ON a.id=w.attempt_id
    JOIN workspace_lease l ON l.holder_workflow_attempt_id=a.id JOIN workflow_writer_queue q ON q.attempt_id=a.id
    JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE w.attempt_id=NEW.attempt_id AND w.object_repository IS NOT NULL
    AND n.latest_attempt_id=a.id AND n.state='ACTIVE' AND q.state='ADMITTED'
    AND l.canonical_root=w.canonical_root AND l.root_fingerprint=w.root_fingerprint
    AND ((NEW.phase='SEED' AND w.state='PREPARING' AND a.state='PREPARING' AND a.external_session_id IS NULL
        AND l.state='HELD' AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
        OR (NEW.phase='RESTORE' AND w.state='RESTORING' AND l.state<>'RELEASED'
            AND EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))))
BEGIN SELECT RAISE(ABORT,'workflow directory apply owner mismatch'); END;
CREATE TRIGGER trg_workflow_directory_apply_immutable BEFORE UPDATE ON workflow_directory_apply
BEGIN SELECT RAISE(ABORT,'workflow directory apply is immutable'); END;
CREATE TRIGGER trg_workflow_directory_apply_retained BEFORE DELETE ON workflow_directory_apply
BEGIN SELECT RAISE(ABORT,'workflow directory apply must be retained'); END;

ALTER TABLE workflow_code_snapshot ADD COLUMN object_repository TEXT;
CREATE TRIGGER trg_workflow_code_directory BEFORE INSERT ON workflow_code_snapshot
WHEN (NEW.object_repository IS NULL AND EXISTS(SELECT 1 FROM workflow_workspace w WHERE w.attempt_id=NEW.attempt_id AND w.object_repository IS NOT NULL))
    OR (NEW.object_repository IS NOT NULL AND NOT EXISTS(SELECT 1 FROM workflow_workspace w
    WHERE w.attempt_id=NEW.attempt_id AND w.project_id=NEW.project_id AND w.requirement_id=NEW.requirement_id
    AND w.object_repository=NEW.object_repository AND w.canonical_root=NEW.repository AND w.root_fingerprint=NEW.root_fingerprint
    AND NEW.project_prefix='' AND w.base_tree=NEW.base_tree AND w.checkpoint_tree=NEW.result_tree
    AND w.state IN ('FROZEN','RESTORING','RESTORED')))
BEGIN SELECT RAISE(ABORT,'workflow directory code requires frozen workspace'); END;
