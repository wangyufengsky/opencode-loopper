-- Preserve why a candidate run was closed so crash recovery cannot reinterpret
-- timeout/provider/interaction failures as a normal zero-submission completion.
ALTER TABLE ai_candidate_submission_run ADD COLUMN close_reason TEXT
    CHECK (close_reason IS NULL OR close_reason IN (
        'NORMAL_COMPLETION_ZERO_SUBMISSION','INTERACTION_FORBIDDEN','TIMEOUT',
        'REMOTE_FAILED','OWNER_REQUESTED'));
