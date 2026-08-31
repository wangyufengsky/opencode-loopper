package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Narrow persistence interface for candidate submission and runtime-generation binding. */
public interface LoopperMachineCandidateMapper {
    @Insert("""
            INSERT INTO open_code_session_runtime_binding(
              external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
              internal_mcp_server,created_at)
            VALUES(#{externalSessionId},#{runtimeGenerationId},#{ownershipMode},#{endpointFingerprint},
              #{internalMcpServer},#{createdAt})
            """)
    int insertOpenCodeSessionRuntimeBinding(OpenCodeSessionRuntimeBindingRow row);

    @Select("SELECT * FROM open_code_session_runtime_binding WHERE external_session_id=#{externalSessionId}")
    Optional<OpenCodeSessionRuntimeBindingRow> findOpenCodeSessionRuntimeBinding(String externalSessionId);

    @Insert("""
            INSERT INTO ai_candidate_submission_run(
              id,designer_session_id,task_decomposition_id,loop_spec_compilation_id,candidate_kind,workflow_step,
              source_revision,owner_version,submission_channel,contract_version,runtime_generation_id,
              external_session_id,state,max_attempts,
              attempts_used,terminal_attempt_id,created_at,updated_at,version)
            VALUES(#{id},#{designerSessionId},#{taskDecompositionId},#{loopSpecCompilationId},#{candidateKind},
              #{workflowStep},#{sourceRevision},#{ownerVersion},#{submissionChannel},#{contractVersion},
              #{runtimeGenerationId},#{externalSessionId},#{state},#{maxAttempts},#{attemptsUsed},
              #{terminalAttemptId},#{createdAt},#{updatedAt},#{version})
            """)
    int insertCandidateSubmissionRun(CandidateSubmissionRunRow row);

    @Select("SELECT * FROM ai_candidate_submission_run WHERE id=#{id}")
    Optional<CandidateSubmissionRunRow> findCandidateSubmissionRun(String id);

    @Update("""
            UPDATE ai_candidate_submission_run SET state=#{state},attempts_used=#{attemptsUsed},
              terminal_attempt_id=#{terminalAttemptId},updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateCandidateSubmissionRun(CandidateSubmissionRunRow row);

    @Insert("""
            INSERT INTO ai_candidate_submission_attempt(
              id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
              canonical_result_sha256,created_at)
            VALUES(#{id},#{runId},#{ordinal},#{idempotencyKey},#{requestSha256},#{outcome},#{retryable},
              #{problemsJson},#{responseJson},#{canonicalResultSha256},#{createdAt})
            """)
    int insertCandidateSubmissionAttempt(CandidateSubmissionAttemptRow row);

    @Select("""
            SELECT * FROM ai_candidate_submission_attempt
            WHERE run_id=#{runId} AND idempotency_key=#{idempotencyKey}
            """)
    Optional<CandidateSubmissionAttemptRow> findCandidateSubmissionAttemptByKey(
            @Param("runId") String runId, @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM ai_candidate_submission_attempt WHERE run_id=#{runId} ORDER BY ordinal")
    List<CandidateSubmissionAttemptRow> listCandidateSubmissionAttempts(String runId);

    @Select("SELECT COUNT(*) FROM ai_candidate_submission_run WHERE task_decomposition_id=#{taskDecompositionId}")
    int countCandidateSubmissionRunsForDecomposition(String taskDecompositionId);

    @Select("""
            SELECT COUNT(*) FROM ai_candidate_submission_attempt attempt
            JOIN ai_candidate_submission_run run ON run.id=attempt.run_id
            WHERE run.task_decomposition_id=#{taskDecompositionId}
            """)
    int countCandidateSubmissionAttemptsForDecomposition(String taskDecompositionId);

    @Select("SELECT COUNT(*) FROM ai_candidate_submission_run WHERE loop_spec_compilation_id=#{compilationId}")
    int countCandidateSubmissionRunsForCompilation(String compilationId);

    @Select("""
            SELECT COUNT(*) FROM ai_candidate_submission_attempt attempt
            JOIN ai_candidate_submission_run run ON run.id=attempt.run_id
            WHERE run.loop_spec_compilation_id=#{compilationId}
            """)
    int countCandidateSubmissionAttemptsForCompilation(String compilationId);
}
