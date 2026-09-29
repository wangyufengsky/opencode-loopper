-- A human outcome is a durable request, separate from every original node verdict.
CREATE TABLE workflow_finish_intent (
    requirement_id TEXT PRIMARY KEY REFERENCES workflow_requirement(id),
    target_state TEXT NOT NULL CHECK(target_state IN ('COMPLETED','FAILED','CANCELLED')),
    reason TEXT NOT NULL CHECK(length(trim(reason)) BETWEEN 1 AND 4000),
    plan_revision INTEGER NOT NULL,
    requested_version INTEGER NOT NULL CHECK(requested_version>=0),
    requested_at TEXT NOT NULL,
    finalized_at TEXT,
    FOREIGN KEY(requirement_id,plan_revision) REFERENCES workflow_plan_revision(requirement_id,revision)
);
CREATE INDEX idx_workflow_finish_pending ON workflow_finish_intent(requirement_id) WHERE finalized_at IS NULL;
CREATE TRIGGER trg_workflow_finish_owner BEFORE INSERT ON workflow_finish_intent
WHEN NOT EXISTS(SELECT 1 FROM workflow_requirement r WHERE r.id=NEW.requirement_id
    AND r.state='STOPPING' AND r.head_revision=NEW.plan_revision AND r.version=NEW.requested_version+1)
BEGIN SELECT RAISE(ABORT,'workflow finish identity mismatch'); END;
CREATE TRIGGER trg_workflow_finish_immutable BEFORE UPDATE ON workflow_finish_intent
WHEN NEW.requirement_id<>OLD.requirement_id OR NEW.target_state<>OLD.target_state OR NEW.reason<>OLD.reason
    OR NEW.plan_revision<>OLD.plan_revision OR NEW.requested_version<>OLD.requested_version
    OR NEW.requested_at<>OLD.requested_at OR OLD.finalized_at IS NOT NULL OR NEW.finalized_at IS NULL
    OR NOT EXISTS(SELECT 1 FROM workflow_requirement r WHERE r.id=NEW.requirement_id AND r.state=NEW.target_state)
BEGIN SELECT RAISE(ABORT,'workflow finish intent is immutable'); END;
CREATE TRIGGER trg_workflow_finish_no_delete BEFORE DELETE ON workflow_finish_intent
BEGIN SELECT RAISE(ABORT,'workflow finish history is immutable'); END;
