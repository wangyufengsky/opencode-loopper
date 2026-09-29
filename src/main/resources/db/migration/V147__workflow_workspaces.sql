CREATE TABLE workflow_workspace (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_node_attempt(id),
    requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    state TEXT NOT NULL CHECK(state IN ('PREPARING','READY','CAPTURING','FROZEN','RESTORING','RESTORED','RELEASED')),
    project_directory TEXT NOT NULL,
    canonical_root TEXT NOT NULL,
    root_fingerprint TEXT NOT NULL,
    source_branch TEXT NOT NULL,
    source_commit TEXT NOT NULL,
    base_tree TEXT NOT NULL,
    branch TEXT NOT NULL,
    checkpoint_ref TEXT NOT NULL UNIQUE,
    seed_snapshot_id TEXT REFERENCES workflow_code_snapshot(id),
    seed_sha256 TEXT,
    seed_tree TEXT,
    checkpoint_commit TEXT,
    checkpoint_tree TEXT,
    stash_commit TEXT,
    release_receipt_json TEXT CHECK(release_receipt_json IS NULL OR json_valid(release_receipt_json)),
    version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK((seed_snapshot_id IS NULL)=(seed_sha256 IS NULL) AND (seed_snapshot_id IS NULL)=(seed_tree IS NULL)),
    CHECK((checkpoint_commit IS NULL)=(checkpoint_tree IS NULL)),
    CHECK(state NOT IN ('FROZEN') OR checkpoint_tree IS NOT NULL)
);
CREATE INDEX idx_workflow_workspace_requirement ON workflow_workspace(requirement_id,attempt_id);
CREATE TRIGGER trg_workflow_workspace_owner BEFORE INSERT ON workflow_workspace
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id JOIN workspace_lease l ON l.holder_workflow_attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND a.adapter_key='model.write.v1' AND a.state='PREPARING'
    AND n.latest_attempt_id=a.id AND n.state='ACTIVE' AND n.requirement_id=NEW.requirement_id AND r.project_id=NEW.project_id
    AND l.canonical_root=NEW.canonical_root AND l.root_fingerprint=NEW.root_fingerprint AND l.state='HELD')
BEGIN SELECT RAISE(ABORT,'workflow workspace owner mismatch'); END;
CREATE TRIGGER trg_workflow_workspace_identity BEFORE UPDATE OF attempt_id,requirement_id,project_id,
    project_directory,canonical_root,root_fingerprint,source_branch,source_commit,base_tree,branch,checkpoint_ref,
    seed_snapshot_id,seed_sha256,seed_tree,created_at ON workflow_workspace
BEGIN SELECT RAISE(ABORT,'workflow workspace identity is immutable'); END;
CREATE TRIGGER trg_workflow_workspace_checkpoint BEFORE UPDATE OF checkpoint_commit,checkpoint_tree,stash_commit ON workflow_workspace
WHEN OLD.checkpoint_tree IS NOT NULL OR OLD.state<>'CAPTURING' OR NEW.checkpoint_tree IS NULL
BEGIN SELECT RAISE(ABORT,'workflow workspace checkpoint is immutable'); END;
CREATE TRIGGER trg_workflow_workspace_retained BEFORE DELETE ON workflow_workspace
BEGIN SELECT RAISE(ABORT,'workflow workspace history must be retained'); END;
CREATE TRIGGER trg_workflow_workspace_release_receipt BEFORE UPDATE OF release_receipt_json ON workflow_workspace
WHEN OLD.release_receipt_json IS NOT NULL OR OLD.state<>'RESTORED' OR NEW.release_receipt_json IS NULL
BEGIN SELECT RAISE(ABORT,'workflow workspace release receipt is immutable'); END;
