-- Independent document/code evidence belongs to one exact workflow attempt, not a template model run.
CREATE TABLE workflow_document_read (
    attempt_id TEXT NOT NULL REFERENCES workflow_node_attempt(id),
    input_name TEXT NOT NULL CHECK(input_name IN ('documents','code')),
    path TEXT NOT NULL CHECK(length(path) BETWEEN 1 AND 2048),
    content_sha TEXT NOT NULL CHECK(length(content_sha) IN (40,64)),
    start_line INTEGER NOT NULL CHECK(start_line>=1),
    end_line INTEGER NOT NULL CHECK(end_line>=start_line-1 AND end_line-start_line<200),
    total_lines INTEGER NOT NULL CHECK(total_lines>=end_line),
    content TEXT NOT NULL CHECK(length(content)<=48000),
    created_at TEXT NOT NULL,
    PRIMARY KEY(attempt_id,input_name,path,content_sha,start_line,end_line)
);
CREATE TABLE workflow_document_input_read (
    attempt_id TEXT NOT NULL REFERENCES workflow_node_attempt(id),
    input_name TEXT NOT NULL CHECK(length(input_name) BETWEEN 1 AND 64),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    start_offset INTEGER NOT NULL CHECK(start_offset>=0),
    end_offset INTEGER NOT NULL CHECK(end_offset>=start_offset),
    total_length INTEGER NOT NULL CHECK(total_length>=end_offset),
    created_at TEXT NOT NULL,
    PRIMARY KEY(attempt_id,input_name,sha256,start_offset,end_offset)
);
CREATE TRIGGER trg_workflow_document_read_owner BEFORE INSERT ON workflow_document_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN json_each(a.inputs_json,'$.values') i
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND n.latest_attempt_id=a.id AND n.state='ACTIVE'
      AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0 AND a.adapter_key='model.readonly.v1'
      AND json_extract(n.definition_json,'$.moduleVersion')=1
      AND json_extract(n.definition_json,'$.moduleId') IN ('document.direct-review','document.direct-review-check')
      AND json_extract(i.value,'$.name')=NEW.input_name AND json_extract(i.value,'$.kind')='DOCUMENT'
      AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow document read owner is not active'); END;
CREATE TRIGGER trg_workflow_document_input_read_owner BEFORE INSERT ON workflow_document_input_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN json_each(a.inputs_json,'$.values') i
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND n.latest_attempt_id=a.id AND n.state='ACTIVE'
      AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0 AND a.adapter_key='model.readonly.v1'
      AND json_extract(n.definition_json,'$.moduleVersion')=1
      AND json_extract(n.definition_json,'$.moduleId')='document.direct-review-check'
      AND json_extract(i.value,'$.name')=NEW.input_name AND json_extract(i.value,'$.kind')='JSON'
      AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow document draft read owner is not active'); END;
CREATE TRIGGER trg_workflow_document_read_immutable BEFORE UPDATE ON workflow_document_read
BEGIN SELECT RAISE(ABORT,'workflow document read is immutable'); END;
CREATE TRIGGER trg_workflow_document_read_retained BEFORE DELETE ON workflow_document_read
BEGIN SELECT RAISE(ABORT,'workflow document read must be retained'); END;
CREATE TRIGGER trg_workflow_document_input_read_immutable BEFORE UPDATE ON workflow_document_input_read
BEGIN SELECT RAISE(ABORT,'workflow document input read is immutable'); END;
CREATE TRIGGER trg_workflow_document_input_read_retained BEFORE DELETE ON workflow_document_input_read
BEGIN SELECT RAISE(ABORT,'workflow document input read must be retained'); END;

DROP TRIGGER trg_workflow_delivery_size;
CREATE TRIGGER trg_workflow_delivery_size BEFORE INSERT ON workflow_node_delivery
WHEN length(CAST(NEW.content_json AS BLOB))>131072 AND NOT EXISTS(
    SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND a.adapter_key='model.readonly.v1'
      AND json_extract(n.definition_json,'$.kind')='WORK' AND json_extract(n.definition_json,'$.moduleVersion')=1
      AND json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review','document.direct-review','document.direct-review-check'))
BEGIN SELECT RAISE(ABORT,'workflow delivery exceeds module limit'); END;
