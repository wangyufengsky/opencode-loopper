-- Human acceptance is separate from immutable AI verdicts.
CREATE TABLE task_judge_approval (
  cycle_id TEXT PRIMARY KEY REFERENCES task_execution_cycle(id) ON DELETE CASCADE,
  task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
  review_batch_id TEXT REFERENCES judge_review_batch(id) ON DELETE CASCADE,
  task_version INTEGER NOT NULL,
  cycle_version INTEGER NOT NULL,
  approved_at TEXT NOT NULL
);
CREATE INDEX idx_task_judge_approval_task ON task_judge_approval(task_id);
CREATE TRIGGER task_judge_approval_anchor BEFORE INSERT ON task_judge_approval BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM task t JOIN task_execution_cycle c ON c.task_id=t.id
    WHERE t.id=NEW.task_id AND t.state='WAITING_INPUT' AND t.version=NEW.task_version
      AND c.id=NEW.cycle_id AND c.state='RUNNING' AND c.version=NEW.cycle_version
      AND ((NEW.review_batch_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM judge_review_batch b WHERE b.id=NEW.review_batch_id
          AND b.execution_cycle_id=c.id AND b.task_id=t.id AND b.state<>'RUNNING'))
        OR (NEW.review_batch_id IS NULL AND NOT EXISTS (
          SELECT 1 FROM judge_review_batch b WHERE b.task_id=t.id)))
  ) THEN RAISE(ABORT,'Human approval requires current stopped review') END;
END;
CREATE TRIGGER task_judge_approval_immutable BEFORE UPDATE ON task_judge_approval BEGIN
  SELECT RAISE(ABORT,'Human approval is immutable');
END;
