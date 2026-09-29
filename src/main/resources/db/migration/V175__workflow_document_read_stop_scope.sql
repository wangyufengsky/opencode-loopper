-- A user finish intent stops new reading evidence immediately, before individual sessions drain.
CREATE TRIGGER trg_workflow_document_read_requirement BEFORE INSERT ON workflow_document_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id
    WHERE a.id=NEW.attempt_id AND r.state IN ('RUNNING','PAUSED','STALLED')
      AND NOT EXISTS(SELECT 1 FROM workflow_finish_intent f WHERE f.requirement_id=r.id))
BEGIN SELECT RAISE(ABORT,'workflow document requirement is stopping'); END;
CREATE TRIGGER trg_workflow_document_input_read_requirement BEFORE INSERT ON workflow_document_input_read
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id
    WHERE a.id=NEW.attempt_id AND r.state IN ('RUNNING','PAUSED','STALLED')
      AND NOT EXISTS(SELECT 1 FROM workflow_finish_intent f WHERE f.requirement_id=r.id))
BEGIN SELECT RAISE(ABORT,'workflow document requirement is stopping'); END;
