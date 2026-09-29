-- Final document packages are atomic database artifacts, not source snapshots or writer trees.
CREATE TABLE workflow_document (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    manifest_json TEXT NOT NULL CHECK(json_valid(manifest_json) AND length(CAST(manifest_json AS BLOB))<=1048576
        AND json_extract(manifest_json,'$.version')=1 AND json_extract(manifest_json,'$.type')='DESIGN_DOCUMENT'
        AND json_array_length(manifest_json,'$.files') BETWEEN 1 AND 1026),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TABLE workflow_document_file (
    attempt_id TEXT NOT NULL REFERENCES workflow_document(attempt_id),
    path TEXT NOT NULL CHECK(length(path) BETWEEN 1 AND 160 AND path NOT IN ('.','..') AND path NOT GLOB '*[^A-Za-z0-9_.-]*'),
    size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 67108864),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    content TEXT NOT NULL CHECK(length(CAST(content AS BLOB))=size_bytes),
    PRIMARY KEY(attempt_id,path)
);
CREATE TRIGGER trg_workflow_document_owner BEFORE INSERT ON workflow_document
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
    AND r.state IN ('RUNNING','PAUSED','STALLED') AND a.adapter_key='system.source.design-document.v1'
    AND a.role_snapshot_json IS NULL AND a.external_session_id IS NULL
    AND json_extract(n.definition_json,'$.kind')='SYSTEM' AND json_extract(n.definition_json,'$.moduleId')='system.source.design-document'
    AND json_extract(n.definition_json,'$.moduleVersion')=1 AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow document owner mismatch'); END;
CREATE TRIGGER trg_workflow_document_file_owner BEFORE INSERT ON workflow_document_file
WHEN NOT EXISTS(SELECT 1 FROM workflow_document d JOIN workflow_node_attempt a ON a.id=d.attempt_id
    JOIN workflow_node_run n ON n.id=a.node_run_id JOIN workflow_requirement r ON r.id=n.requirement_id
    JOIN json_each(d.manifest_json,'$.files') f
    WHERE d.attempt_id=NEW.attempt_id AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
    AND r.state IN ('RUNNING','PAUSED','STALLED') AND json_extract(f.value,'$.path')=NEW.path
    AND json_extract(f.value,'$.sha256')=NEW.sha256 AND json_extract(f.value,'$.sizeBytes')=NEW.size_bytes
    AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
    OR (SELECT COALESCE(SUM(size_bytes),0) FROM workflow_document_file WHERE attempt_id=NEW.attempt_id)+NEW.size_bytes>67108864
BEGIN SELECT RAISE(ABORT,'workflow document file identity mismatch'); END;
CREATE TRIGGER trg_workflow_document_immutable BEFORE UPDATE ON workflow_document
BEGIN SELECT RAISE(ABORT,'workflow document is immutable'); END;
CREATE TRIGGER trg_workflow_document_no_delete BEFORE DELETE ON workflow_document
BEGIN SELECT RAISE(ABORT,'workflow document history is immutable'); END;
CREATE TRIGGER trg_workflow_document_file_immutable BEFORE UPDATE ON workflow_document_file
BEGIN SELECT RAISE(ABORT,'workflow document file is immutable'); END;
CREATE TRIGGER trg_workflow_document_file_no_delete BEFORE DELETE ON workflow_document_file
BEGIN SELECT RAISE(ABORT,'workflow document file history is immutable'); END;

DROP TRIGGER trg_workflow_stop_owner;
CREATE TRIGGER trg_workflow_stop_owner BEFORE INSERT ON workflow_attempt_stop
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND a.external_session_id IS NEW.external_session_id
    AND (a.adapter_key<>'system.verify.command.v1' OR NEW.kind IN ('COMMAND_TERMINAL','COMMAND_NOT_LAUNCHED'))
    AND (NEW.kind<>'NO_EXTERNAL_WORK' OR a.adapter_key='human.v1'
        OR (a.adapter_key='system.verify.files.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.verify.files'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.snapshot.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.snapshot'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.design-document.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.design-document'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.review.dual.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.review.dual'
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
