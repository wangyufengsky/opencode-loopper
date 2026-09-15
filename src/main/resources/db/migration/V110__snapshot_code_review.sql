-- Expand new admissions without rewriting frozen contracts, reports, or identities.
PRAGMA foreign_keys=OFF;
PRAGMA legacy_alter_table=ON;
SAVEPOINT snapshot_review_v110;
ALTER TABLE template_task_run RENAME TO template_task_run_v109;
DROP INDEX idx_template_task_run_created;
CREATE TABLE template_task_run (
    task_id TEXT PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    request_key TEXT NOT NULL UNIQUE,
    request_sha256 TEXT NOT NULL,
    template_id TEXT NOT NULL CHECK(template_id IN ('CODE_REVIEW','CONTRIBUTION_REPORT','SNAPSHOT_CODE_REVIEW')),
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

INSERT INTO template_task_run SELECT * FROM template_task_run_v109;
DROP TABLE template_task_run_v109;
ALTER TABLE template_task_batch RENAME TO template_task_batch_v109;
DROP INDEX idx_template_task_batch_owner;
DROP INDEX idx_template_task_batch_cache;
CREATE TABLE template_task_batch (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    attempt_id TEXT NOT NULL REFERENCES attempt(id) ON DELETE CASCADE,
    session_id TEXT REFERENCES execution_session(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    purpose TEXT NOT NULL CHECK(purpose IN ('REVIEW','CONTRIBUTOR','SNAPSHOT_PLAN','SNAPSHOT_LINKS','SNAPSHOT_ANALYSIS','SNAPSHOT_SUPPLEMENT','SNAPSHOT_REVIEW','SNAPSHOT_RELATION_ANALYSIS','SNAPSHOT_RELATION_REVIEW')),
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
    generation INTEGER NOT NULL DEFAULT 0 CHECK(generation>=0),
    UNIQUE(attempt_id,purpose,ordinal,generation)
);
CREATE INDEX idx_template_task_batch_owner ON template_task_batch(task_id,attempt_id,purpose,ordinal);
CREATE INDEX idx_template_task_batch_cache ON template_task_batch(input_sha256,state,updated_at DESC,id DESC);

INSERT INTO template_task_batch(id,task_id,attempt_id,session_id,ordinal,purpose,input_json,input_sha256,state,creation_plan_json,prompt_json,prompt_sha256,output_json,error_code,error_message,created_at,updated_at,version,generation) SELECT id,task_id,attempt_id,session_id,ordinal,purpose,input_json,input_sha256,state,creation_plan_json,prompt_json,prompt_sha256,output_json,error_code,error_message,created_at,updated_at,version,generation FROM template_task_batch_v109;
DROP TABLE template_task_batch_v109;
CREATE TABLE snapshot_review_run (
    task_id TEXT PRIMARY KEY REFERENCES task(id),
    mode TEXT NOT NULL CHECK (mode IN ('DATE_INCREMENTAL', 'FULL')),
    source_sha TEXT,
    snapshot_json TEXT,
    snapshot_sha256 TEXT,
    plan_json TEXT,
    plan_revision INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE snapshot_review_file (
    task_id TEXT NOT NULL REFERENCES snapshot_review_run(task_id),
    source_version TEXT NOT NULL,
    path TEXT NOT NULL,
    blob TEXT NOT NULL,
    mode TEXT NOT NULL,
    bytes INTEGER NOT NULL,
    limitation TEXT,
    PRIMARY KEY (task_id, source_version, path)
);
CREATE TABLE snapshot_review_read (
    batch_id TEXT NOT NULL REFERENCES template_task_batch(id),
    task_id TEXT NOT NULL REFERENCES snapshot_review_run(task_id),
    source_version TEXT NOT NULL,
    path TEXT NOT NULL,
    blob TEXT NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    content TEXT NOT NULL,
    PRIMARY KEY (batch_id, source_version, path, start_line, end_line)
);
CREATE TABLE snapshot_review_plan_revision (
    task_id TEXT NOT NULL REFERENCES snapshot_review_run(task_id),
    revision INTEGER NOT NULL,
    plan_json TEXT NOT NULL,
    reason TEXT NOT NULL,
    PRIMARY KEY (task_id, revision)
);
CREATE INDEX idx_snapshot_review_read_task ON snapshot_review_read(task_id);

RELEASE snapshot_review_v110;
PRAGMA legacy_alter_table=OFF;
PRAGMA foreign_keys=ON;
