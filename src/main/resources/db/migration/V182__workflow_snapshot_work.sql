-- Each professional model attempt fixes its exact analysis units before creating a remote Session.
CREATE TABLE workflow_snapshot_work_input (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    source_attempt_id TEXT NOT NULL REFERENCES workflow_node_attempt(id),
    source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64),
    input_json TEXT NOT NULL CHECK(json_valid(input_json) AND length(CAST(input_json AS BLOB))<=2097152),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TABLE workflow_snapshot_work_page (
    attempt_id TEXT NOT NULL REFERENCES workflow_snapshot_work_input(attempt_id),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    start_offset INTEGER NOT NULL CHECK(start_offset>=0),
    end_offset INTEGER NOT NULL CHECK(end_offset>=start_offset AND end_offset-start_offset<=24000),
    total_length INTEGER NOT NULL CHECK(total_length>=end_offset AND total_length<=2097152),
    created_at TEXT NOT NULL,
    PRIMARY KEY(attempt_id,sha256,start_offset,end_offset)
);
CREATE TRIGGER trg_workflow_snapshot_work_owner BEFORE INSERT ON workflow_snapshot_work_input
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workflow_requirement r ON r.id=n.requirement_id
    JOIN json_each(a.inputs_json,'$.values') i JOIN workflow_node_attempt p ON p.id=NEW.source_attempt_id
    JOIN workflow_node_run pn ON pn.id=p.node_run_id JOIN workflow_review_source h ON h.node_run_id=pn.id
    WHERE a.id=NEW.attempt_id AND a.state='PREPARING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
      AND a.adapter_key='model.readonly.v1' AND l.state='PREPARING' AND l.suspended=0
      AND r.state IN ('RUNNING','PAUSED','STALLED') AND NOT EXISTS(SELECT 1 FROM workflow_finish_intent f WHERE f.requirement_id=r.id)
      AND json_extract(n.definition_json,'$.moduleVersion')=1
      AND json_extract(n.definition_json,'$.moduleId') IN ('snapshot.analyze','snapshot.review')
      AND json_extract(NEW.input_json,'$.version')=1 AND json_extract(NEW.input_json,'$.module')=json_extract(n.definition_json,'$.moduleId')
      AND json_extract(NEW.input_json,'$.sourceAttempt')=p.id AND json_extract(NEW.input_json,'$.source.sha256')=NEW.source_sha256
      AND json_extract(i.value,'$.name')='source' AND json_extract(i.value,'$.attemptId')=p.id
      AND json_extract(i.value,'$.content.sha256')=NEW.source_sha256 AND h.manifest_sha256=NEW.source_sha256
      AND pn.requirement_id=n.requirement_id AND p.state='SUCCEEDED'
      AND EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=p.id)
      AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow snapshot work input owner mismatch'); END;
CREATE TRIGGER trg_workflow_snapshot_work_page_owner BEFORE INSERT ON workflow_snapshot_work_page
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workflow_requirement r ON r.id=n.requirement_id
    JOIN workflow_snapshot_work_input i ON i.attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
      AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0 AND i.sha256=NEW.sha256
      AND r.state IN ('RUNNING','PAUSED','STALLED') AND NOT EXISTS(SELECT 1 FROM workflow_finish_intent f WHERE f.requirement_id=r.id)
      AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow snapshot work reading is not active'); END;
CREATE TRIGGER trg_workflow_snapshot_work_input_immutable BEFORE UPDATE ON workflow_snapshot_work_input
BEGIN SELECT RAISE(ABORT,'workflow snapshot work input is immutable'); END;
CREATE TRIGGER trg_workflow_snapshot_work_input_retained BEFORE DELETE ON workflow_snapshot_work_input
BEGIN SELECT RAISE(ABORT,'workflow snapshot work input must be retained'); END;
CREATE TRIGGER trg_workflow_snapshot_work_page_immutable BEFORE UPDATE ON workflow_snapshot_work_page
BEGIN SELECT RAISE(ABORT,'workflow snapshot work read is immutable'); END;
CREATE TRIGGER trg_workflow_snapshot_work_page_retained BEFORE DELETE ON workflow_snapshot_work_page
BEGIN SELECT RAISE(ABORT,'workflow snapshot work read must be retained'); END;

-- V3 permits twelve distinct related file-list/search/read requests per attempt; replay does not spend another slot.
CREATE TABLE workflow_snapshot_work_context (
    attempt_id TEXT NOT NULL REFERENCES workflow_snapshot_work_input(attempt_id),
    request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
    created_at TEXT NOT NULL,
    PRIMARY KEY(attempt_id,request_sha256)
);
CREATE TABLE workflow_snapshot_work_receipt (
    attempt_id TEXT NOT NULL REFERENCES workflow_snapshot_work_input(attempt_id),
    version TEXT NOT NULL CHECK(length(version) IN (40,64)),
    path TEXT NOT NULL CHECK(length(path) BETWEEN 1 AND 2048),
    blob TEXT NOT NULL CHECK(length(blob) IN (40,64)),
    start_line INTEGER NOT NULL CHECK(start_line>=1),
    end_line INTEGER NOT NULL CHECK(end_line>=start_line AND end_line-start_line<200),
    content TEXT NOT NULL CHECK(length(content)<=32000),
    created_at TEXT NOT NULL,
    PRIMARY KEY(attempt_id,version,path,blob,start_line,end_line)
);
CREATE TRIGGER trg_workflow_snapshot_work_context_owner BEFORE INSERT ON workflow_snapshot_work_context
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workflow_requirement r ON r.id=n.requirement_id
    JOIN workflow_snapshot_work_input i ON i.attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
      AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0
      AND r.state IN ('RUNNING','PAUSED','STALLED') AND NOT EXISTS(SELECT 1 FROM workflow_finish_intent f WHERE f.requirement_id=r.id)
      AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow snapshot work reading is not active'); END;
CREATE TRIGGER trg_workflow_snapshot_work_context_immutable BEFORE UPDATE ON workflow_snapshot_work_context
BEGIN SELECT RAISE(ABORT,'workflow snapshot work context is immutable'); END;
CREATE TRIGGER trg_workflow_snapshot_work_context_retained BEFORE DELETE ON workflow_snapshot_work_context
BEGIN SELECT RAISE(ABORT,'workflow snapshot work context must be retained'); END;
CREATE TRIGGER trg_workflow_snapshot_work_receipt_owner BEFORE INSERT ON workflow_snapshot_work_receipt
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workflow_requirement r ON r.id=n.requirement_id
    JOIN workflow_snapshot_work_input i ON i.attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
      AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0
      AND r.state IN ('RUNNING','PAUSED','STALLED') AND NOT EXISTS(SELECT 1 FROM workflow_finish_intent f WHERE f.requirement_id=r.id)
      AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow snapshot work reading is not active'); END;
CREATE TRIGGER trg_workflow_snapshot_work_receipt_immutable BEFORE UPDATE ON workflow_snapshot_work_receipt
BEGIN SELECT RAISE(ABORT,'workflow snapshot work receipt is immutable'); END;
CREATE TRIGGER trg_workflow_snapshot_work_receipt_retained BEFORE DELETE ON workflow_snapshot_work_receipt
BEGIN SELECT RAISE(ABORT,'workflow snapshot work receipt must be retained'); END;
CREATE TRIGGER trg_workflow_snapshot_work_context_limit BEFORE INSERT ON workflow_snapshot_work_context
WHEN NOT EXISTS(SELECT 1 FROM workflow_snapshot_work_context WHERE attempt_id=NEW.attempt_id AND request_sha256=NEW.request_sha256)
 AND (SELECT count(*) FROM workflow_snapshot_work_context WHERE attempt_id=NEW.attempt_id)>=12
BEGIN SELECT RAISE(ABORT,'workflow snapshot work related context limit'); END;
CREATE TRIGGER trg_workflow_snapshot_work_receipt_version BEFORE INSERT ON workflow_snapshot_work_receipt
WHEN NOT EXISTS(SELECT 1 FROM workflow_snapshot_work_input i WHERE i.attempt_id=NEW.attempt_id
 AND (json_extract(i.input_json,'$.targetSha')=NEW.version OR json_extract(i.input_json,'$.baselineSha')=NEW.version))
BEGIN SELECT RAISE(ABORT,'workflow snapshot work code version mismatch'); END;
