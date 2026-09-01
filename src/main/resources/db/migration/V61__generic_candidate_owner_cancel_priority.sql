ALTER TABLE ai_candidate_internal_termination_intent
  ADD COLUMN owner_cancel_requested INTEGER NOT NULL DEFAULT 0
    CHECK (owner_cancel_requested IN (0,1));

ALTER TABLE ai_candidate_internal_termination_intent
  ADD COLUMN archive_when_complete INTEGER NOT NULL DEFAULT 0
    CHECK (archive_when_complete IN (0,1));

CREATE TRIGGER trg_generic_candidate_owner_cancel_monotonic
BEFORE UPDATE OF owner_cancel_requested ON ai_candidate_internal_termination_intent
WHEN OLD.owner_cancel_requested=1 AND NEW.owner_cancel_requested<>1
BEGIN
  SELECT RAISE(ABORT,'Generic candidate owner cancellation is monotonic');
END;

CREATE TRIGGER trg_generic_candidate_archive_request_monotonic
BEFORE UPDATE OF archive_when_complete ON ai_candidate_internal_termination_intent
WHEN OLD.archive_when_complete=1 AND NEW.archive_when_complete<>1
BEGIN
  SELECT RAISE(ABORT,'Generic candidate archive request is monotonic');
END;
