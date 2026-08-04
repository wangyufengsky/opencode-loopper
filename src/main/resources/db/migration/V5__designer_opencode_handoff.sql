-- The Designer conversation is independent from task execution.  Retain the
-- latest remote OpenCode session and its transport state so an actual assistant
-- response can be polled and audited without claiming a fabricated reply.
ALTER TABLE designer_session ADD COLUMN external_session_id TEXT;
ALTER TABLE designer_session ADD COLUMN external_session_state TEXT;

CREATE INDEX idx_designer_session_handoff
    ON designer_session(state, external_session_id);
