CREATE TABLE ppt_agent_run (
 id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES ppt_document(id),
 idempotency_key TEXT NOT NULL, input_sha TEXT NOT NULL CHECK(length(input_sha)=64),
 user_text TEXT NOT NULL, scope_json TEXT NOT NULL CHECK(json_valid(scope_json)),
 source_revision INTEGER NOT NULL CHECK(source_revision>=0), phase TEXT NOT NULL,
 model_json TEXT NOT NULL, root_path TEXT NOT NULL, context_json TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PREPARED','CREATING','CREATE_UNKNOWN','SENDING','UNKNOWN','RUNNING','STOPPING','WAITING_INPUT','COMPLETED','STOPPED','FAILED')),
 detail TEXT NOT NULL DEFAULT '', answer TEXT NOT NULL DEFAULT '', plan_json TEXT,
 external_session_id TEXT UNIQUE, generation TEXT, message_id TEXT NOT NULL UNIQUE,
 request_json TEXT, request_sha TEXT, create_dispatched INTEGER NOT NULL DEFAULT 0 CHECK(create_dispatched IN (0,1)), round INTEGER NOT NULL DEFAULT 0,
 stop_reason TEXT, stop_proof TEXT, input_tokens INTEGER, output_tokens INTEGER,
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0,
 UNIQUE(document_id,idempotency_key), UNIQUE(id,document_id),
 CHECK((request_json IS NULL)=(request_sha IS NULL)),
 CHECK(external_session_id IS NULL OR plan_json IS NOT NULL),
 CHECK(generation IS NULL OR plan_json IS NOT NULL),
 CHECK(state NOT IN ('STOPPED','COMPLETED','FAILED','WAITING_INPUT') OR stop_proof IS NOT NULL)
);
CREATE UNIQUE INDEX ppt_agent_active_writer ON ppt_agent_run(document_id)
 WHERE state NOT IN ('COMPLETED','STOPPED','FAILED');
CREATE INDEX ppt_agent_history ON ppt_agent_run(document_id,created_at,id);
CREATE TABLE ppt_agent_question (
 id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES ppt_agent_run(id),
 document_id TEXT NOT NULL REFERENCES ppt_document(id), prompt TEXT NOT NULL,
 options_json TEXT NOT NULL CHECK(json_valid(options_json)),
 state TEXT NOT NULL CHECK(state IN ('PENDING','ANSWERED','CLOSED')), answer TEXT,
 reply_key TEXT, reply_sha TEXT, created_at TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0,
 FOREIGN KEY(run_id,document_id) REFERENCES ppt_agent_run(id,document_id)
);
CREATE UNIQUE INDEX ppt_agent_pending_question ON ppt_agent_question(run_id) WHERE state='PENDING';
CREATE TABLE ppt_agent_receipt (
 run_id TEXT NOT NULL REFERENCES ppt_agent_run(id), idempotency_key TEXT NOT NULL,
 tool TEXT NOT NULL, input_sha TEXT NOT NULL CHECK(length(input_sha)=64),
 response_json TEXT NOT NULL, created_at TEXT NOT NULL,
 PRIMARY KEY(run_id,idempotency_key)
);
CREATE TABLE ppt_agent_prompt (
 run_id TEXT NOT NULL REFERENCES ppt_agent_run(id), round INTEGER NOT NULL,
 message_id TEXT NOT NULL UNIQUE, request_json TEXT NOT NULL, request_sha TEXT NOT NULL,
 created_at TEXT NOT NULL, PRIMARY KEY(run_id,round)
);
