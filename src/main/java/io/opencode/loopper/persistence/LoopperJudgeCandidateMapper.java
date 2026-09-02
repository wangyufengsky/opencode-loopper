package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Narrow persistence contract for the two-role JUDGE_DECISION_V1 review batch. */
public interface LoopperJudgeCandidateMapper {
    @Insert("""
            INSERT INTO judge_review_batch(
              id,task_id,execution_cycle_id,final_attempt_id,generation,state,
              created_at,updated_at,ended_at,version)
            VALUES(#{id},#{taskId},#{executionCycleId},#{finalAttemptId},#{generation},#{state},
              #{createdAt},#{updatedAt},#{endedAt},#{version})
            """)
    int insertJudgeReviewBatch(JudgeReviewBatchRow row);

    @Select("SELECT * FROM judge_review_batch WHERE id=#{id}")
    Optional<JudgeReviewBatchRow> findJudgeReviewBatch(String id);

    @Select("SELECT * FROM judge_review_batch WHERE task_id=#{taskId} ORDER BY generation,id")
    List<JudgeReviewBatchRow> listJudgeReviewBatches(String taskId);

    @Select("""
            SELECT * FROM judge_run
            WHERE review_batch_id=#{reviewBatchId} AND role=#{role}
            ORDER BY ordinal DESC,id DESC LIMIT 1
            """)
    Optional<JudgeRunRow> latestJudgeRunForBatchRole(
            @Param("reviewBatchId") String reviewBatchId, @Param("role") String role);

    @Select("SELECT COUNT(*) FROM judge_run WHERE review_batch_id=#{reviewBatchId} AND role=#{role} AND state='SESSION_ERROR'")
    int countJudgeSessionErrorsForBatchRole(
            @Param("reviewBatchId") String reviewBatchId, @Param("role") String role);

    @Update("""
            UPDATE judge_review_batch
            SET state=#{state},updated_at=#{updatedAt},ended_at=#{endedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateJudgeReviewBatch(JudgeReviewBatchRow row);

    @Insert("""
            INSERT INTO judge_candidate_source_snapshot(
              candidate_run_id,judge_run_id,task_id,execution_cycle_id,final_attempt_id,
              review_batch_id,role,ordinal,source_revision,prepared_owner_version,contract_version,
              source_prompt,source_prompt_sha256,canonical_evidence_json,evidence_sha256,created_at)
            VALUES(#{candidateRunId},#{judgeRunId},#{taskId},#{executionCycleId},#{finalAttemptId},
              #{reviewBatchId},#{role},#{ordinal},#{sourceRevision},#{preparedOwnerVersion},
              #{contractVersion},#{sourcePrompt},#{sourcePromptSha256},#{canonicalEvidenceJson},
              #{evidenceSha256},#{createdAt})
            """)
    int insertJudgeCandidateSourceSnapshot(JudgeCandidateSourceSnapshotRow row);

    @Select("SELECT * FROM judge_candidate_source_snapshot WHERE candidate_run_id=#{candidateRunId}")
    Optional<JudgeCandidateSourceSnapshotRow> findJudgeCandidateSourceSnapshot(String candidateRunId);

    @Insert("""
            INSERT INTO judge_candidate_accepted_result(
              candidate_run_id,judge_run_id,review_batch_id,role,source_revision,owner_version,
              contract_version,canonical_candidate_json,candidate_payload_sha256,
              canonical_decision_json,canonical_result_sha256,verdict,reason,evidence_json,
              settled_judge_run_id,created_at,updated_at,version)
            VALUES(#{candidateRunId},#{judgeRunId},#{reviewBatchId},#{role},#{sourceRevision},
              #{ownerVersion},#{contractVersion},#{canonicalCandidateJson},#{candidatePayloadSha256},
              #{canonicalDecisionJson},#{canonicalResultSha256},#{verdict},#{reason},#{evidenceJson},
              #{settledJudgeRunId},#{createdAt},#{updatedAt},#{version})
            """)
    int insertJudgeCandidateAcceptedResult(JudgeCandidateAcceptedResultRow row);

    @Select("SELECT * FROM judge_candidate_accepted_result WHERE candidate_run_id=#{candidateRunId}")
    Optional<JudgeCandidateAcceptedResultRow> findJudgeCandidateAcceptedResult(String candidateRunId);

    @Select("""
            SELECT * FROM judge_candidate_accepted_result
            WHERE settled_judge_run_id IS NULL ORDER BY created_at,candidate_run_id
            """)
    List<JudgeCandidateAcceptedResultRow> listUnsettledJudgeCandidateAcceptedResults();

    @Update("""
            UPDATE judge_candidate_accepted_result
            SET settled_judge_run_id=#{settledJudgeRunId},updated_at=#{updatedAt},version=version+1
            WHERE candidate_run_id=#{candidateRunId} AND version=#{expectedVersion}
              AND settled_judge_run_id IS NULL AND judge_run_id=#{settledJudgeRunId}
              AND #{settledJudgeRunId} IS NOT NULL AND length(#{settledJudgeRunId})>0
            """)
    int settleJudgeCandidateAcceptedResult(
            @Param("candidateRunId") String candidateRunId,
            @Param("expectedVersion") long expectedVersion,
            @Param("settledJudgeRunId") String settledJudgeRunId,
            @Param("updatedAt") String updatedAt);
}
