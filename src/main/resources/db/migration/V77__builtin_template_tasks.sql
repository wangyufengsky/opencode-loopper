-- Widen column-local constraints without replacing the Task table or its foreign-key graph.
ALTER TABLE task RENAME COLUMN execution_mode TO previous_execution_mode;
ALTER TABLE task ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'LEGACY_AGGREGATE'
    CHECK (execution_mode IN ('LEGACY_AGGREGATE','ROLLING_PACKAGES','TEMPLATE_REPORT'));
UPDATE task SET execution_mode=previous_execution_mode;
ALTER TABLE task DROP COLUMN previous_execution_mode;
ALTER TABLE task RENAME COLUMN workspace_policy TO previous_workspace_policy;
ALTER TABLE task ADD COLUMN workspace_policy TEXT
    CHECK (workspace_policy IS NULL OR workspace_policy IN ('RELEASE_BETWEEN_PACKAGES','PINNED_DIRECT','ISOLATED_REPORT'));
UPDATE task SET workspace_policy=previous_workspace_policy;
ALTER TABLE task DROP COLUMN previous_workspace_policy;

CREATE TABLE template_task_run (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    request_key TEXT NOT NULL UNIQUE,
    request_sha256 TEXT NOT NULL,
    template_id TEXT NOT NULL CHECK(template_id IN ('CODE_REVIEW','CONTRIBUTION_REPORT')),
    template_version TEXT NOT NULL,
    branch_id TEXT NOT NULL,
    branch_label TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    remote_name TEXT,
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL CHECK(end_date >= start_date),
    contract_json TEXT NOT NULL,
    snapshot_json TEXT,
    snapshot_sha256 TEXT,
    repair_round INTEGER NOT NULL DEFAULT 0 CHECK(repair_round BETWEEN 0 AND 2),
    bypass_cache INTEGER NOT NULL DEFAULT 0 CHECK(bypass_cache IN (0,1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    CHECK ((snapshot_json IS NULL) = (snapshot_sha256 IS NULL))
);
CREATE INDEX idx_template_task_run_created ON template_task_run(created_at DESC,task_id DESC);

CREATE TABLE template_task_batch (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    attempt_id TEXT NOT NULL REFERENCES attempt(id) ON DELETE CASCADE,
    session_id TEXT REFERENCES execution_session(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    purpose TEXT NOT NULL CHECK(purpose IN ('REVIEW','CONTRIBUTOR')),
    input_json TEXT NOT NULL,
    input_sha256 TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('PREPARED','CREATING','PROMPT_READY','DISPATCHING','RUNNING','VALIDATED','STOPPING','STOPPED','FAILED')),
    creation_plan_json TEXT,
    prompt_json TEXT,
    prompt_sha256 TEXT,
    output_json TEXT,
    error_code TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(attempt_id,purpose,ordinal)
);
CREATE INDEX idx_template_task_batch_owner ON template_task_batch(task_id,attempt_id,purpose,ordinal);
CREATE INDEX idx_template_task_batch_cache ON template_task_batch(input_sha256,state,updated_at DESC,id DESC);

-- Existing runs and rules remain queryable. Trigger retirement is enforced at every service entry point.
UPDATE automation_rule SET state='DISABLED',version=version+1,
    updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE state='ENABLED';
