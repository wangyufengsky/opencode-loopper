-- A writer's model result precedes the server-owned code capture and final delivery.
CREATE TABLE workflow_writer_candidate (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    attempt_version INTEGER NOT NULL CHECK(attempt_version>=0),
    content_json TEXT NOT NULL CHECK(json_valid(content_json) AND length(CAST(content_json AS BLOB))<=131072),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_writer_candidate_owner BEFORE INSERT ON workflow_writer_candidate
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workflow_writer_queue q ON q.attempt_id=a.id
    JOIN workspace_lease w ON w.canonical_root=q.canonical_root
    WHERE a.id=NEW.attempt_id AND a.version=NEW.attempt_version AND a.adapter_key='model.write.v1'
    AND a.state='RUNNING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
    AND l.state IN ('DISPATCHING','RUNNING') AND l.suspended=0 AND q.state='ADMITTED'
    AND w.holder_workflow_attempt_id=a.id AND w.state='HELD'
    AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'workflow writer candidate owner mismatch'); END;
CREATE TRIGGER trg_workflow_writer_candidate_immutable BEFORE UPDATE ON workflow_writer_candidate
BEGIN SELECT RAISE(ABORT,'workflow writer candidate is immutable'); END;
CREATE TRIGGER trg_workflow_writer_candidate_retained BEFORE DELETE ON workflow_writer_candidate
BEGIN SELECT RAISE(ABORT,'workflow writer candidate history must be retained'); END;
