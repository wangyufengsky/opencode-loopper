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
            INSERT INTO ai_candidate_prompt_dispatch(
              id,run_id,internal_launch_id,candidate_launch_id,dispatch_kind,source_attempt_ordinal,
              external_session_id,runtime_generation_id,message_id,
              request_json,request_sha256,state,
              model_call_consumed,model_call_consumed_at,claim_owner,claim_token,claim_expires_at,fence,
              dispatch_attempted,dispatch_started_at,acknowledged,acked_at,
              termination_proof,termination_proof_at,last_error_code,last_error_detail,
              created_at,updated_at,version)
            VALUES(#{id},#{runId},#{internalLaunchId},#{candidateLaunchId},#{dispatchKind},#{sourceAttemptOrdinal},
              #{externalSessionId},#{runtimeGenerationId},#{messageId},#{requestJson},#{requestSha256},#{state},
              #{modelCallConsumed},#{modelCallConsumedAt},#{claimOwner},#{claimToken},#{claimExpiresAt},#{fence},
              #{dispatchAttempted},#{dispatchStartedAt},#{acknowledged},#{ackedAt},
              #{terminationProof},#{terminationProofAt},#{lastErrorCode},#{lastErrorDetail},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertCandidatePromptDispatch(CandidatePromptDispatchRow row);

    @Select("SELECT * FROM ai_candidate_prompt_dispatch WHERE id=#{id}")
    Optional<CandidatePromptDispatchRow> findCandidatePromptDispatch(String id);

    @Select("SELECT * FROM ai_candidate_prompt_dispatch WHERE run_id=#{runId} AND dispatch_kind='INITIAL'")
    Optional<CandidatePromptDispatchRow> findInitialCandidatePromptDispatch(String runId);

    @Select("""
            SELECT * FROM ai_candidate_prompt_dispatch
            WHERE run_id=#{runId} AND dispatch_kind='CORRECTION'
              AND source_attempt_ordinal=#{attemptOrdinal}
            """)
    Optional<CandidatePromptDispatchRow> findCorrectionCandidatePromptDispatch(
            @Param("runId") String runId, @Param("attemptOrdinal") int attemptOrdinal);

    @Select("SELECT * FROM ai_candidate_prompt_dispatch WHERE run_id=#{runId} ORDER BY created_at,id")
    List<CandidatePromptDispatchRow> listCandidatePromptDispatchesForRun(String runId);

    @Select("""
            SELECT * FROM ai_candidate_prompt_dispatch
            WHERE run_id=#{runId} AND state NOT IN ('STOPPED','CANCELLED') ORDER BY updated_at,id
            """)
    List<CandidatePromptDispatchRow> listActiveCandidatePromptDispatchesForRun(String runId);

    @Select("""
            SELECT dispatch.* FROM ai_candidate_prompt_dispatch dispatch
            JOIN ai_candidate_submission_run run ON run.id=dispatch.run_id
            WHERE run.designer_session_id=#{designerSessionId}
            ORDER BY dispatch.updated_at,dispatch.id
            """)
    List<CandidatePromptDispatchRow> listCandidatePromptDispatchesForDesigner(String designerSessionId);

    @Update("""
            UPDATE ai_candidate_prompt_dispatch SET state=#{state},
              model_call_consumed=#{modelCallConsumed},model_call_consumed_at=#{modelCallConsumedAt},
              claim_owner=#{claimOwner},claim_token=#{claimToken},claim_expires_at=#{claimExpiresAt},
              fence=#{fence},dispatch_attempted=#{dispatchAttempted},
              dispatch_started_at=#{dispatchStartedAt},acknowledged=#{acknowledged},acked_at=#{ackedAt},
              termination_proof=#{terminationProof},termination_proof_at=#{terminationProofAt},
              last_error_code=#{lastErrorCode},last_error_detail=#{lastErrorDetail},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateCandidatePromptDispatch(CandidatePromptDispatchRow row);

    @Insert("""
            INSERT INTO acceptance_candidate_handoff_cleanup_remote(
              handoff_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
              directory_sha256,title_sha256,state,termination_proof,proof_at,
              stop_claim_owner,stop_claim_token,stop_claim_expires_at,stop_fence,
              last_error_code,last_error_detail,created_at,updated_at,version)
            VALUES(#{handoffId},#{externalSessionId},#{runtimeGenerationId},#{endpointFingerprint},
              #{directorySha256},#{titleSha256},#{state},#{terminationProof},#{proofAt},
              #{stopClaimOwner},#{stopClaimToken},#{stopClaimExpiresAt},#{stopFence},#{lastErrorCode},
              #{lastErrorDetail},#{createdAt},#{updatedAt},#{version})
            """)
    int insertAcceptanceCandidateHandoffCleanupRemote(AcceptanceCandidateHandoffCleanupRemoteRow row);

    @Select("""
            SELECT * FROM acceptance_candidate_handoff_cleanup_remote
            WHERE handoff_id=#{handoffId} ORDER BY external_session_id
            """)
    List<AcceptanceCandidateHandoffCleanupRemoteRow> listAcceptanceCandidateHandoffCleanupRemotes(
            String handoffId);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
              JOIN acceptance_candidate_legacy_handoff handoff ON handoff.id=cleanup.handoff_id
              WHERE handoff.designer_session_id=#{designerSessionId} AND cleanup.state<>'STOPPED')
            """)
    boolean existsUnstoppedAcceptanceCandidateHandoffCleanupForDesigner(String designerSessionId);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
              JOIN acceptance_candidate_legacy_handoff handoff ON handoff.id=cleanup.handoff_id
              JOIN designer_session designer ON designer.id=handoff.designer_session_id
              WHERE designer.task_id=#{taskId} AND cleanup.state<>'STOPPED')
            """)
    boolean existsUnstoppedAcceptanceCandidateHandoffCleanupForTask(String taskId);

    @Update("""
            UPDATE acceptance_candidate_handoff_cleanup_remote SET state=#{state},
              stop_claim_owner=#{stopClaimOwner},stop_claim_token=#{stopClaimToken},
              stop_claim_expires_at=#{stopClaimExpiresAt},stop_fence=#{stopFence},
              last_error_code=NULL,last_error_detail=NULL,
              updated_at=#{updatedAt},version=version+1
            WHERE handoff_id=#{handoffId} AND external_session_id=#{externalSessionId} AND version=#{version}
              AND state=#{expectedState} AND termination_proof IS NULL
              AND (stop_claim_owner IS NULL OR stop_claim_expires_at<=#{claimedAt})
            """)
    int claimAcceptanceCandidateHandoffCleanupRemote(
            @Param("handoffId") String handoffId, @Param("externalSessionId") String externalSessionId,
            @Param("version") long version, @Param("expectedState") String expectedState,
            @Param("state") String state, @Param("stopClaimOwner") String stopClaimOwner,
            @Param("stopClaimToken") String stopClaimToken,
            @Param("stopClaimExpiresAt") String stopClaimExpiresAt,
            @Param("stopFence") long stopFence, @Param("claimedAt") String claimedAt,
            @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE acceptance_candidate_handoff_cleanup_remote SET state=#{row.state},
              termination_proof=#{row.terminationProof},proof_at=#{row.proofAt},
              stop_claim_owner=#{row.stopClaimOwner},stop_claim_token=#{row.stopClaimToken},
              stop_claim_expires_at=#{row.stopClaimExpiresAt},stop_fence=#{row.stopFence},
              last_error_code=#{row.lastErrorCode},
              last_error_detail=#{row.lastErrorDetail},updated_at=#{row.updatedAt},version=version+1
            WHERE handoff_id=#{row.handoffId} AND external_session_id=#{row.externalSessionId}
              AND version=#{row.version} AND state='STOPPING'
              AND stop_claim_owner=#{claimOwner} AND stop_claim_token=#{claimToken}
              AND stop_fence=#{claimFence}
            """)
    int updateAcceptanceCandidateHandoffCleanupRemoteAsClaimHolder(
            @Param("row") AcceptanceCandidateHandoffCleanupRemoteRow row,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimFence") long claimFence);

    @Insert("""
            INSERT INTO acceptance_candidate_legacy_handoff(
              id,compilation_id,designer_session_id,work_package_id,source_design_revision,
              source_design_message_id,source_draft_version,source_design_sha256,contract_version,state,
              prepared_owner_version,current_owner_version,old_external_session_id,old_runtime_generation_id,
              old_endpoint_fingerprint,old_external_state,old_termination_proof,old_proof_at,
              legacy_creation_key,successor_exact_title,successor_canonical_directory,
              successor_runtime_generation_id,successor_managed,successor_internal_mcp_server,
              successor_endpoint_fingerprint,successor_model_provider_id,successor_model_id,successor_thinking,
              successor_profile,successor_permission_policy_json,successor_permission_policy_digest,
              successor_create_request_sha256,
              successor_creation_credential,successor_attestation_type,
              create_claim_owner,create_claim_token,create_claim_expires_at,create_fence,
              create_dispatch_attempted,create_dispatch_started_at,
              legacy_external_session_id,legacy_runtime_generation_id,
              legacy_endpoint_fingerprint,legacy_external_state,legacy_termination_proof,legacy_proof_at,
              legacy_prompt_message_id,legacy_prompt_sha256,
              legacy_prompt_dispatch_attempted,legacy_prompt_dispatch_started_at,
              prompt_claim_owner,prompt_claim_token,
              prompt_claim_expires_at,prompt_fence,model_call_consumed,model_call_consumed_at,
              failure_phase,last_error_code,last_error_detail,created_at,updated_at,version)
            VALUES(#{id},#{compilationId},#{designerSessionId},#{workPackageId},#{sourceDesignRevision},
              #{sourceDesignMessageId},#{sourceDraftVersion},#{sourceDesignSha256},#{contractVersion},#{state},
              #{preparedOwnerVersion},#{currentOwnerVersion},#{oldExternalSessionId},#{oldRuntimeGenerationId},
              #{oldEndpointFingerprint},#{oldExternalState},#{oldTerminationProof},#{oldProofAt},
              #{legacyCreationKey},#{successorExactTitle},#{successorCanonicalDirectory},
              #{successorRuntimeGenerationId},#{successorManaged},#{successorInternalMcpServer},
              #{successorEndpointFingerprint},#{successorModelProviderId},#{successorModelId},#{successorThinking},
              #{successorProfile},#{successorPermissionPolicyJson},#{successorPermissionPolicyDigest},
              #{successorCreateRequestSha256},
              #{successorCreationCredential},#{successorAttestationType},
              #{createClaimOwner},#{createClaimToken},#{createClaimExpiresAt},#{createFence},
              #{createDispatchAttempted},#{createDispatchStartedAt},
              #{legacyExternalSessionId},#{legacyRuntimeGenerationId},
              #{legacyEndpointFingerprint},#{legacyExternalState},#{legacyTerminationProof},#{legacyProofAt},
              #{legacyPromptMessageId},#{legacyPromptSha256},
              #{legacyPromptDispatchAttempted},#{legacyPromptDispatchStartedAt},
              #{promptClaimOwner},#{promptClaimToken},
              #{promptClaimExpiresAt},#{promptFence},#{modelCallConsumed},#{modelCallConsumedAt},
              #{failurePhase},#{lastErrorCode},#{lastErrorDetail},#{createdAt},#{updatedAt},#{version})
            """)
    int insertAcceptanceCandidateLegacyHandoff(AcceptanceCandidateLegacyHandoffRow row);

    @Select("SELECT * FROM acceptance_candidate_legacy_handoff WHERE id=#{id}")
    Optional<AcceptanceCandidateLegacyHandoffRow> findAcceptanceCandidateLegacyHandoff(String id);

    @Select("SELECT * FROM acceptance_candidate_legacy_handoff WHERE compilation_id=#{compilationId}")
    Optional<AcceptanceCandidateLegacyHandoffRow> findAcceptanceCandidateLegacyHandoffForCompilation(
            String compilationId);

    @Select("""
            SELECT * FROM acceptance_candidate_legacy_handoff
            WHERE state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
            ORDER BY updated_at,id
            """)
    List<AcceptanceCandidateLegacyHandoffRow> listActiveAcceptanceCandidateLegacyHandoffs();

    @Select("""
            SELECT * FROM acceptance_candidate_legacy_handoff
            WHERE designer_session_id=#{designerSessionId}
              AND state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
            ORDER BY updated_at,id
            """)
    List<AcceptanceCandidateLegacyHandoffRow> listAcceptanceCandidateHandoffsForDesigner(
            String designerSessionId);

    @Select("""
            SELECT * FROM acceptance_candidate_legacy_handoff
            WHERE designer_session_id=#{designerSessionId}
            ORDER BY updated_at,id
            """)
    List<AcceptanceCandidateLegacyHandoffRow> listAllAcceptanceCandidateHandoffsForDesigner(
            String designerSessionId);

    @Update("""
            UPDATE acceptance_candidate_legacy_handoff SET state=#{state},
              current_owner_version=#{currentOwnerVersion},old_external_state=#{oldExternalState},
              old_termination_proof=#{oldTerminationProof},old_proof_at=#{oldProofAt},
              create_claim_owner=#{createClaimOwner},create_claim_token=#{createClaimToken},
              create_claim_expires_at=#{createClaimExpiresAt},create_fence=#{createFence},
              create_dispatch_attempted=#{createDispatchAttempted},
              create_dispatch_started_at=#{createDispatchStartedAt},
              legacy_external_session_id=#{legacyExternalSessionId},
              legacy_runtime_generation_id=#{legacyRuntimeGenerationId},
              legacy_endpoint_fingerprint=#{legacyEndpointFingerprint},
              legacy_external_state=#{legacyExternalState},legacy_termination_proof=#{legacyTerminationProof},
              legacy_proof_at=#{legacyProofAt},legacy_prompt_sha256=#{legacyPromptSha256},
              legacy_prompt_dispatch_attempted=#{legacyPromptDispatchAttempted},
              legacy_prompt_dispatch_started_at=#{legacyPromptDispatchStartedAt},
              prompt_claim_owner=#{promptClaimOwner},prompt_claim_token=#{promptClaimToken},
              prompt_claim_expires_at=#{promptClaimExpiresAt},prompt_fence=#{promptFence},
              model_call_consumed=#{modelCallConsumed},model_call_consumed_at=#{modelCallConsumedAt},
              failure_phase=#{failurePhase},last_error_code=#{lastErrorCode},last_error_detail=#{lastErrorDetail},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateAcceptanceCandidateLegacyHandoff(AcceptanceCandidateLegacyHandoffRow row);

    @Update("""
            UPDATE acceptance_candidate_legacy_handoff SET state='SETTLED',
              current_owner_version=#{row.currentOwnerVersion},
              legacy_external_state=#{row.legacyExternalState},
              legacy_termination_proof=#{row.legacyTerminationProof},legacy_proof_at=#{row.legacyProofAt},
              create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
              prompt_claim_owner=NULL,prompt_claim_token=NULL,prompt_claim_expires_at=NULL,
              failure_phase=NULL,last_error_code=NULL,last_error_detail=NULL,
              updated_at=#{row.updatedAt},version=version+1
            WHERE id=#{row.id} AND compilation_id=#{row.compilationId}
              AND designer_session_id=#{row.designerSessionId}
              AND work_package_id=#{row.workPackageId}
              AND source_design_revision=#{row.sourceDesignRevision}
              AND source_design_message_id=#{row.sourceDesignMessageId}
              AND source_draft_version=#{row.sourceDraftVersion}
              AND contract_version=#{row.contractVersion} AND state='HANDED_OFF'
              AND current_owner_version=#{expectedOwnerVersion}
              AND legacy_external_session_id=#{row.legacyExternalSessionId}
              AND legacy_runtime_generation_id=#{row.legacyRuntimeGenerationId}
              AND legacy_termination_proof IS NULL AND version=#{row.version}
              AND NOT EXISTS (
                SELECT 1 FROM acceptance_candidate_handoff_cleanup_remote cleanup
                WHERE cleanup.handoff_id=#{row.id} AND cleanup.state<>'STOPPED')
              AND EXISTS (
                SELECT 1 FROM loop_spec_compilation compilation
                JOIN design_work_package work_package
                  ON work_package.designer_session_id=#{row.designerSessionId}
                 AND work_package.package_id=#{row.workPackageId}
                 AND work_package.design_revision=#{row.sourceDesignRevision}
                 AND work_package.design_message_id=#{row.sourceDesignMessageId}
                 AND work_package.id=(
                   SELECT current_work_package.id FROM design_work_package current_work_package
                   WHERE current_work_package.designer_session_id=#{row.designerSessionId}
                     AND current_work_package.package_id=#{row.workPackageId}
                   ORDER BY current_work_package.plan_revision DESC,
                     current_work_package.created_at DESC LIMIT 1)
                JOIN design_requirement_revision revision
                  ON revision.id=work_package.requirement_revision_id
                 AND revision.designer_session_id=#{row.designerSessionId}
                 AND revision.source_draft_version=#{row.sourceDraftVersion}
                WHERE compilation.id=#{row.compilationId}
                  AND compilation.designer_session_id=#{row.designerSessionId}
                  AND compilation.work_package_id=#{row.workPackageId}
                  AND compilation.design_revision=#{row.sourceDesignRevision}
                  AND compilation.source_design_message_id=#{row.sourceDesignMessageId}
                  AND compilation.source_draft_version=#{row.sourceDraftVersion}
                  AND compilation.state='RUNNING'
                  AND compilation.external_session_id=#{row.legacyExternalSessionId}
                  AND compilation.external_session_state=#{row.legacyTerminationProof}
                  AND compilation.version=#{row.currentOwnerVersion})
            """)
    int settleAcceptanceCandidateLegacyHandoff(
            @Param("row") AcceptanceCandidateLegacyHandoffRow row,
            @Param("expectedOwnerVersion") long expectedOwnerVersion);

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
              id,designer_session_id,task_id,project_id,owner_type,owner_id,candidate_kind,workflow_step,
              source_revision,owner_version,submission_channel,contract_version,runtime_generation_id,
              external_session_id,state,max_attempts,
              attempts_used,terminal_attempt_id,created_at,updated_at,version,close_reason)
            VALUES(#{id},#{designerSessionId},#{taskId},#{projectId},#{ownerType},#{ownerId},#{candidateKind},
              #{workflowStep},#{sourceRevision},#{ownerVersion},#{submissionChannel},#{contractVersion},
              #{runtimeGenerationId},#{externalSessionId},#{state},#{maxAttempts},#{attemptsUsed},
              #{terminalAttemptId},#{createdAt},#{updatedAt},#{version},#{closeReason})
            """)
    int insertCandidateSubmissionRun(CandidateSubmissionRunRow row);

    @Select("SELECT * FROM ai_candidate_submission_run WHERE id=#{id}")
    Optional<CandidateSubmissionRunRow> findCandidateSubmissionRun(String id);

    @Select("""
            SELECT * FROM ai_candidate_submission_run
            WHERE designer_session_id=#{designerSessionId} AND state='OPEN'
            ORDER BY created_at,id
            """)
    List<CandidateSubmissionRunRow> listOpenCandidateSubmissionRunsForDesigner(String designerSessionId);

    @Update("""
            UPDATE ai_candidate_submission_run SET state=#{state},attempts_used=#{attemptsUsed},
              terminal_attempt_id=#{terminalAttemptId},updated_at=#{updatedAt},close_reason=#{closeReason},
              version=version+1
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

    @Select("""
            SELECT COUNT(*) FROM ai_candidate_submission_run
            WHERE owner_type='TASK_DECOMPOSITION' AND owner_id=#{taskDecompositionId}
            """)
    int countCandidateSubmissionRunsForDecomposition(String taskDecompositionId);

    @Select("""
            SELECT COUNT(*) FROM ai_candidate_submission_attempt attempt
            JOIN ai_candidate_submission_run run ON run.id=attempt.run_id
            WHERE run.owner_type='TASK_DECOMPOSITION' AND run.owner_id=#{taskDecompositionId}
            """)
    int countCandidateSubmissionAttemptsForDecomposition(String taskDecompositionId);

    @Select("""
            SELECT COUNT(*) FROM ai_candidate_submission_run
            WHERE owner_type='LOOP_SPEC_COMPILATION' AND owner_id=#{compilationId}
            """)
    int countCandidateSubmissionRunsForCompilation(String compilationId);

    @Select("""
            SELECT COUNT(*) FROM ai_candidate_submission_attempt attempt
            JOIN ai_candidate_submission_run run ON run.id=attempt.run_id
            WHERE run.owner_type='LOOP_SPEC_COMPILATION' AND run.owner_id=#{compilationId}
            """)
    int countCandidateSubmissionAttemptsForCompilation(String compilationId);

    @Select("""
            SELECT * FROM ai_candidate_submission_run
            WHERE owner_type='DESIGN_WORK_PACKAGE' AND owner_id=#{designWorkPackageId}
              AND source_revision=#{sourceRevision}
            ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    Optional<CandidateSubmissionRunRow> findLatestCandidateSubmissionRunForWorkPackage(
            @Param("designWorkPackageId") String designWorkPackageId,
            @Param("sourceRevision") long sourceRevision);

    @Select("SELECT COUNT(*) FROM ai_candidate_submission_attempt WHERE run_id=#{runId}")
    int countCandidateSubmissionAttemptsForRun(String runId);

    @Insert("""
            INSERT INTO package_design_candidate_accepted_result(
              candidate_run_id,design_work_package_id,source_revision,owner_version,contract_version,
              canonical_candidate_json,canonical_markdown,compiled_result_json,canonical_result_sha256,
              settled_compilation_id,created_at,updated_at,version)
            VALUES(#{candidateRunId},#{designWorkPackageId},#{sourceRevision},#{ownerVersion},#{contractVersion},
              #{canonicalCandidateJson},#{canonicalMarkdown},#{compiledResultJson},#{canonicalResultSha256},
              #{settledCompilationId},#{createdAt},#{updatedAt},#{version})
            """)
    int insertPackageDesignAcceptedResult(PackageDesignAcceptedResultRow row);

    @Select("SELECT * FROM package_design_candidate_accepted_result WHERE candidate_run_id=#{candidateRunId}")
    Optional<PackageDesignAcceptedResultRow> findPackageDesignAcceptedResult(String candidateRunId);

    @Select("""
            SELECT * FROM package_design_candidate_accepted_result
            WHERE design_work_package_id=#{designWorkPackageId}
            ORDER BY created_at DESC,candidate_run_id DESC LIMIT 1
            """)
    Optional<PackageDesignAcceptedResultRow> findLatestPackageDesignAcceptedResultForWorkPackage(
            String designWorkPackageId);

    @Select("""
            SELECT * FROM package_design_candidate_accepted_result
            WHERE settled_compilation_id IS NULL
            ORDER BY created_at,candidate_run_id
            """)
    List<PackageDesignAcceptedResultRow> listUnsettledPackageDesignAcceptedResults();

    @Update("""
            UPDATE package_design_candidate_accepted_result
            SET settled_compilation_id=#{settledCompilationId},updated_at=#{updatedAt},version=version+1
            WHERE candidate_run_id=#{candidateRunId} AND version=#{expectedVersion}
              AND settled_compilation_id IS NULL
              AND #{settledCompilationId} IS NOT NULL AND length(#{settledCompilationId}) > 0
            """)
    int settlePackageDesignAcceptedResult(
            @Param("candidateRunId") String candidateRunId,
            @Param("expectedVersion") long expectedVersion,
            @Param("settledCompilationId") String settledCompilationId,
            @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO rolling_package_plan_candidate_accepted_result(
              candidate_run_id,task_package_plan_revision_id,source_revision,owner_version,contract_version,
              canonical_candidate_json,canonical_plan_json,impact_json,canonical_result_sha256,
              settled_plan_revision_id,created_at,updated_at,version)
            VALUES(#{candidateRunId},#{taskPackagePlanRevisionId},#{sourceRevision},#{ownerVersion},
              #{contractVersion},#{canonicalCandidateJson},#{canonicalPlanJson},#{impactJson},
              #{canonicalResultSha256},#{settledPlanRevisionId},#{createdAt},#{updatedAt},#{version})
            """)
    int insertRollingPackagePlanAcceptedResult(RollingPackagePlanAcceptedResultRow row);

    @Select("""
            SELECT * FROM rolling_package_plan_candidate_accepted_result
            WHERE candidate_run_id=#{candidateRunId}
            """)
    Optional<RollingPackagePlanAcceptedResultRow> findRollingPackagePlanAcceptedResult(
            String candidateRunId);

    @Select("""
            SELECT * FROM rolling_package_plan_candidate_accepted_result
            WHERE settled_plan_revision_id IS NULL
            ORDER BY created_at,candidate_run_id
            """)
    List<RollingPackagePlanAcceptedResultRow> listUnsettledRollingPackagePlanAcceptedResults();

    @Update("""
            UPDATE rolling_package_plan_candidate_accepted_result
            SET settled_plan_revision_id=#{settledPlanRevisionId},updated_at=#{updatedAt},version=version+1
            WHERE candidate_run_id=#{candidateRunId} AND version=#{expectedVersion}
              AND settled_plan_revision_id IS NULL
              AND task_package_plan_revision_id=#{settledPlanRevisionId}
              AND #{settledPlanRevisionId} IS NOT NULL AND length(#{settledPlanRevisionId}) > 0
            """)
    int settleRollingPackagePlanAcceptedResult(
            @Param("candidateRunId") String candidateRunId,
            @Param("expectedVersion") long expectedVersion,
            @Param("settledPlanRevisionId") String settledPlanRevisionId,
            @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO reviewer_report_candidate_source_snapshot(
              candidate_run_id,analysis_report_id,source_revision,prepared_owner_version,contract_version,
              canonical_source_manifest_json,source_manifest_sha256,created_at)
            VALUES(#{candidateRunId},#{analysisReportId},#{sourceRevision},#{preparedOwnerVersion},
              #{contractVersion},#{canonicalSourceManifestJson},#{sourceManifestSha256},#{createdAt})
            """)
    int insertReviewerReportSourceSnapshot(ReviewerReportSourceSnapshotRow row);

    @Select("""
            SELECT * FROM reviewer_report_candidate_source_snapshot
            WHERE candidate_run_id=#{candidateRunId}
            """)
    Optional<ReviewerReportSourceSnapshotRow> findReviewerReportSourceSnapshot(String candidateRunId);

    @Insert("""
            INSERT INTO reviewer_report_candidate_accepted_result(
              candidate_run_id,analysis_report_id,source_revision,owner_version,contract_version,
              canonical_candidate_json,canonical_findings_json,markdown,evidence_json,
              content_sha256,source_snapshot_sha256,candidate_payload_sha256,canonical_result_sha256,
              settled_analysis_report_id,created_at,updated_at,version)
            VALUES(#{candidateRunId},#{analysisReportId},#{sourceRevision},#{ownerVersion},#{contractVersion},
              #{canonicalCandidateJson},#{canonicalFindingsJson},#{markdown},#{evidenceJson},
              #{contentSha256},#{sourceSnapshotSha256},#{candidatePayloadSha256},#{canonicalResultSha256},
              #{settledAnalysisReportId},#{createdAt},#{updatedAt},#{version})
            """)
    int insertReviewerReportAcceptedResult(ReviewerReportAcceptedResultRow row);

    @Select("""
            SELECT * FROM reviewer_report_candidate_accepted_result
            WHERE candidate_run_id=#{candidateRunId}
            """)
    Optional<ReviewerReportAcceptedResultRow> findReviewerReportAcceptedResult(String candidateRunId);

    @Select("""
            SELECT * FROM reviewer_report_candidate_accepted_result
            WHERE settled_analysis_report_id IS NULL
            ORDER BY created_at,candidate_run_id
            """)
    List<ReviewerReportAcceptedResultRow> listUnsettledReviewerReportAcceptedResults();

    @Update("""
            UPDATE reviewer_report_candidate_accepted_result
            SET settled_analysis_report_id=#{settledAnalysisReportId},updated_at=#{updatedAt},version=version+1
            WHERE candidate_run_id=#{candidateRunId} AND version=#{expectedVersion}
              AND settled_analysis_report_id IS NULL
              AND analysis_report_id=#{settledAnalysisReportId}
              AND #{settledAnalysisReportId} IS NOT NULL AND length(#{settledAnalysisReportId}) > 0
            """)
    int settleReviewerReportAcceptedResult(
            @Param("candidateRunId") String candidateRunId,
            @Param("expectedVersion") long expectedVersion,
            @Param("settledAnalysisReportId") String settledAnalysisReportId,
            @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO project_convention_candidate_source_snapshot(
              candidate_run_id,project_id,project_convention_draft_id,source_revision,
              prepared_owner_version,contract_version,source_exists,source_agents_sha256,
              source_content,source_content_sha256,project_stack_profile_id,stack_fingerprint,
              canonical_evidence_json,evidence_sha256,created_at)
            VALUES(#{candidateRunId},#{projectId},#{projectConventionDraftId},#{sourceRevision},
              #{preparedOwnerVersion},#{contractVersion},#{sourceExists},#{sourceAgentsSha256},
              #{sourceContent},#{sourceContentSha256},#{projectStackProfileId},#{stackFingerprint},
              #{canonicalEvidenceJson},#{evidenceSha256},#{createdAt})
            """)
    int insertProjectConventionCandidateSourceSnapshot(
            ProjectConventionCandidateSourceSnapshotRow row);

    @Select("""
            SELECT * FROM project_convention_candidate_source_snapshot
            WHERE candidate_run_id=#{candidateRunId}
            """)
    Optional<ProjectConventionCandidateSourceSnapshotRow>
            findProjectConventionCandidateSourceSnapshot(String candidateRunId);

    @Insert("""
            INSERT INTO project_convention_candidate_accepted_result(
              candidate_run_id,project_id,project_convention_draft_id,source_revision,owner_version,
              contract_version,canonical_candidate_json,candidate_payload_sha256,
              canonical_result_sha256,proposed_content,proposed_content_sha256,settled_draft_id,
              created_at,updated_at,version)
            VALUES(#{candidateRunId},#{projectId},#{projectConventionDraftId},#{sourceRevision},
              #{ownerVersion},#{contractVersion},#{canonicalCandidateJson},#{candidatePayloadSha256},
              #{canonicalResultSha256},#{proposedContent},#{proposedContentSha256},#{settledDraftId},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertProjectConventionCandidateAcceptedResult(
            ProjectConventionCandidateAcceptedResultRow row);

    @Select("""
            SELECT * FROM project_convention_candidate_accepted_result
            WHERE candidate_run_id=#{candidateRunId}
            """)
    Optional<ProjectConventionCandidateAcceptedResultRow>
            findProjectConventionCandidateAcceptedResult(String candidateRunId);

    @Select("""
            SELECT * FROM project_convention_candidate_accepted_result
            WHERE settled_draft_id IS NULL
            ORDER BY created_at,candidate_run_id
            """)
    List<ProjectConventionCandidateAcceptedResultRow>
            listUnsettledProjectConventionCandidateAcceptedResults();

    @Update("""
            UPDATE project_convention_candidate_accepted_result
            SET settled_draft_id=#{settledDraftId},updated_at=#{updatedAt},version=version+1
            WHERE candidate_run_id=#{candidateRunId} AND version=#{expectedVersion}
              AND settled_draft_id IS NULL
              AND project_convention_draft_id=#{settledDraftId}
              AND #{settledDraftId} IS NOT NULL AND length(#{settledDraftId})>0
            """)
    int settleProjectConventionCandidateAcceptedResult(
            @Param("candidateRunId") String candidateRunId,
            @Param("expectedVersion") long expectedVersion,
            @Param("settledDraftId") String settledDraftId,
            @Param("updatedAt") String updatedAt);
}
