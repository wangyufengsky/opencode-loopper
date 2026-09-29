CREATE TABLE workflow_push (
    id TEXT PRIMARY KEY,
    requirement_id TEXT NOT NULL UNIQUE REFERENCES workflow_requirement(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    publication_id TEXT NOT NULL UNIQUE REFERENCES workflow_publication(id),
    request_key TEXT NOT NULL UNIQUE,
    request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
    input_json TEXT NOT NULL CHECK(json_valid(input_json)),
    input_sha256 TEXT NOT NULL CHECK(length(input_sha256)=64),
    state TEXT NOT NULL CHECK(state IN ('PREPARING','RUNNING','BLOCKED','PUSHED')),
    version INTEGER NOT NULL CHECK(version>=0),
    active_attempt_id TEXT NOT NULL,
    reason_code TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE workflow_push_attempt (
    id TEXT PRIMARY KEY,
    push_id TEXT NOT NULL REFERENCES workflow_push(id),
    ordinal INTEGER NOT NULL CHECK(ordinal>0),
    request_json TEXT CHECK(request_json IS NULL OR json_valid(request_json)),
    request_sha256 TEXT CHECK(request_sha256 IS NULL OR length(request_sha256)=64),
    registration_json TEXT CHECK(registration_json IS NULL OR json_valid(registration_json)),
    result_json TEXT CHECK(result_json IS NULL OR json_valid(result_json)),
    version INTEGER NOT NULL CHECK(version>=0),
    created_at TEXT NOT NULL,
    UNIQUE(push_id,ordinal),
    CHECK((request_json IS NULL)=(request_sha256 IS NULL)),
    CHECK(registration_json IS NULL OR request_json IS NOT NULL),
    CHECK(result_json IS NULL OR registration_json IS NOT NULL)
);
CREATE INDEX idx_workflow_push_pending ON workflow_push(requirement_id) WHERE state IN ('PREPARING','RUNNING');
CREATE TRIGGER trg_workflow_push_owner BEFORE INSERT ON workflow_push
WHEN NEW.state<>'PREPARING' OR NEW.version<>0 OR NOT EXISTS(
    SELECT 1 FROM workflow_publication p WHERE p.id=NEW.publication_id AND p.requirement_id=NEW.requirement_id
      AND p.project_id=NEW.project_id AND p.state='COMMITTED' AND p.commit_sha=json_extract(NEW.input_json,'$.commit')
      AND json_extract(p.intent_json,'$.branch')=json_extract(NEW.input_json,'$.branch')
      AND json_extract(p.intent_json,'$.repository')=json_extract(NEW.input_json,'$.repository'))
BEGIN SELECT RAISE(ABORT,'workflow push owner mismatch'); END;
CREATE TRIGGER trg_workflow_push_update BEFORE UPDATE ON workflow_push
WHEN NEW.id<>OLD.id OR NEW.requirement_id<>OLD.requirement_id OR NEW.project_id<>OLD.project_id
  OR NEW.publication_id<>OLD.publication_id OR NEW.request_key<>OLD.request_key OR NEW.request_sha256<>OLD.request_sha256
  OR NEW.input_json<>OLD.input_json OR NEW.input_sha256<>OLD.input_sha256 OR NEW.created_at<>OLD.created_at
  OR NEW.version<>OLD.version+1 OR NOT (
    (OLD.state='PREPARING' AND NEW.state IN ('RUNNING','BLOCKED') AND NEW.active_attempt_id=OLD.active_attempt_id)
    OR (OLD.state='RUNNING' AND NEW.state IN ('BLOCKED','PUSHED') AND NEW.active_attempt_id=OLD.active_attempt_id)
    OR (OLD.state='BLOCKED' AND NEW.state IN ('PREPARING','RUNNING')))
  OR NOT EXISTS(SELECT 1 FROM workflow_push_attempt a WHERE a.id=NEW.active_attempt_id AND a.push_id=NEW.id)
BEGIN SELECT RAISE(ABORT,'workflow push intent is immutable'); END;
CREATE TRIGGER trg_workflow_push_receipt BEFORE UPDATE ON workflow_push
WHEN NEW.state='PUSHED' AND NOT EXISTS(
    SELECT 1 FROM workflow_push_attempt a WHERE a.id=NEW.active_attempt_id AND a.push_id=NEW.id
      AND json_extract(a.result_json,'$.stopConfirmed')=1 AND json_extract(a.result_json,'$.launched')=1
      AND json_extract(a.result_json,'$.exitCode')=0 AND json_extract(a.result_json,'$.cancelled')=0
      AND json_extract(a.result_json,'$.timedOut')=0 AND json_extract(a.result_json,'$.outputTruncated')=0
      AND json_extract(a.result_json,'$.error')='' AND trim(json_extract(a.result_json,'$.output'))='LOOPPER_GIT_PUSH_CONFIRMED')
BEGIN SELECT RAISE(ABORT,'workflow push proof required'); END;
CREATE TRIGGER trg_workflow_push_attempt_owner BEFORE INSERT ON workflow_push_attempt
WHEN NEW.version<>0 OR NEW.request_json IS NOT NULL OR NEW.registration_json IS NOT NULL OR NEW.result_json IS NOT NULL
  OR NOT EXISTS(SELECT 1 FROM workflow_push p WHERE p.id=NEW.push_id AND p.state IN ('PREPARING','BLOCKED'))
  OR (NEW.ordinal=1 AND NOT EXISTS(SELECT 1 FROM workflow_push p WHERE p.id=NEW.push_id AND p.active_attempt_id=NEW.id))
  OR (NEW.ordinal>1 AND NOT EXISTS(SELECT 1 FROM workflow_push p JOIN workflow_push_attempt a ON a.id=p.active_attempt_id
      WHERE p.id=NEW.push_id AND p.state='BLOCKED' AND a.ordinal=NEW.ordinal-1 AND json_extract(a.result_json,'$.stopConfirmed')=1))
BEGIN SELECT RAISE(ABORT,'workflow push retry stop proof required'); END;
CREATE TRIGGER trg_workflow_push_attempt_update BEFORE UPDATE ON workflow_push_attempt
WHEN NEW.id<>OLD.id OR NEW.push_id<>OLD.push_id OR NEW.ordinal<>OLD.ordinal OR NEW.created_at<>OLD.created_at OR NEW.version<>OLD.version+1
  OR (OLD.request_json IS NOT NULL AND (NEW.request_json IS NOT OLD.request_json OR NEW.request_sha256 IS NOT OLD.request_sha256))
  OR (OLD.registration_json IS NOT NULL AND NEW.registration_json IS NOT OLD.registration_json)
  OR (OLD.result_json IS NOT NULL AND NEW.result_json IS NOT OLD.result_json)
  OR NOT EXISTS(SELECT 1 FROM workflow_push p WHERE p.id=NEW.push_id AND p.active_attempt_id=NEW.id AND p.state IN ('PREPARING','RUNNING'))
  OR (NEW.request_json IS NOT NULL AND json_extract(NEW.request_json,'$.id') IS NOT NEW.id)
  OR (NEW.registration_json IS NOT NULL AND json_extract(NEW.registration_json,'$.requestSha256') IS NOT NEW.request_sha256)
  OR (NEW.result_json IS NOT NULL AND (json_extract(NEW.result_json,'$.requestSha256') IS NOT NEW.request_sha256
      OR json_extract(NEW.result_json,'$.worker.pid') IS NOT json_extract(NEW.registration_json,'$.worker.pid')
      OR json_extract(NEW.result_json,'$.worker.startedAt') IS NOT json_extract(NEW.registration_json,'$.worker.startedAt')))
BEGIN SELECT RAISE(ABORT,'workflow push attempt is immutable'); END;
CREATE TRIGGER trg_workflow_push_no_delete BEFORE DELETE ON workflow_push BEGIN SELECT RAISE(ABORT,'workflow push history is immutable'); END;
CREATE TRIGGER trg_workflow_push_attempt_no_delete BEFORE DELETE ON workflow_push_attempt BEGIN SELECT RAISE(ABORT,'workflow push history is immutable'); END;
