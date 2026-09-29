-- A test reviewer can cite fixed CODE as well as SOURCE; previous modules keep their original scope.
DROP TRIGGER trg_workflow_source_read_owner;
CREATE TRIGGER trg_workflow_source_read_owner BEFORE INSERT ON workflow_source_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN json_each(a.inputs_json,'$.values') i
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0
        AND json_extract(n.definition_json,'$.moduleVersion')=1 AND json_extract(i.value,'$.name')=NEW.input_name
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
        AND json_extract(n.definition_json,'$.moduleVersion')=1
        AND ((a.adapter_key='model.readonly.v1' AND json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review','source.test-design','source.test-review'))
            OR (a.adapter_key='model.write.v1' AND json_extract(n.definition_json,'$.moduleId')='source.test-write'))
        AND json_extract(i.value,'$.name')=NEW.input_name AND json_extract(i.value,'$.kind')='JSON'
        AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow design read owner is not active'); END;
