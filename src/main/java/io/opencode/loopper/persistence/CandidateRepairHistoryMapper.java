package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Select;

/** Bounded history lookup used by candidate repair progress. */
public interface CandidateRepairHistoryMapper {
    @Select("SELECT COUNT(*) FROM ai_candidate_submission_attempt WHERE run_id=#{runId}")
    int countCandidateSubmissionAttemptsForRun(String runId);

    @Select("SELECT * FROM ai_candidate_submission_attempt WHERE run_id=#{runId} ORDER BY ordinal DESC LIMIT 3")
    List<CandidateSubmissionAttemptRow> recentCandidateSubmissionAttempts(String runId);
}
