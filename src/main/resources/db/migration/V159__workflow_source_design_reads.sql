-- Preserve accepted delivery bytes while retaining the legacy limit for ordinary nodes.
DROP TRIGGER trg_workflow_attempt_terminal;
CREATE TABLE workflow_node_delivery_next (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    content_json TEXT NOT NULL CHECK(json_valid(content_json) AND json_type(content_json)='object'
        AND length(CAST(content_json AS BLOB))<=307200),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    outcome TEXT,
    created_at TEXT NOT NULL
);
INSERT INTO workflow_node_delivery_next SELECT * FROM workflow_node_delivery;
DROP TABLE workflow_node_delivery;
ALTER TABLE workflow_node_delivery_next RENAME TO workflow_node_delivery;
CREATE TRIGGER trg_workflow_delivery_immutable BEFORE UPDATE ON workflow_node_delivery
BEGIN SELECT RAISE(ABORT,'workflow delivery is immutable'); END;
CREATE TRIGGER trg_workflow_delivery_retained BEFORE DELETE ON workflow_node_delivery
BEGIN SELECT RAISE(ABORT,'workflow delivery history must be retained'); END;
CREATE TRIGGER trg_workflow_delivery_size BEFORE INSERT ON workflow_node_delivery
WHEN length(CAST(NEW.content_json AS BLOB))>131072 AND NOT EXISTS(
    SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND a.adapter_key='model.readonly.v1'
        AND json_extract(n.definition_json,'$.kind')='WORK'
        AND json_extract(n.definition_json,'$.moduleVersion')=1
        AND json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review'))
BEGIN SELECT RAISE(ABORT,'workflow delivery exceeds module limit'); END;
CREATE TRIGGER trg_workflow_attempt_terminal BEFORE UPDATE OF state ON workflow_node_attempt
WHEN NEW.state IN ('SUCCEEDED','FAILED','CANCELLED') AND (
    NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=NEW.id)
    OR (NEW.state='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM workflow_node_delivery d WHERE d.attempt_id=NEW.id)))
BEGIN SELECT RAISE(ABORT,'workflow attempt requires stop proof and successful delivery'); END;

CREATE TABLE workflow_source_read (
    attempt_id TEXT NOT NULL REFERENCES workflow_node_attempt(id),
    input_name TEXT NOT NULL CHECK(length(input_name) BETWEEN 1 AND 64),
    path TEXT NOT NULL,
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    start_line INTEGER NOT NULL CHECK(start_line>=1),
    end_line INTEGER NOT NULL CHECK(end_line>=start_line-1),
    total_lines INTEGER NOT NULL CHECK(total_lines>=end_line),
    content TEXT NOT NULL CHECK(length(content)<=48000),
    created_at TEXT NOT NULL,
    PRIMARY KEY(attempt_id,input_name,path,sha256,start_line,end_line),
    CHECK(end_line-start_line<200)
);
CREATE TABLE workflow_design_input_read (
    attempt_id TEXT NOT NULL REFERENCES workflow_node_attempt(id),
    input_name TEXT NOT NULL CHECK(length(input_name) BETWEEN 1 AND 64),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    start_offset INTEGER NOT NULL CHECK(start_offset>=0),
    end_offset INTEGER NOT NULL CHECK(end_offset>=start_offset),
    total_length INTEGER NOT NULL CHECK(total_length>=end_offset),
    created_at TEXT NOT NULL,
    PRIMARY KEY(attempt_id,input_name,sha256,start_offset,end_offset)
);
CREATE TRIGGER trg_workflow_source_read_owner BEFORE INSERT ON workflow_source_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN json_each(a.inputs_json,'$.values') i
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0
        AND a.adapter_key='model.readonly.v1' AND json_extract(n.definition_json,'$.moduleVersion')=1
        AND json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review')
        AND json_extract(i.value,'$.name')=NEW.input_name AND json_extract(i.value,'$.kind')='DOCUMENT'
        AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow design read owner is not active'); END;
CREATE TRIGGER trg_workflow_source_read_immutable BEFORE UPDATE ON workflow_source_read
BEGIN SELECT RAISE(ABORT,'workflow design read evidence is immutable'); END;
CREATE TRIGGER trg_workflow_source_read_retained BEFORE DELETE ON workflow_source_read
BEGIN SELECT RAISE(ABORT,'workflow design read evidence must be retained'); END;
CREATE TRIGGER trg_workflow_design_input_read_owner BEFORE INSERT ON workflow_design_input_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN json_each(a.inputs_json,'$.values') i
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0
        AND a.adapter_key='model.readonly.v1' AND json_extract(n.definition_json,'$.moduleVersion')=1
        AND json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review')
        AND json_extract(i.value,'$.name')=NEW.input_name AND json_extract(i.value,'$.kind')='JSON'
        AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow design read owner is not active'); END;
CREATE TRIGGER trg_workflow_design_input_read_immutable BEFORE UPDATE ON workflow_design_input_read
BEGIN SELECT RAISE(ABORT,'workflow design read evidence is immutable'); END;
CREATE TRIGGER trg_workflow_design_input_read_retained BEFORE DELETE ON workflow_design_input_read
BEGIN SELECT RAISE(ABORT,'workflow design read evidence must be retained'); END;
