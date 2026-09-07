-- New conversations opt in explicitly. No historical row is backfilled.
CREATE TABLE package_behavior_policy (
 conversation_id TEXT PRIMARY KEY REFERENCES designer_conversation(id),
 policy_version TEXT NOT NULL CHECK(policy_version='PACKAGE_BEHAVIOR_POLICY_V1')
);
CREATE TRIGGER freeze_package_behavior_policy BEFORE UPDATE ON package_behavior_policy
BEGIN SELECT RAISE(ABORT,'behavior policy is immutable'); END;
CREATE TABLE package_behavior_preparation (
 id TEXT PRIMARY KEY,
 design_work_package_id TEXT NOT NULL REFERENCES design_work_package(id),
 discussion_revision INTEGER NOT NULL CHECK(discussion_revision>0),
 remote_id TEXT NOT NULL,
 requirement_sha256 TEXT NOT NULL CHECK(length(requirement_sha256)=64),
 base_prompt TEXT NOT NULL,
 context_json TEXT NOT NULL CHECK(json_valid(context_json)),
 prompt_version TEXT NOT NULL CHECK(prompt_version='PACKAGE_BEHAVIOR_PROMPT_20260907_R3'),
 state TEXT NOT NULL CHECK(state IN ('EXTRACTING','EXTRACT_READY','REVIEW_DISPATCHING','REVIEWING','READY','DESIGN_DISPATCHING','DISPATCHED','FAILED','UNCONFIRMED','STOPPED')),
 extraction TEXT CHECK(length(CAST(extraction AS BLOB))<=32768),
 review_remote_id TEXT,
 review_json TEXT CHECK(length(CAST(review_json AS BLOB))<=65536),
 book_json TEXT CHECK(json_valid(book_json) AND length(CAST(book_json AS BLOB))<=32768),
 book_sha256 TEXT CHECK(length(book_sha256)=64),
 failure_code TEXT,
 created_at TEXT NOT NULL,
 version INTEGER NOT NULL DEFAULT 0,
 UNIQUE(design_work_package_id,discussion_revision)
);
CREATE TRIGGER freeze_package_behavior_preparation BEFORE UPDATE ON package_behavior_preparation
WHEN NEW.design_work_package_id!=OLD.design_work_package_id OR NEW.discussion_revision!=OLD.discussion_revision
 OR NEW.remote_id!=OLD.remote_id OR NEW.requirement_sha256!=OLD.requirement_sha256 OR NEW.base_prompt!=OLD.base_prompt OR NEW.context_json!=OLD.context_json OR NEW.prompt_version!=OLD.prompt_version
 OR OLD.extraction IS NOT NULL AND NEW.extraction IS NOT OLD.extraction
 OR OLD.review_remote_id IS NOT NULL AND NEW.review_remote_id IS NOT OLD.review_remote_id
 OR OLD.review_json IS NOT NULL AND NEW.review_json IS NOT OLD.review_json
 OR OLD.book_json IS NOT NULL AND (NEW.book_json IS NOT OLD.book_json OR NEW.book_sha256 IS NOT OLD.book_sha256)
BEGIN SELECT RAISE(ABORT,'behavior source and review evidence is immutable'); END;
CREATE TABLE package_behavior_run (
 run_id TEXT PRIMARY KEY REFERENCES ai_candidate_submission_run(id),
 preparation_id TEXT NOT NULL REFERENCES package_behavior_preparation(id),
 book_sha256 TEXT NOT NULL CHECK(length(book_sha256)=64)
);
CREATE TRIGGER freeze_package_behavior_run BEFORE UPDATE ON package_behavior_run
BEGIN SELECT RAISE(ABORT,'behavior run binding is immutable'); END;

CREATE TRIGGER require_behavior_review_owner BEFORE INSERT ON designer_conversation
WHEN NEW.scope_key LIKE 'BEHAVIOR_REVIEW:%' AND NOT EXISTS (SELECT 1 FROM designer_session WHERE id=NEW.designer_session_id AND state='RUNNING')
BEGIN SELECT RAISE(ABORT,'behavior review owner is not running'); END;
CREATE TRIGGER require_behavior_review_dispatch BEFORE UPDATE OF state ON designer_conversation_turn
WHEN NEW.state='SENDING' AND EXISTS (SELECT 1 FROM designer_conversation c JOIN designer_session d ON d.id=c.designer_session_id
 WHERE c.id=NEW.conversation_id AND c.scope_key LIKE 'BEHAVIOR_REVIEW:%' AND d.state!='RUNNING')
BEGIN SELECT RAISE(ABORT,'behavior review owner has stopped'); END;
