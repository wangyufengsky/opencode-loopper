-- Workflow attempts and legacy Tasks share the same canonical workspace lease.
ALTER TABLE workspace_lease ADD COLUMN holder_workflow_attempt_id TEXT REFERENCES workflow_node_attempt(id);
CREATE UNIQUE INDEX idx_workspace_lease_workflow_holder ON workspace_lease(holder_workflow_attempt_id)
    WHERE holder_workflow_attempt_id IS NOT NULL AND state<>'RELEASED';
CREATE TRIGGER trg_workspace_single_owner_insert BEFORE INSERT ON workspace_lease
WHEN NEW.holder_workflow_attempt_id IS NOT NULL AND (NEW.holder_task_id IS NOT NULL OR NEW.writer_session_id IS NOT NULL OR NEW.state='RELEASED')
BEGIN SELECT RAISE(ABORT,'workspace lease has incompatible owners'); END;
CREATE TRIGGER trg_workspace_single_owner_update BEFORE UPDATE ON workspace_lease
WHEN NEW.holder_workflow_attempt_id IS NOT NULL AND (NEW.holder_task_id IS NOT NULL OR NEW.writer_session_id IS NOT NULL OR NEW.state='RELEASED')
BEGIN SELECT RAISE(ABORT,'workspace lease has incompatible owners'); END;

CREATE TABLE workflow_writer_queue (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    canonical_root TEXT NOT NULL,
    root_fingerprint TEXT NOT NULL,
    position INTEGER NOT NULL CHECK(position>0),
    state TEXT NOT NULL CHECK(state IN ('QUEUED','ADMITTED','CANCELLED','FINISHED')),
    enqueued_at TEXT NOT NULL,
    admitted_at TEXT,
    finished_at TEXT,
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    UNIQUE(canonical_root,position)
);
CREATE INDEX idx_workflow_writer_waiting ON workflow_writer_queue(canonical_root,state,position);
CREATE TRIGGER trg_workflow_writer_owner BEFORE INSERT ON workflow_writer_queue
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id WHERE a.id=NEW.attempt_id
    AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id
    AND n.latest_attempt_id=a.id AND n.state='ACTIVE' AND a.adapter_key='model.write.v1')
BEGIN SELECT RAISE(ABORT,'workflow writer owner mismatch'); END;
CREATE TRIGGER trg_workflow_writer_identity BEFORE UPDATE OF attempt_id,requirement_id,project_id,canonical_root,root_fingerprint,position,enqueued_at ON workflow_writer_queue
BEGIN SELECT RAISE(ABORT,'workflow writer queue identity is immutable'); END;
CREATE TRIGGER trg_workflow_writer_retained BEFORE DELETE ON workflow_writer_queue
BEGIN SELECT RAISE(ABORT,'workflow writer queue history must be retained'); END;
