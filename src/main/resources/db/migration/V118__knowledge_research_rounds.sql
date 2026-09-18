CREATE TABLE knowledge_research_round (
  turn_id TEXT NOT NULL REFERENCES knowledge_turn(id),
  ordinal INTEGER NOT NULL CHECK(ordinal > 0),
  message_id TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL CHECK(state IN ('PREPARED','SENDING','UNKNOWN','RUNNING','COMPLETED')),
  request_json TEXT NOT NULL,
  request_sha TEXT NOT NULL,
  thinking_prefix TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(turn_id, ordinal)
);
