-- A confirmed directory publication is an independent writer, never a reopened model attempt.
CREATE TABLE workflow_writeback (
    id TEXT PRIMARY KEY,
    requirement_id TEXT NOT NULL UNIQUE REFERENCES workflow_requirement(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    request_key TEXT NOT NULL UNIQUE,
    request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
    intent_json TEXT NOT NULL CHECK(json_valid(intent_json)),
    intent_sha256 TEXT NOT NULL CHECK(length(intent_sha256)=64),
    state TEXT NOT NULL CHECK(state IN ('CONFIRMED','APPLYING','BLOCKED','APPLIED')),
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    prepared_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_writeback_owner BEFORE INSERT ON workflow_writeback
WHEN NEW.state<>'CONFIRMED' OR NEW.version<>0 OR NEW.prepared_at IS NOT NULL OR NOT EXISTS(
    SELECT 1 FROM workflow_requirement r JOIN workflow_node_run n ON n.requirement_id=r.id
    JOIN workflow_node_attempt a ON a.id=n.latest_attempt_id
    JOIN workflow_plan_node p ON p.requirement_id=r.id AND p.plan_revision=r.head_revision AND p.node_run_id=n.id
    JOIN workflow_workspace w ON w.attempt_id=a.id
    JOIN workflow_attempt_stop stop ON stop.attempt_id=a.id
    JOIN workflow_node_delivery d ON d.attempt_id=a.id
    JOIN json_each(d.content_json,'$.outputs') output
    JOIN workflow_code_snapshot c ON c.attempt_id=a.id
    JOIN workflow_code_manifest m ON m.snapshot_id=c.id
    WHERE r.id=NEW.requirement_id AND r.project_id=NEW.project_id AND r.state='COMPLETED'
      AND a.id=json_extract(NEW.intent_json,'$.selection.attempt') AND a.state IN ('SUCCEEDED','FAILED')
      AND n.node_key=json_extract(NEW.intent_json,'$.selection.node')
      AND output.key=json_extract(NEW.intent_json,'$.selection.output')
      AND json_extract(output.value,'$.kind')='CODE'
      AND json_extract(output.value,'$.content.snapshotId')=c.id
      AND json_extract(output.value,'$.content.sha256')=m.sha256
      AND w.state='RELEASED' AND w.object_repository IS NOT NULL AND w.object_repository=c.object_repository
      AND w.object_repository=json_extract(NEW.intent_json,'$.objectRepository')
      AND r.version=json_extract(NEW.intent_json,'$.preview.requirementVersion')
      AND r.head_revision=json_extract(NEW.intent_json,'$.selection.revision')
      AND r.id=json_extract(NEW.intent_json,'$.preview.requirementId')
      AND w.canonical_root=json_extract(NEW.intent_json,'$.before.canonicalRoot')
      AND w.root_fingerprint=json_extract(NEW.intent_json,'$.before.rootFingerprint'))
BEGIN SELECT RAISE(ABORT,'workflow writeback owner mismatch'); END;
CREATE TRIGGER trg_workflow_writeback_identity BEFORE UPDATE OF id,requirement_id,project_id,request_key,request_sha256,intent_json,intent_sha256,created_at ON workflow_writeback
BEGIN SELECT RAISE(ABORT,'workflow writeback intent is immutable'); END;
CREATE TRIGGER trg_workflow_writeback_retained BEFORE DELETE ON workflow_writeback
BEGIN SELECT RAISE(ABORT,'workflow writeback history must be retained'); END;
CREATE TRIGGER trg_workflow_writeback_prepared BEFORE UPDATE OF prepared_at ON workflow_writeback
WHEN OLD.prepared_at IS NOT NULL AND NEW.prepared_at IS NOT OLD.prepared_at
BEGIN SELECT RAISE(ABORT,'workflow writeback preparation is immutable'); END;

ALTER TABLE workspace_lease ADD COLUMN holder_writeback_id TEXT REFERENCES workflow_writeback(id);
CREATE UNIQUE INDEX idx_workspace_lease_writeback_holder ON workspace_lease(holder_writeback_id)
    WHERE holder_writeback_id IS NOT NULL AND state<>'RELEASED';
CREATE TRIGGER trg_workspace_writeback_owner_insert BEFORE INSERT ON workspace_lease
WHEN NEW.holder_writeback_id IS NOT NULL AND (NEW.holder_task_id IS NOT NULL OR NEW.holder_workflow_attempt_id IS NOT NULL OR NEW.writer_session_id IS NOT NULL OR NEW.state='RELEASED')
BEGIN SELECT RAISE(ABORT,'workspace lease has incompatible owners'); END;
CREATE TRIGGER trg_workspace_writeback_owner_update BEFORE UPDATE ON workspace_lease
WHEN NEW.holder_writeback_id IS NOT NULL AND (NEW.holder_task_id IS NOT NULL OR NEW.holder_workflow_attempt_id IS NOT NULL OR NEW.writer_session_id IS NOT NULL OR NEW.state='RELEASED')
BEGIN SELECT RAISE(ABORT,'workspace lease has incompatible owners'); END;

CREATE TABLE workflow_writeback_queue (
    writeback_id TEXT PRIMARY KEY REFERENCES workflow_writeback(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    canonical_root TEXT NOT NULL,
    root_fingerprint TEXT NOT NULL,
    position INTEGER NOT NULL CHECK(position>0),
    state TEXT NOT NULL CHECK(state IN ('QUEUED','ADMITTED','FINISHED')),
    enqueued_at TEXT NOT NULL,
    admitted_at TEXT,
    finished_at TEXT,
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    UNIQUE(canonical_root,position)
);
CREATE INDEX idx_workflow_writeback_waiting ON workflow_writeback_queue(canonical_root,state,position);
CREATE TRIGGER trg_workflow_writeback_queue_owner BEFORE INSERT ON workflow_writeback_queue
WHEN NOT EXISTS(SELECT 1 FROM workflow_writeback w WHERE w.id=NEW.writeback_id AND w.project_id=NEW.project_id
    AND w.state='CONFIRMED' AND NEW.canonical_root=json_extract(w.intent_json,'$.before.canonicalRoot')
    AND NEW.root_fingerprint=json_extract(w.intent_json,'$.before.rootFingerprint'))
BEGIN SELECT RAISE(ABORT,'workflow writeback queue owner mismatch'); END;
CREATE TRIGGER trg_workflow_writeback_queue_identity BEFORE UPDATE OF writeback_id,project_id,canonical_root,root_fingerprint,position,enqueued_at ON workflow_writeback_queue
BEGIN SELECT RAISE(ABORT,'workflow writeback queue identity is immutable'); END;
CREATE TRIGGER trg_workflow_writeback_queue_retained BEFORE DELETE ON workflow_writeback_queue
BEGIN SELECT RAISE(ABORT,'workflow writeback queue history must be retained'); END;
CREATE TABLE workflow_writeback_receipt (
    writeback_id TEXT PRIMARY KEY REFERENCES workflow_writeback(id),
    target_sha256 TEXT NOT NULL CHECK(length(target_sha256)=64),
    lease_version INTEGER NOT NULL CHECK(lease_version>=0),
    confirmed_at TEXT NOT NULL
);
CREATE TRIGGER trg_workflow_writeback_receipt_owner BEFORE INSERT ON workflow_writeback_receipt
WHEN NOT EXISTS(SELECT 1 FROM workflow_writeback w JOIN workspace_lease l ON l.holder_writeback_id=w.id
    JOIN workflow_writeback_queue q ON q.writeback_id=w.id WHERE w.id=NEW.writeback_id AND w.state='APPLYING'
    AND w.prepared_at IS NOT NULL AND q.state='ADMITTED' AND l.state='HELD' AND l.version=NEW.lease_version
    AND NEW.target_sha256=json_extract(w.intent_json,'$.preview.targetSha256'))
BEGIN SELECT RAISE(ABORT,'workflow writeback receipt owner mismatch'); END;
CREATE TRIGGER trg_workflow_writeback_receipt_immutable BEFORE UPDATE ON workflow_writeback_receipt
BEGIN SELECT RAISE(ABORT,'workflow writeback receipt is immutable'); END;
CREATE TRIGGER trg_workflow_writeback_receipt_retained BEFORE DELETE ON workflow_writeback_receipt
BEGIN SELECT RAISE(ABORT,'workflow writeback receipt must be retained'); END;
CREATE TRIGGER trg_workflow_writeback_state_guard BEFORE UPDATE OF state ON workflow_writeback
WHEN (NEW.state='APPLYING' AND (NEW.prepared_at IS NULL OR NOT EXISTS(
    SELECT 1 FROM workspace_lease l JOIN workflow_writeback_queue q ON q.writeback_id=l.holder_writeback_id
    WHERE l.holder_writeback_id=NEW.id AND l.state='HELD' AND q.state='ADMITTED')))
 OR (NEW.state='APPLIED' AND NOT EXISTS(SELECT 1 FROM workflow_writeback_receipt r WHERE r.writeback_id=NEW.id))
BEGIN SELECT RAISE(ABORT,'workflow writeback proof required'); END;

CREATE TRIGGER trg_workflow_writeback_transitions BEFORE UPDATE ON workflow_writeback
WHEN NEW.version<>OLD.version+1
 OR NOT ((OLD.state='CONFIRMED' AND NEW.state IN ('APPLYING','BLOCKED'))
     OR (OLD.state='APPLYING' AND NEW.state IN ('APPLIED','BLOCKED'))
     OR (OLD.state='BLOCKED' AND NEW.state=CASE WHEN OLD.prepared_at IS NULL THEN 'CONFIRMED' ELSE 'APPLYING' END))
 OR (NEW.prepared_at IS NOT OLD.prepared_at AND NOT (OLD.state='CONFIRMED' AND NEW.state='APPLYING' AND OLD.prepared_at IS NULL AND NEW.prepared_at IS NOT NULL))
BEGIN SELECT RAISE(ABORT,'workflow writeback transition invalid'); END;
CREATE TRIGGER trg_workflow_writeback_queue_transitions BEFORE UPDATE ON workflow_writeback_queue
WHEN NEW.version<>OLD.version+1 OR NOT ((OLD.state='QUEUED' AND NEW.state='ADMITTED') OR (OLD.state='ADMITTED' AND NEW.state='FINISHED'))
BEGIN SELECT RAISE(ABORT,'workflow writeback queue transition invalid'); END;
