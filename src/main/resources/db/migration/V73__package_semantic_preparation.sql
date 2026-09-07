-- Preserve historical turns and identities while extending only the phase vocabulary.
DROP TRIGGER require_open_designer_turn;
DROP TRIGGER require_open_designer_dispatch;
CREATE TABLE designer_conversation_turn_v73 (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES designer_conversation(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL UNIQUE,
    phase TEXT NOT NULL CHECK(phase IN ('REQUIREMENT','PACKAGE_QUESTION','PACKAGE_DESIGN','PACKAGE_SEMANTICS')),
    candidate_run_id TEXT NOT NULL UNIQUE,
    request_json TEXT,
    request_sha256 TEXT,
    state TEXT NOT NULL CHECK(state IN ('PREPARED','SENDING','SENT','SETTLED','UNKNOWN')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
INSERT INTO designer_conversation_turn_v73 SELECT * FROM designer_conversation_turn;
DROP TABLE designer_conversation_turn;
ALTER TABLE designer_conversation_turn_v73 RENAME TO designer_conversation_turn;
CREATE UNIQUE INDEX ux_designer_conversation_active_turn ON designer_conversation_turn(conversation_id) WHERE state!='SETTLED';
CREATE INDEX idx_designer_turn_history ON designer_conversation_turn(conversation_id,created_at,id);
CREATE TRIGGER require_open_designer_turn BEFORE INSERT ON designer_conversation_turn
WHEN NOT EXISTS (SELECT 1 FROM designer_conversation WHERE id=NEW.conversation_id AND state='OPEN')
BEGIN SELECT RAISE(ABORT,'design conversation is retired or not bound'); END;
CREATE TRIGGER require_open_designer_dispatch BEFORE UPDATE OF state ON designer_conversation_turn
WHEN NEW.state='SENDING' AND NOT EXISTS (SELECT 1 FROM designer_conversation WHERE id=NEW.conversation_id AND state='OPEN')
BEGIN SELECT RAISE(ABORT,'design conversation is retired or not bound'); END;

CREATE TABLE package_semantic_preparation (
 id TEXT PRIMARY KEY,
 design_work_package_id TEXT NOT NULL REFERENCES design_work_package(id),
 discussion_revision INTEGER NOT NULL CHECK(discussion_revision>0),
 remote_id TEXT NOT NULL,
 requirement_sha256 TEXT NOT NULL CHECK(length(requirement_sha256)=64),
 prompt_version TEXT NOT NULL CHECK(prompt_version='PACKAGE_SEMANTIC_PREPARATION_V1'),
 reasons_json TEXT NOT NULL CHECK(json_valid(reasons_json)),
 base_prompt TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PREPARED','RUNNING','READY','DISPATCHING','DISPATCHED','FAILED')),
 material TEXT CHECK(length(CAST(material AS BLOB))<=32768),
 failure_code TEXT,
 created_at TEXT NOT NULL,
 version INTEGER NOT NULL DEFAULT 0,
 UNIQUE(design_work_package_id,discussion_revision)
);
CREATE TRIGGER freeze_package_semantic_preparation BEFORE UPDATE ON package_semantic_preparation
WHEN NEW.design_work_package_id!=OLD.design_work_package_id OR NEW.discussion_revision!=OLD.discussion_revision
 OR NEW.remote_id!=OLD.remote_id OR NEW.requirement_sha256!=OLD.requirement_sha256
 OR NEW.prompt_version!=OLD.prompt_version OR NEW.reasons_json!=OLD.reasons_json OR NEW.base_prompt!=OLD.base_prompt
BEGIN SELECT RAISE(ABORT,'semantic preparation identity is immutable'); END;
