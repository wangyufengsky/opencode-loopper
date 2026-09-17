CREATE TABLE knowledge_conversation_options (
  conversation_id TEXT PRIMARY KEY REFERENCES knowledge_conversation(id),
  contract_version INTEGER NOT NULL DEFAULT 1,
  timezone TEXT NOT NULL DEFAULT 'UTC',
  archived_at TEXT,
  last_activity_at TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0
);
INSERT INTO knowledge_conversation_options(conversation_id,last_activity_at)
SELECT id,updated_at FROM knowledge_conversation;
CREATE INDEX knowledge_history_activity ON knowledge_conversation_options(archived_at,last_activity_at,conversation_id);

CREATE TABLE knowledge_question (
  id TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES knowledge_conversation(id),
  turn_id TEXT NOT NULL REFERENCES knowledge_turn(id),
  remote_id TEXT NOT NULL,
  prompt_json TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('PENDING','PREPARED','SENDING','ANSWERED','UNKNOWN','CLOSED')),
  answer_key TEXT,
  answers_json TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  UNIQUE(conversation_id,remote_id),
  UNIQUE(conversation_id,answer_key)
);
CREATE INDEX knowledge_question_turn ON knowledge_question(conversation_id,turn_id,created_at,id);

CREATE TABLE knowledge_git_snapshot (
  id TEXT PRIMARY KEY,
  owner TEXT NOT NULL,
  query_sha TEXT NOT NULL,
  body_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

INSERT OR IGNORE INTO assist_tool_policy(scope,server_id,tool_name,enabled,version,updated_at) VALUES
 ('','@loopper-assist','inspect_knowledge_git',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 ('','@loopper-assist','list_knowledge_git_authors',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 ('','@loopper-assist','search_knowledge_git_commits',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 ('','@loopper-assist','read_knowledge_git_commit',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 ('','@loopper-assist','read_knowledge_git_file',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 ('','@loopper-assist','blame_knowledge_git_lines',1,0,strftime('%Y-%m-%dT%H:%M:%fZ','now'));
