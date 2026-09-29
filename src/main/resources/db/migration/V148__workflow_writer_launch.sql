-- Reuse the model-session ledger for queued writers; no existing launch or frozen permissions change.
DROP TRIGGER trg_workflow_launch_owner;
CREATE TRIGGER trg_workflow_launch_owner BEFORE INSERT ON workflow_model_launch
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id
    AND (a.adapter_key='model.readonly.v1' OR (
        a.adapter_key='model.write.v1' AND a.state='PREPARING' AND n.state='ACTIVE' AND n.latest_attempt_id=a.id
        AND EXISTS(SELECT 1 FROM workflow_writer_queue q WHERE q.attempt_id=a.id
            AND q.requirement_id=n.requirement_id AND q.state IN ('QUEUED','ADMITTED')))))
BEGIN SELECT RAISE(ABORT,'workflow model owner mismatch'); END;
