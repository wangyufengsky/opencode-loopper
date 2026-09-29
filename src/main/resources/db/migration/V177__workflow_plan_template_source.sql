-- A saved template records its origin, never an execution-state or input-value copy.
CREATE TABLE workflow_template_plan_source (
    template_id TEXT PRIMARY KEY REFERENCES workflow_template(id),
    requirement_id TEXT NOT NULL,
    plan_revision INTEGER NOT NULL,
    plan_sha256 TEXT NOT NULL CHECK(length(plan_sha256)=64),
    template_sha256 TEXT NOT NULL CHECK(length(template_sha256)=64),
    mode TEXT NOT NULL CHECK(mode IN ('CURRENT','INITIAL')),
    created_at TEXT NOT NULL,
    FOREIGN KEY(requirement_id,plan_revision) REFERENCES workflow_plan_revision(requirement_id,revision)
);
CREATE TRIGGER trg_workflow_template_plan_source_owner BEFORE INSERT ON workflow_template_plan_source
WHEN NOT EXISTS(SELECT 1 FROM workflow_template t JOIN workflow_template_revision v ON v.template_id=t.id
    WHERE t.id=NEW.template_id AND t.builtin=0 AND t.archived=0 AND t.head_revision=1 AND v.revision=1
    AND v.sha256=NEW.template_sha256)
    OR NOT EXISTS(SELECT 1 FROM workflow_plan_revision p WHERE p.requirement_id=NEW.requirement_id
        AND p.revision=NEW.plan_revision AND p.sha256=NEW.plan_sha256)
BEGIN SELECT RAISE(ABORT,'workflow template plan source mismatch'); END;
CREATE TRIGGER trg_workflow_template_plan_source_immutable BEFORE UPDATE ON workflow_template_plan_source
BEGIN SELECT RAISE(ABORT,'workflow template plan source is immutable'); END;
CREATE TRIGGER trg_workflow_template_plan_source_retained BEFORE DELETE ON workflow_template_plan_source
BEGIN SELECT RAISE(ABORT,'workflow template plan source must be retained'); END;
