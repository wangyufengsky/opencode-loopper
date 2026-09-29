-- Program nodes use the same explicit pause-after confirmation as role-backed work.
-- Historical checkpoints and acknowledgements remain unchanged.
DROP TRIGGER trg_workflow_checkpoint_owner;
CREATE TRIGGER trg_workflow_checkpoint_owner BEFORE INSERT ON workflow_node_checkpoint
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND n.node_key=NEW.node_key
    AND a.state='SUCCEEDED' AND json_extract(n.definition_json,'$.kind') IN ('WORK','SYSTEM')
    AND json_extract(n.definition_json,'$.pauseAfter')=1)
BEGIN SELECT RAISE(ABORT,'workflow checkpoint requires completed work'); END;
