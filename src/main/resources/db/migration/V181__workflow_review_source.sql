-- Fixed-version review sources retain a separate immutable identity and proven command completion.
DROP TRIGGER trg_workflow_command_owner;
CREATE TRIGGER trg_workflow_command_owner BEFORE INSERT ON workflow_command_run
WHEN NEW.state<>'PREPARING' OR NEW.request_json IS NOT NULL OR NEW.registration_json IS NOT NULL OR NEW.result_json IS NOT NULL
    OR NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
        JOIN workflow_requirement r ON r.id=n.requirement_id
        WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id
        AND a.adapter_key='system.verify.command.v1' AND a.role_snapshot_json IS NULL AND a.external_session_id IS NULL
        AND json_extract(n.definition_json,'$.kind')='SYSTEM'
        AND ((json_extract(n.definition_json,'$.moduleId') IN ('system.verify.command','system.repository.snapshot','system.git.history','system.review.snapshot') AND json_extract(n.definition_json,'$.moduleVersion')=1)
            OR (json_extract(n.definition_json,'$.moduleId')='system.source.test-run' AND json_extract(n.definition_json,'$.moduleVersion') IN (1,2))))
BEGIN SELECT RAISE(ABORT,'workflow command owner mismatch'); END;

CREATE TABLE workflow_review_source (
    node_run_id TEXT PRIMARY KEY REFERENCES workflow_node_run(id),
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    branch_id TEXT NOT NULL,
    input_json TEXT NOT NULL CHECK(json_valid(input_json) AND length(CAST(input_json AS BLOB))<=16384),
    input_sha256 TEXT NOT NULL CHECK(length(input_sha256)=64),
    manifest_json TEXT CHECK(manifest_json IS NULL OR json_valid(manifest_json) AND length(CAST(manifest_json AS BLOB))<=33554432),
    manifest_sha256 TEXT CHECK(manifest_sha256 IS NULL OR length(manifest_sha256)=64),
    created_at TEXT NOT NULL,
    CHECK((manifest_json IS NULL)=(manifest_sha256 IS NULL))
);
CREATE TRIGGER trg_workflow_review_source_owner BEFORE INSERT ON workflow_review_source
WHEN NEW.manifest_json IS NOT NULL OR json_extract(NEW.input_json,'$.source.nodeId') IS NOT NEW.node_run_id
    OR json_extract(NEW.input_json,'$.projectId') IS NOT NEW.project_id
    OR COALESCE(json_extract(NEW.input_json,'$.mode'),'') NOT IN ('FULL','DATE_INCREMENTAL')
    OR NOT EXISTS(SELECT 1 FROM workflow_node_run n JOIN workflow_requirement r ON r.id=n.requirement_id
        JOIN workflow_node_attempt a ON a.id=n.latest_attempt_id
        WHERE n.id=NEW.node_run_id AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id
        AND n.state='ACTIVE' AND a.state='RUNNING' AND a.adapter_key='system.verify.command.v1'
        AND a.role_snapshot_json IS NULL AND a.external_session_id IS NULL
        AND json_extract(n.definition_json,'$.kind')='SYSTEM' AND json_extract(n.definition_json,'$.moduleId')='system.review.snapshot'
        AND json_extract(n.definition_json,'$.moduleVersion')=1)
BEGIN SELECT RAISE(ABORT,'workflow review source owner mismatch'); END;
CREATE TRIGGER trg_workflow_review_source_identity BEFORE UPDATE OF node_run_id,requirement_id,project_id,branch_id,input_json,input_sha256,created_at ON workflow_review_source
BEGIN SELECT RAISE(ABORT,'workflow review source identity is immutable'); END;
CREATE TRIGGER trg_workflow_review_source_manifest BEFORE UPDATE OF manifest_json,manifest_sha256 ON workflow_review_source
WHEN OLD.manifest_json IS NOT NULL OR NEW.manifest_json IS NULL
    OR json_extract(NEW.manifest_json,'$.nodeRunId') IS NOT OLD.node_run_id
    OR json_extract(NEW.manifest_json,'$.branchId') IS NOT OLD.branch_id
    OR json_extract(NEW.manifest_json,'$.mode') IS NOT json_extract(OLD.input_json,'$.mode')
    OR json_extract(NEW.manifest_json,'$.type') IS NOT 'REVIEW_SOURCE'
    OR json_extract(NEW.manifest_json,'$.version') IS NOT 1
    OR NOT EXISTS(SELECT 1 FROM workflow_node_run n JOIN workflow_node_attempt a ON a.id=n.latest_attempt_id
        JOIN workflow_command_run c ON c.attempt_id=a.id JOIN workflow_attempt_stop s ON s.attempt_id=a.id
        WHERE n.id=OLD.node_run_id AND n.state='ACTIVE' AND a.state='RUNNING' AND c.state='RUNNING'
        AND json_extract(c.result_json,'$.stopConfirmed')=1 AND json_extract(c.result_json,'$.exitCode')=0
        AND json_extract(c.result_json,'$.launched')=1 AND json_extract(c.result_json,'$.timedOut')=0
        AND json_extract(c.result_json,'$.cancelled')=0 AND json_extract(c.result_json,'$.outputTruncated')=0
        AND json_extract(c.result_json,'$.error')='' AND s.kind='COMMAND_TERMINAL')
BEGIN SELECT RAISE(ABORT,'workflow review source requires stopped successful capture'); END;
CREATE TRIGGER trg_workflow_review_source_retained BEFORE DELETE ON workflow_review_source
BEGIN SELECT RAISE(ABORT,'workflow review source history must be retained'); END;
