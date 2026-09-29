-- A stopped writer may still hold a release-pending lease until restoration commits.
DROP TRIGGER trg_workflow_test_scope_owner;
CREATE TRIGGER trg_workflow_test_scope_owner BEFORE INSERT ON workflow_test_scope
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_workspace w ON w.attempt_id=a.id
    JOIN workflow_model_launch l ON l.attempt_id=a.id JOIN workspace_lease q ON q.holder_workflow_attempt_id=a.id AND q.canonical_root=w.canonical_root
    WHERE a.id=NEW.attempt_id AND a.state='RUNNING' AND l.state='RUNNING' AND w.state='FROZEN'
        AND w.checkpoint_tree=NEW.checkpoint_tree AND q.state IN ('HELD','RELEASE_PENDING')
        AND EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))
BEGIN SELECT RAISE(ABORT,'test scope requires stopped writer and fixed checkpoint'); END;
