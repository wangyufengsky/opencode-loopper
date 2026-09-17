CREATE TABLE knowledge_source (
  id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES project(id), kind TEXT NOT NULL,
  name TEXT NOT NULL, path TEXT NOT NULL, sha256 TEXT, state TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  CHECK(kind IN ('DIRECTORY','UPLOAD')), CHECK(state IN ('PREPARED','READY','FAILED','REMOVED'))
);
CREATE INDEX knowledge_source_project ON knowledge_source(project_id,created_at,id);
CREATE TABLE knowledge_conversation (
  id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES project(id), root_path TEXT NOT NULL,
  title TEXT NOT NULL, model_json TEXT NOT NULL, sources_json TEXT NOT NULL, connections_json TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'IDLE', remote_id TEXT UNIQUE, plan_json TEXT,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0,
  CHECK(state IN ('IDLE','RUNNING','STOPPING','DISCONNECTED'))
);
CREATE INDEX knowledge_conversation_project ON knowledge_conversation(project_id,created_at,id);
CREATE TABLE knowledge_turn (
  id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES knowledge_conversation(id),
  ordinal INTEGER NOT NULL, idempotency_key TEXT NOT NULL, message_id TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL, user_text TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', detail TEXT NOT NULL DEFAULT '',
  request_json TEXT, request_sha TEXT, input_tokens INTEGER, output_tokens INTEGER,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0,
  UNIQUE(conversation_id,idempotency_key), UNIQUE(conversation_id,ordinal),
  CHECK(state IN ('PREPARED','CREATING','CREATE_UNKNOWN','SENDING','UNKNOWN','RUNNING','STOPPING','COMPLETED','STOPPED','FAILED'))
);
CREATE UNIQUE INDEX knowledge_turn_active ON knowledge_turn(conversation_id)
  WHERE state NOT IN ('COMPLETED','STOPPED','FAILED');
CREATE TABLE knowledge_citation (
  id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES knowledge_conversation(id),
  turn_id TEXT NOT NULL REFERENCES knowledge_turn(id), kind TEXT NOT NULL,
  source_id TEXT NOT NULL, name TEXT NOT NULL, location TEXT NOT NULL, sha256 TEXT NOT NULL,
  body_json TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE INDEX knowledge_citation_turn ON knowledge_citation(conversation_id,turn_id,created_at,id);
CREATE TABLE knowledge_call (
  id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES knowledge_conversation(id),
  turn_id TEXT NOT NULL REFERENCES knowledge_turn(id), tool TEXT NOT NULL, state TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX knowledge_call_turn ON knowledge_call(conversation_id,turn_id,created_at,id);

-- New private tools belong exclusively to the knowledge profile; existing policy choices remain intact.
INSERT OR IGNORE INTO assist_tool_policy(scope,server_id,tool_name,enabled,version,updated_at) VALUES
 ('','@loopper-assist','list_knowledge_sources',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 ('','@loopper-assist','browse_knowledge_source',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 ('','@loopper-assist','search_knowledge',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 ('','@loopper-assist','read_knowledge_source',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now'));
