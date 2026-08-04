-- A LoopSpec draft owns at most one isolated execution task.  This lets all
-- repeated create_task calls converge on the same task instead of cloning worktrees.
CREATE UNIQUE INDEX idx_task_loop_draft_unique ON task(loop_draft_id) WHERE loop_draft_id IS NOT NULL;
