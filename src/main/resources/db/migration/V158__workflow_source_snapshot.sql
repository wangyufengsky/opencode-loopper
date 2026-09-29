-- Each node freezes one source identity across attempts, independently of Git writer snapshots.
CREATE TABLE workflow_source_snapshot (
    node_run_id TEXT PRIMARY KEY REFERENCES workflow_node_run(id),
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    root_path TEXT NOT NULL,
    source_path TEXT NOT NULL CHECK(length(source_path) BETWEEN 1 AND 2048),
    purpose TEXT NOT NULL CHECK(purpose IN ('DESIGN','UNIT_TEST')),
    manifest_json TEXT,
    manifest_sha256 TEXT,
    created_at TEXT NOT NULL,
    ready_at TEXT,
    CHECK((manifest_json IS NULL AND manifest_sha256 IS NULL AND ready_at IS NULL)
        OR (manifest_json IS NOT NULL AND manifest_sha256 IS NOT NULL AND json_valid(manifest_json) AND length(manifest_sha256)=64))
);
CREATE TRIGGER trg_workflow_source_owner BEFORE INSERT ON workflow_source_snapshot
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_run n JOIN workflow_requirement r ON r.id=n.requirement_id
    WHERE n.id=NEW.node_run_id AND r.id=NEW.requirement_id AND r.project_id=NEW.project_id AND r.state='RUNNING'
    AND json_extract(n.definition_json,'$.kind')='SYSTEM'
    AND json_extract(n.definition_json,'$.moduleId')='system.source.snapshot'
    AND json_extract(n.definition_json,'$.moduleVersion')=1)
BEGIN SELECT RAISE(ABORT,'workflow source owner mismatch'); END;
CREATE TRIGGER trg_workflow_source_immutable BEFORE UPDATE ON workflow_source_snapshot
WHEN NEW.node_run_id<>OLD.node_run_id OR NEW.requirement_id<>OLD.requirement_id OR NEW.project_id<>OLD.project_id
    OR NEW.root_path<>OLD.root_path OR NEW.source_path<>OLD.source_path OR NEW.purpose<>OLD.purpose OR NEW.created_at<>OLD.created_at
    OR (OLD.manifest_json IS NOT NULL AND (NEW.manifest_json IS NOT OLD.manifest_json OR NEW.manifest_sha256 IS NOT OLD.manifest_sha256))
    OR (OLD.ready_at IS NOT NULL AND NEW.ready_at IS NOT OLD.ready_at)
    OR NOT EXISTS(SELECT 1 FROM workflow_node_run n JOIN workflow_requirement r ON r.id=n.requirement_id
        WHERE n.id=NEW.node_run_id AND n.state='ACTIVE' AND r.state IN ('RUNNING','PAUSED','STALLED'))
BEGIN SELECT RAISE(ABORT,'workflow source identity is immutable'); END;
CREATE TRIGGER trg_workflow_source_no_delete BEFORE DELETE ON workflow_source_snapshot
BEGIN SELECT RAISE(ABORT,'workflow source history is immutable'); END;

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
