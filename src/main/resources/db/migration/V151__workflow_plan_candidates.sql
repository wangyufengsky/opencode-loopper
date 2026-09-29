CREATE TABLE workflow_plan_candidate (
    id TEXT PRIMARY KEY,
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    attempt_id TEXT NOT NULL REFERENCES workflow_node_attempt(id),
    output_name TEXT NOT NULL,
    base_revision INTEGER NOT NULL,
    graph_json TEXT NOT NULL CHECK(json_valid(graph_json) AND json_type(graph_json)='object' AND length(CAST(graph_json AS BLOB))<=2097152),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64),
    state TEXT NOT NULL CHECK(state IN ('PENDING','APPLIED','REJECTED')),
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    applied_revision INTEGER,
    decision_reason TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(attempt_id,output_name),
    FOREIGN KEY(requirement_id,base_revision) REFERENCES workflow_plan_revision(requirement_id,revision),
    FOREIGN KEY(requirement_id,applied_revision) REFERENCES workflow_plan_revision(requirement_id,revision),
    CHECK((state='APPLIED')=(applied_revision IS NOT NULL))
);
CREATE INDEX idx_workflow_plan_candidate_page ON workflow_plan_candidate(requirement_id,state,created_at DESC,id DESC);
CREATE TRIGGER trg_workflow_plan_candidate_owner BEFORE INSERT ON workflow_plan_candidate
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND n.requirement_id=NEW.requirement_id AND a.plan_revision=NEW.base_revision)
BEGIN SELECT RAISE(ABORT,'workflow plan candidate owner mismatch'); END;
CREATE TRIGGER trg_workflow_plan_candidate_identity BEFORE UPDATE OF id,requirement_id,attempt_id,output_name,base_revision,graph_json,sha256,created_at ON workflow_plan_candidate
BEGIN SELECT RAISE(ABORT,'workflow plan candidate content is immutable'); END;
CREATE TRIGGER trg_workflow_plan_candidate_retained BEFORE DELETE ON workflow_plan_candidate
BEGIN SELECT RAISE(ABORT,'workflow plan candidate history must be retained'); END;
CREATE TRIGGER trg_workflow_plan_candidate_terminal BEFORE UPDATE ON workflow_plan_candidate
WHEN OLD.state<>'PENDING'
BEGIN SELECT RAISE(ABORT,'workflow plan candidate decision is immutable'); END;
