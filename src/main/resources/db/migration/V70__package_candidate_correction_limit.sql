-- NULL preserves the unlimited MCP contract of every existing run.
ALTER TABLE ai_candidate_submission_run ADD COLUMN correction_limit INTEGER
    CHECK (correction_limit IS NULL OR (correction_limit BETWEEN 2 AND 16
        AND attempts_used <= correction_limit AND candidate_kind = 'PACKAGE_DESIGN_V1' AND submission_channel = 'INTERNAL_MCP'));

CREATE TRIGGER candidate_correction_limit_immutable
BEFORE UPDATE OF correction_limit ON ai_candidate_submission_run
WHEN NEW.correction_limit IS NOT OLD.correction_limit
BEGIN
    SELECT RAISE(ABORT, 'candidate correction limit is immutable');
END;
