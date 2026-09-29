-- Only the exact frozen test-profile adapter gets the additional no-external-work proof.
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
        OR (a.adapter_key='system.source.test-profile.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.test-profile'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.design-plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.design-plan'
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
