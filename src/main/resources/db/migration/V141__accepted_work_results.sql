-- Accepted canonical output is a reusable work result. Rejected raw submissions remain unstored.
-- Historical runs retain their original storage contract, including runs still open at upgrade.
ALTER TABLE ai_candidate_submission_run
    ADD COLUMN result_storage_version INTEGER NOT NULL DEFAULT 0 CHECK (result_storage_version IN (0,1));

CREATE TRIGGER trg_candidate_result_storage_immutable
BEFORE UPDATE OF result_storage_version ON ai_candidate_submission_run
WHEN OLD.result_storage_version IS NOT NEW.result_storage_version
BEGIN
    SELECT RAISE(ABORT,'candidate result storage contract is immutable');
END;

CREATE TABLE accepted_work_result (
    id TEXT PRIMARY KEY REFERENCES ai_candidate_submission_attempt(id) ON DELETE CASCADE,
    run_id TEXT NOT NULL UNIQUE REFERENCES ai_candidate_submission_run(id) ON DELETE CASCADE,
    content TEXT NOT NULL CHECK (json_valid(content) AND json_type(content)='object'
        AND length(CAST(content AS BLOB)) BETWEEN 2 AND 131072),
    sha256 TEXT NOT NULL CHECK (length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
    created_at TEXT NOT NULL
);

CREATE TRIGGER trg_accepted_work_result_identity
BEFORE INSERT ON accepted_work_result
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM ai_candidate_submission_run r JOIN ai_candidate_submission_attempt a ON a.run_id=r.id
        WHERE r.id=NEW.run_id AND r.result_storage_version=1 AND r.state='ACCEPTED'
          AND r.terminal_attempt_id=NEW.id AND a.id=NEW.id AND a.outcome='ACCEPTED'
          AND a.canonical_result_sha256=NEW.sha256 AND a.created_at=NEW.created_at
    ) THEN RAISE(ABORT,'accepted work result identity mismatch') END;
END;

CREATE TRIGGER trg_accepted_work_result_immutable
BEFORE UPDATE ON accepted_work_result
BEGIN
    SELECT RAISE(ABORT,'accepted work result is immutable');
END;

-- Retire alongside the existing explicit candidate-history deletion, even in legacy fixtures
-- where FK cascading is disabled. A failed parent deletion rolls these deletes back too.
CREATE TRIGGER trg_candidate_attempt_work_result_delete
BEFORE DELETE ON ai_candidate_submission_attempt
BEGIN
    DELETE FROM accepted_work_result WHERE id=OLD.id;
END;
CREATE TRIGGER trg_candidate_run_work_result_delete
BEFORE DELETE ON ai_candidate_submission_run
BEGIN
    DELETE FROM accepted_work_result WHERE run_id=OLD.id;
END;
