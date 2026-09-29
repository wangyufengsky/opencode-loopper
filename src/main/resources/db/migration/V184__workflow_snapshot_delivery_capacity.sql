-- Align the fixed-version module envelope with its validated professional claims; preserve every prior byte and narrower module limit.
DROP TRIGGER trg_workflow_attempt_terminal;
CREATE TABLE workflow_node_delivery_next (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    content_json TEXT NOT NULL CHECK(json_valid(content_json) AND json_type(content_json)='object'
        AND length(CAST(content_json AS BLOB))<=1500000),
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
      AND json_extract(n.definition_json,'$.kind')='WORK' AND json_extract(n.definition_json,'$.moduleVersion')=1
      AND ((json_extract(n.definition_json,'$.moduleId') IN ('snapshot.analyze','snapshot.review')
              AND length(CAST(NEW.content_json AS BLOB))<=1500000)
          OR (json_extract(n.definition_json,'$.moduleId') IN ('source.design','source.design-review','document.direct-review','document.direct-review-check')
              AND length(CAST(NEW.content_json AS BLOB))<=307200)))
BEGIN SELECT RAISE(ABORT,'workflow delivery exceeds module limit'); END;
CREATE TRIGGER trg_workflow_attempt_terminal BEFORE UPDATE OF state ON workflow_node_attempt
WHEN NEW.state IN ('SUCCEEDED','FAILED','CANCELLED') AND (
    NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=NEW.id)
    OR (NEW.state='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM workflow_node_delivery d WHERE d.attempt_id=NEW.id)))
BEGIN SELECT RAISE(ABORT,'workflow attempt requires stop proof and successful delivery'); END;

