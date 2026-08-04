-- A Designer conversation and the review panel must refer to the same draft.
-- The binding is nullable only for historical sessions created before this migration.
ALTER TABLE designer_session ADD COLUMN loop_draft_id TEXT REFERENCES loop_draft(id);

CREATE INDEX idx_designer_session_draft
    ON designer_session(loop_draft_id);
