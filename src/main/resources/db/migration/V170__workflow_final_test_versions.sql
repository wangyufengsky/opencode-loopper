-- Admit exact V2 final-code tests and per-batch reviews; frozen V1 contracts remain unchanged.
DROP TRIGGER trg_workflow_command_owner;
CREATE TRIGGER trg_workflow_command_owner BEFORE INSERT ON workflow_command_run
WHEN NEW.state<>'PREPARING' OR NEW.request_json IS NOT NULL OR NEW.registration_json IS NOT NULL OR NEW.result_json IS NOT NULL
    OR NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
        JOIN workflow_requirement r ON r.id=n.requirement_id
        WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id
        AND a.adapter_key='system.verify.command.v1' AND a.role_snapshot_json IS NULL AND a.external_session_id IS NULL
        AND json_extract(n.definition_json,'$.kind')='SYSTEM'
        AND ((json_extract(n.definition_json,'$.moduleId')='system.verify.command' AND json_extract(n.definition_json,'$.moduleVersion')=1)
            OR (json_extract(n.definition_json,'$.moduleId')='system.source.test-run' AND json_extract(n.definition_json,'$.moduleVersion') IN (1,2))))
BEGIN SELECT RAISE(ABORT,'workflow command owner mismatch'); END;

DROP TRIGGER trg_workflow_native_test_owner;
CREATE TRIGGER trg_workflow_native_test_owner BEFORE INSERT ON workflow_native_test_evidence
WHEN NOT EXISTS(SELECT 1 FROM workflow_command_run c JOIN workflow_node_attempt a ON a.id=c.attempt_id
    JOIN workflow_node_run n ON n.id=a.node_run_id WHERE c.attempt_id=NEW.attempt_id
    AND c.state IN ('READY','RUNNING','STOPPING') AND c.request_sha256=NEW.request_sha256
    AND c.request_sha256=json_extract(NEW.result_json,'$.requestSha256')
    AND json_extract(c.registration_json,'$.worker') IS json_extract(NEW.result_json,'$.worker')
    AND c.registration_json IS NOT NULL AND json_extract(NEW.result_json,'$.stopConfirmed')=1
    AND a.adapter_key='system.verify.command.v1' AND json_extract(n.definition_json,'$.moduleId')='system.source.test-run'
    AND json_extract(n.definition_json,'$.moduleVersion') IN (1,2))
BEGIN SELECT RAISE(ABORT,'workflow native test evidence owner mismatch'); END;

DROP TRIGGER trg_workflow_source_read_owner;
CREATE TRIGGER trg_workflow_source_read_owner BEFORE INSERT ON workflow_source_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN json_each(a.inputs_json,'$.values') i
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0
        AND (json_extract(n.definition_json,'$.moduleVersion')=1 OR (json_extract(n.definition_json,'$.moduleId')='source.test-review' AND json_extract(n.definition_json,'$.moduleVersion')=2)) AND json_extract(i.value,'$.name')=NEW.input_name
        AND (((a.adapter_key='model.readonly.v1' AND json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review','source.test-design'))
                OR (a.adapter_key='model.write.v1' AND json_extract(n.definition_json,'$.moduleId')='source.test-write')) AND json_extract(i.value,'$.kind')='DOCUMENT'
            OR (a.adapter_key='model.readonly.v1' AND json_extract(n.definition_json,'$.moduleId')='source.test-review'
                AND ((NEW.input_name='source' AND json_extract(i.value,'$.kind')='DOCUMENT') OR (NEW.input_name='code' AND json_extract(i.value,'$.kind')='CODE'))))
        AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow design read owner is not active'); END;

DROP TRIGGER trg_workflow_design_input_read_owner;
CREATE TRIGGER trg_workflow_design_input_read_owner BEFORE INSERT ON workflow_design_input_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN json_each(a.inputs_json,'$.values') i
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0
        AND (json_extract(n.definition_json,'$.moduleVersion')=1 OR (json_extract(n.definition_json,'$.moduleId')='source.test-review' AND json_extract(n.definition_json,'$.moduleVersion')=2))
        AND ((a.adapter_key='model.readonly.v1' AND json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review','source.test-design','source.test-review'))
            OR (a.adapter_key='model.write.v1' AND json_extract(n.definition_json,'$.moduleId')='source.test-write'))
        AND json_extract(i.value,'$.name')=NEW.input_name AND json_extract(i.value,'$.kind')='JSON'
        AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow design read owner is not active'); END;
