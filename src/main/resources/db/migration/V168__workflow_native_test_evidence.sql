-- Reuse the command supervisor; specialized native tests retain independent immutable report bytes.
DROP TRIGGER trg_workflow_command_owner;
CREATE TRIGGER trg_workflow_command_owner BEFORE INSERT ON workflow_command_run
WHEN NEW.state<>'PREPARING' OR NEW.request_json IS NOT NULL OR NEW.registration_json IS NOT NULL OR NEW.result_json IS NOT NULL
    OR NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
        JOIN workflow_requirement r ON r.id=n.requirement_id
        WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id
        AND a.adapter_key='system.verify.command.v1' AND a.role_snapshot_json IS NULL AND a.external_session_id IS NULL
        AND json_extract(n.definition_json,'$.kind')='SYSTEM'
        AND json_extract(n.definition_json,'$.moduleId') IN ('system.verify.command','system.source.test-run')
        AND json_extract(n.definition_json,'$.moduleVersion')=1)
BEGIN SELECT RAISE(ABORT,'workflow command owner mismatch'); END;

CREATE TABLE workflow_native_test_evidence (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_command_run(attempt_id),
    request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
    result_json TEXT NOT NULL CHECK(json_valid(result_json)),
    result_sha256 TEXT NOT NULL CHECK(length(result_sha256)=64),
    report_json TEXT NOT NULL CHECK(json_valid(report_json)),
    report_sha256 TEXT NOT NULL CHECK(length(report_sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_native_test_owner BEFORE INSERT ON workflow_native_test_evidence
WHEN NOT EXISTS(SELECT 1 FROM workflow_command_run c JOIN workflow_node_attempt a ON a.id=c.attempt_id
    JOIN workflow_node_run n ON n.id=a.node_run_id WHERE c.attempt_id=NEW.attempt_id
    AND c.state IN ('READY','RUNNING','STOPPING') AND c.request_sha256=NEW.request_sha256
    AND c.request_sha256=json_extract(NEW.result_json,'$.requestSha256')
    AND json_extract(c.registration_json,'$.worker') IS json_extract(NEW.result_json,'$.worker')
    AND c.registration_json IS NOT NULL AND json_extract(NEW.result_json,'$.stopConfirmed')=1
    AND a.adapter_key='system.verify.command.v1' AND json_extract(n.definition_json,'$.moduleId')='system.source.test-run'
    AND json_extract(n.definition_json,'$.moduleVersion')=1)
BEGIN SELECT RAISE(ABORT,'workflow native test evidence owner mismatch'); END;
CREATE TRIGGER trg_workflow_native_test_immutable BEFORE UPDATE ON workflow_native_test_evidence
BEGIN SELECT RAISE(ABORT,'workflow native test evidence is immutable'); END;
CREATE TRIGGER trg_workflow_native_test_retained BEFORE DELETE ON workflow_native_test_evidence
BEGIN SELECT RAISE(ABORT,'workflow native test evidence must be retained'); END;
CREATE TRIGGER trg_workflow_native_test_success BEFORE UPDATE OF state ON workflow_node_attempt
WHEN NEW.state='SUCCEEDED' AND EXISTS(SELECT 1 FROM workflow_node_run n WHERE n.id=NEW.node_run_id
    AND json_extract(n.definition_json,'$.moduleId')='system.source.test-run')
    AND NOT EXISTS(SELECT 1 FROM workflow_native_test_evidence e JOIN workflow_command_run c ON c.attempt_id=e.attempt_id
        WHERE e.attempt_id=NEW.id AND c.result_sha256=e.result_sha256 AND c.request_sha256=e.request_sha256
        AND json_extract(e.report_json,'$.valid')=1)
BEGIN SELECT RAISE(ABORT,'workflow native test success requires fixed native evidence'); END;
