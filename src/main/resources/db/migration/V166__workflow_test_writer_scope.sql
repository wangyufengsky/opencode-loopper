CREATE TABLE workflow_test_baseline (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    inputs_sha256 TEXT NOT NULL CHECK(length(inputs_sha256)=64),
    files_json TEXT NOT NULL CHECK(json_valid(files_json) AND json_type(files_json)='object' AND length(CAST(files_json AS BLOB))<=8388608),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_test_baseline_owner BEFORE INSERT ON workflow_test_baseline
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workflow_workspace w ON w.attempt_id=a.id
    JOIN workspace_lease q ON q.holder_workflow_attempt_id=a.id AND q.canonical_root=w.canonical_root
    WHERE a.id=NEW.attempt_id AND a.adapter_key='model.write.v1' AND a.state='PREPARING' AND a.inputs_sha256=NEW.inputs_sha256
        AND n.latest_attempt_id=a.id AND json_extract(n.definition_json,'$.moduleId')='source.test-write'
        AND json_extract(n.definition_json,'$.moduleVersion')=1 AND json_extract(n.definition_json,'$.kind')='WORK'
        AND l.state='PREPARING' AND l.creation_plan_json IS NULL AND w.state='READY' AND q.state='HELD')
BEGIN SELECT RAISE(ABORT,'test baseline requires preparing writer and held workspace'); END;
CREATE TRIGGER trg_workflow_test_baseline_immutable BEFORE UPDATE ON workflow_test_baseline
BEGIN SELECT RAISE(ABORT,'test baseline is immutable'); END;
CREATE TRIGGER trg_workflow_test_baseline_retained BEFORE DELETE ON workflow_test_baseline
BEGIN SELECT RAISE(ABORT,'test baseline must be retained'); END;
CREATE TABLE workflow_test_scope (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_test_baseline(attempt_id),
    checkpoint_tree TEXT NOT NULL CHECK(length(checkpoint_tree) IN (40,64)),
    files_json TEXT NOT NULL CHECK(json_valid(files_json) AND json_type(files_json)='object' AND length(CAST(files_json AS BLOB))<=8388608),
    files_sha256 TEXT NOT NULL CHECK(length(files_sha256)=64),
    report_json TEXT NOT NULL CHECK(json_valid(report_json) AND json_type(report_json)='object'),
    report_sha256 TEXT NOT NULL CHECK(length(report_sha256)=64),
    passed INTEGER NOT NULL CHECK(passed IN (0,1)),
    created_at TEXT NOT NULL,
    CHECK(json_extract(report_json,'$.version')=1 AND json_extract(report_json,'$.type')='SOURCE_TEST_SCOPE'
        AND json_extract(report_json,'$.passed')=passed AND json_extract(report_json,'$.testsExecuted')=0)
);
CREATE TRIGGER trg_workflow_test_scope_owner BEFORE INSERT ON workflow_test_scope
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_workspace w ON w.attempt_id=a.id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workspace_lease q ON q.holder_workflow_attempt_id=a.id AND q.canonical_root=w.canonical_root
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND l.state='RUNNING' AND w.state='FROZEN'
        AND w.checkpoint_tree=NEW.checkpoint_tree AND q.state IN ('HELD','UNCONFIRMED')
        AND EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'test scope requires stopped writer and fixed checkpoint'); END;
CREATE TRIGGER trg_workflow_test_scope_immutable BEFORE UPDATE ON workflow_test_scope
BEGIN SELECT RAISE(ABORT,'test scope proof is immutable'); END;
CREATE TRIGGER trg_workflow_test_scope_retained BEFORE DELETE ON workflow_test_scope
BEGIN SELECT RAISE(ABORT,'test scope proof must be retained'); END;
CREATE TRIGGER trg_workflow_test_writer_success BEFORE UPDATE OF state ON workflow_node_attempt
WHEN NEW.state='SUCCEEDED' AND EXISTS(SELECT 1 FROM workflow_node_run n WHERE n.id=NEW.node_run_id AND json_extract(n.definition_json,'$.moduleId')='source.test-write')
    AND NOT EXISTS(SELECT 1 FROM workflow_test_scope s JOIN workflow_workspace w ON w.attempt_id=s.attempt_id WHERE s.attempt_id=NEW.id AND s.passed=1 AND s.checkpoint_tree=w.checkpoint_tree)
BEGIN SELECT RAISE(ABORT,'test writer success requires matching passing scope proof'); END;

DROP TRIGGER trg_workflow_source_read_owner;
CREATE TRIGGER trg_workflow_source_read_owner BEFORE INSERT ON workflow_source_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN json_each(a.inputs_json,'$.values') i
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0
        AND json_extract(n.definition_json,'$.moduleVersion')=1
        AND ((a.adapter_key='model.readonly.v1' AND json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review','source.test-design'))
            OR (a.adapter_key='model.write.v1' AND json_extract(n.definition_json,'$.moduleId')='source.test-write'))
        AND json_extract(i.value,'$.name')=NEW.input_name AND json_extract(i.value,'$.kind')='DOCUMENT'
        AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow design read owner is not active'); END;

DROP TRIGGER trg_workflow_design_input_read_owner;
CREATE TRIGGER trg_workflow_design_input_read_owner BEFORE INSERT ON workflow_design_input_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN json_each(a.inputs_json,'$.values') i
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0
        AND json_extract(n.definition_json,'$.moduleVersion')=1
        AND ((a.adapter_key='model.readonly.v1' AND json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review','source.test-design'))
            OR (a.adapter_key='model.write.v1' AND json_extract(n.definition_json,'$.moduleId')='source.test-write'))
        AND json_extract(i.value,'$.name')=NEW.input_name AND json_extract(i.value,'$.kind')='JSON'
        AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow design read owner is not active'); END;
