-- Each professional model attempt fixes its exact analysis units before creating a remote Session.
CREATE TABLE workflow_history_analysis_input (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    source_attempt_id TEXT NOT NULL REFERENCES workflow_node_attempt(id),
    source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64),
    input_json TEXT NOT NULL CHECK(json_valid(input_json) AND length(CAST(input_json AS BLOB))<=2097152),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TABLE workflow_history_analysis_read (
    attempt_id TEXT NOT NULL REFERENCES workflow_history_analysis_input(attempt_id),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    start_offset INTEGER NOT NULL CHECK(start_offset>=0),
    end_offset INTEGER NOT NULL CHECK(end_offset>=start_offset AND end_offset-start_offset<=24000),
    total_length INTEGER NOT NULL CHECK(total_length>=end_offset AND total_length<=2097152),
    created_at TEXT NOT NULL,
    PRIMARY KEY(attempt_id,sha256,start_offset,end_offset)
);
CREATE TRIGGER trg_workflow_history_analysis_owner BEFORE INSERT ON workflow_history_analysis_input
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workflow_requirement r ON r.id=n.requirement_id
    JOIN json_each(a.inputs_json,'$.values') i JOIN workflow_node_attempt p ON p.id=NEW.source_attempt_id
    JOIN workflow_node_run pn ON pn.id=p.node_run_id JOIN workflow_history_snapshot h ON h.node_run_id=pn.id
    WHERE a.id=NEW.attempt_id AND a.state='PREPARING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
      AND a.adapter_key='model.readonly.v1' AND l.state='PREPARING' AND l.suspended=0
      AND r.state IN ('RUNNING','PAUSED','STALLED') AND NOT EXISTS(SELECT 1 FROM workflow_finish_intent f WHERE f.requirement_id=r.id)
      AND json_extract(n.definition_json,'$.moduleVersion')=1
      AND json_extract(n.definition_json,'$.moduleId') IN ('history.review','history.contribution')
      AND json_extract(NEW.input_json,'$.version')=1 AND json_extract(NEW.input_json,'$.module')=json_extract(n.definition_json,'$.moduleId')
      AND json_extract(NEW.input_json,'$.sourceAttempt')=p.id AND json_extract(NEW.input_json,'$.source.sha256')=NEW.source_sha256
      AND json_extract(i.value,'$.name')='source' AND json_extract(i.value,'$.attemptId')=p.id
      AND json_extract(i.value,'$.content.sha256')=NEW.source_sha256 AND h.manifest_sha256=NEW.source_sha256
      AND pn.requirement_id=n.requirement_id AND p.state='SUCCEEDED'
      AND EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=p.id)
      AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow history analysis input owner mismatch'); END;
CREATE TRIGGER trg_workflow_history_analysis_read_owner BEFORE INSERT ON workflow_history_analysis_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workflow_requirement r ON r.id=n.requirement_id
    JOIN workflow_history_analysis_input i ON i.attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
      AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0 AND i.sha256=NEW.sha256
      AND r.state IN ('RUNNING','PAUSED','STALLED') AND NOT EXISTS(SELECT 1 FROM workflow_finish_intent f WHERE f.requirement_id=r.id)
      AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow history analysis reading is not active'); END;
CREATE TRIGGER trg_workflow_history_analysis_input_immutable BEFORE UPDATE ON workflow_history_analysis_input
BEGIN SELECT RAISE(ABORT,'workflow history analysis input is immutable'); END;
CREATE TRIGGER trg_workflow_history_analysis_input_retained BEFORE DELETE ON workflow_history_analysis_input
BEGIN SELECT RAISE(ABORT,'workflow history analysis input must be retained'); END;
CREATE TRIGGER trg_workflow_history_analysis_read_immutable BEFORE UPDATE ON workflow_history_analysis_read
BEGIN SELECT RAISE(ABORT,'workflow history analysis read is immutable'); END;
CREATE TRIGGER trg_workflow_history_analysis_read_retained BEFORE DELETE ON workflow_history_analysis_read
BEGIN SELECT RAISE(ABORT,'workflow history analysis read must be retained'); END;
