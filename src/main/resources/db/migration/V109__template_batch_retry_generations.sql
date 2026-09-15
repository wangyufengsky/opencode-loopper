-- Preserve prior batch and submission identities; retry creates a distinct generation.
PRAGMA foreign_keys=OFF;
PRAGMA legacy_alter_table=ON;
SAVEPOINT template_batch_v109;
ALTER TABLE template_task_batch RENAME TO template_task_batch_v108;
DROP INDEX idx_template_task_batch_owner;
DROP INDEX idx_template_task_batch_cache;
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
    generation INTEGER NOT NULL DEFAULT 0 CHECK(generation>=0),
    UNIQUE(attempt_id,purpose,ordinal,generation)
);
CREATE INDEX idx_template_task_batch_owner ON template_task_batch(task_id,attempt_id,purpose,ordinal);
CREATE INDEX idx_template_task_batch_cache ON template_task_batch(input_sha256,state,updated_at DESC,id DESC);

INSERT INTO template_task_batch(id,task_id,attempt_id,session_id,ordinal,purpose,input_json,input_sha256,state,creation_plan_json,prompt_json,prompt_sha256,output_json,error_code,error_message,created_at,updated_at,version) SELECT id,task_id,attempt_id,session_id,ordinal,purpose,input_json,input_sha256,state,creation_plan_json,prompt_json,prompt_sha256,output_json,error_code,error_message,created_at,updated_at,version FROM template_task_batch_v108;
DROP TABLE template_task_batch_v108;
RELEASE template_batch_v109;
PRAGMA legacy_alter_table=OFF;
PRAGMA foreign_keys=ON;
