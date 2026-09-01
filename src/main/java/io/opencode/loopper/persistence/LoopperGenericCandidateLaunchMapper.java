package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Narrow persistence primitives for the V57 generic candidate internal-launch saga. */
public interface LoopperGenericCandidateLaunchMapper {
    @Insert("""
            INSERT INTO ai_candidate_internal_launch(
              id,candidate_run_id,candidate_kind,designer_session_id,task_id,project_id,
              owner_type,owner_id,analysis_report_id,project_convention_draft_id,judge_run_id,
              workflow_step,source_revision,contract_version,max_attempts,state,
              prepared_owner_version,settled_owner_version,settled_at,
              exact_title,canonical_directory,runtime_generation_id,managed,internal_mcp_server,
              endpoint_fingerprint,model_provider_id,model_id,thinking,profile,
              permission_policy_json,permission_policy_digest,create_request_sha256,
              creation_credential,attestation_type,create_claim_owner,create_claim_token,
              create_claim_expires_at,create_fence,create_dispatch_attempted,create_dispatch_started_at,
              external_session_id,external_attested_at,termination_proof,proof_at,failure_phase,
              last_error_code,last_error_detail,created_at,updated_at,version)
            VALUES(#{id},#{candidateRunId},#{candidateKind},#{designerSessionId},#{taskId},#{projectId},
              #{ownerType},#{ownerId},#{analysisReportId},#{projectConventionDraftId},#{judgeRunId},
              #{workflowStep},#{sourceRevision},#{contractVersion},#{maxAttempts},#{state},
              #{preparedOwnerVersion},#{settledOwnerVersion},#{settledAt},
              #{exactTitle},#{canonicalDirectory},#{runtimeGenerationId},#{managed},#{internalMcpServer},
              #{endpointFingerprint},#{modelProviderId},#{modelId},#{thinking},#{profile},
              #{permissionPolicyJson},#{permissionPolicyDigest},#{createRequestSha256},
              #{creationCredential},#{attestationType},#{createClaimOwner},#{createClaimToken},
              #{createClaimExpiresAt},#{createFence},#{createDispatchAttempted},#{createDispatchStartedAt},
              #{externalSessionId},#{externalAttestedAt},#{terminationProof},#{proofAt},#{failurePhase},
              #{lastErrorCode},#{lastErrorDetail},#{createdAt},#{updatedAt},#{version})
            """)
    int insertGenericCandidateInternalLaunch(GenericCandidateInternalLaunchRow row);

    @Select("SELECT * FROM ai_candidate_internal_launch WHERE id=#{id}")
    Optional<GenericCandidateInternalLaunchRow> findGenericCandidateInternalLaunch(String id);

    @Select("SELECT * FROM ai_candidate_internal_launch WHERE candidate_run_id=#{runId}")
    Optional<GenericCandidateInternalLaunchRow> findGenericCandidateInternalLaunchForRun(String runId);

    @Select("SELECT * FROM ai_candidate_internal_launch WHERE analysis_report_id=#{reportId} ORDER BY created_at DESC,id LIMIT 1")
    Optional<GenericCandidateInternalLaunchRow> findGenericCandidateInternalLaunchForAnalysisReport(String reportId);

    @Select("""
            SELECT * FROM ai_candidate_internal_launch
            WHERE designer_session_id=#{designerSessionId}
            ORDER BY created_at,id
            """)
    List<GenericCandidateInternalLaunchRow> listGenericCandidateInternalLaunchesForDesigner(
            String designerSessionId);

    @Select("""
            SELECT * FROM ai_candidate_internal_launch
            WHERE owner_type=#{ownerType} AND owner_id=#{ownerId} AND workflow_step=#{workflowStep}
              AND state IN ('PREPARED','CREATING','CREATED','DISCONNECTED','STOPPING','SETTLED')
            ORDER BY updated_at DESC,id LIMIT 1
            """)
    Optional<GenericCandidateInternalLaunchRow> findActiveGenericCandidateInternalLaunchForOwner(
            @Param("ownerType") String ownerType, @Param("ownerId") String ownerId,
            @Param("workflowStep") String workflowStep);

    @Select("""
            SELECT * FROM ai_candidate_internal_launch
            WHERE state NOT IN ('SETTLED','COMPLETED','FAILED_STOPPED','CANCELLED','STALE')
            ORDER BY updated_at,id
            """)
    List<GenericCandidateInternalLaunchRow> listRecoverableGenericCandidateInternalLaunches();

    @Update("""
            UPDATE ai_candidate_internal_launch
            SET create_claim_owner=#{claimOwner},create_claim_token=#{claimToken},
              create_claim_expires_at=#{claimExpiresAt},create_fence=#{claimFence},
              last_error_code=NULL,last_error_detail=NULL,updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state=#{expectedState}
              AND settled_owner_version IS NULL AND termination_proof IS NULL
              AND (create_claim_owner IS NULL OR create_claim_expires_at<=#{claimedAt})
            """)
    int claimGenericCandidateInternalLaunchCreate(
            @Param("id") String id, @Param("version") long version,
            @Param("expectedState") String expectedState,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimExpiresAt") String claimExpiresAt, @Param("claimFence") long claimFence,
            @Param("claimedAt") String claimedAt, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE ai_candidate_internal_launch SET state='CREATING',create_dispatch_attempted=1,
              create_dispatch_started_at=#{dispatchStartedAt},updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state='PREPARED'
              AND create_dispatch_attempted=0 AND external_session_id IS NULL
              AND create_claim_owner=#{claimOwner} AND create_claim_token=#{claimToken}
              AND create_fence=#{claimFence} AND create_claim_expires_at>#{dispatchStartedAt}
            """)
    int markGenericCandidateInternalLaunchCreateDispatchStarted(
            @Param("id") String id, @Param("version") long version,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimFence") long claimFence, @Param("dispatchStartedAt") String dispatchStartedAt,
            @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE ai_candidate_internal_launch SET state=#{row.state},
              settled_owner_version=#{row.settledOwnerVersion},settled_at=#{row.settledAt},
              create_claim_owner=#{row.createClaimOwner},create_claim_token=#{row.createClaimToken},
              create_claim_expires_at=#{row.createClaimExpiresAt},create_fence=#{row.createFence},
              create_dispatch_attempted=#{row.createDispatchAttempted},
              create_dispatch_started_at=#{row.createDispatchStartedAt},
              external_session_id=#{row.externalSessionId},external_attested_at=#{row.externalAttestedAt},
              termination_proof=#{row.terminationProof},proof_at=#{row.proofAt},failure_phase=#{row.failurePhase},
              last_error_code=#{row.lastErrorCode},last_error_detail=#{row.lastErrorDetail},
              updated_at=#{row.updatedAt},version=version+1
            WHERE id=#{row.id} AND version=#{row.version}
              AND create_claim_owner=#{claimOwner} AND create_claim_token=#{claimToken}
              AND create_fence=#{claimFence}
            """)
    int updateGenericCandidateInternalLaunchAsClaimHolder(
            @Param("row") GenericCandidateInternalLaunchRow row,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimFence") long claimFence);

    @Update("""
            UPDATE analysis_report SET external_session_id=#{externalSessionId},
              external_session_state='CANDIDATE_PROMPT_PENDING',updated_at=#{updatedAt},version=version+1
            WHERE id=(SELECT analysis_report_id FROM ai_candidate_internal_launch WHERE id=#{launchId})
              AND state='RUNNING' AND version=#{preparedOwnerVersion} AND external_session_id IS NULL
              AND EXISTS(SELECT 1 FROM ai_candidate_internal_launch launch
                WHERE launch.id=#{launchId} AND launch.version=#{launchVersion} AND launch.state='CREATED'
                  AND launch.candidate_kind='REVIEWER_REPORT_V1'
                  AND launch.external_session_id=#{externalSessionId}
                  AND launch.prepared_owner_version=#{preparedOwnerVersion}
                  AND NOT EXISTS(SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
                    WHERE cleanup.launch_id=launch.id))
            """)
    int attachGenericReviewerOwner(
            @Param("launchId") String launchId, @Param("launchVersion") long launchVersion,
            @Param("preparedOwnerVersion") long preparedOwnerVersion,
            @Param("externalSessionId") String externalSessionId, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE project_convention_draft SET external_session_id=#{externalSessionId},
              external_session_state='CANDIDATE_PROMPT_PENDING',updated_at=#{updatedAt},version=version+1
            WHERE id=(SELECT project_convention_draft_id FROM ai_candidate_internal_launch WHERE id=#{launchId})
              AND state='RUNNING' AND version=#{preparedOwnerVersion} AND external_session_id IS NULL
              AND EXISTS(SELECT 1 FROM ai_candidate_internal_launch launch
                WHERE launch.id=#{launchId} AND launch.version=#{launchVersion} AND launch.state='CREATED'
                  AND launch.candidate_kind='PROJECT_CONVENTION_V1'
                  AND launch.external_session_id=#{externalSessionId}
                  AND launch.prepared_owner_version=#{preparedOwnerVersion}
                  AND NOT EXISTS(SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
                    WHERE cleanup.launch_id=launch.id))
            """)
    int attachGenericConventionOwner(
            @Param("launchId") String launchId, @Param("launchVersion") long launchVersion,
            @Param("preparedOwnerVersion") long preparedOwnerVersion,
            @Param("externalSessionId") String externalSessionId, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE judge_run SET external_session_id=#{externalSessionId},state='RUNNING',version=version+1
            WHERE id=(SELECT judge_run_id FROM ai_candidate_internal_launch WHERE id=#{launchId})
              AND state='CREATING' AND version=#{preparedOwnerVersion} AND external_session_id IS NULL
              AND EXISTS(SELECT 1 FROM ai_candidate_internal_launch launch
                WHERE launch.id=#{launchId} AND launch.version=#{launchVersion} AND launch.state='CREATED'
                  AND launch.candidate_kind='JUDGE_DECISION_V1'
                  AND launch.external_session_id=#{externalSessionId}
                  AND launch.prepared_owner_version=#{preparedOwnerVersion}
                  AND NOT EXISTS(SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
                    WHERE cleanup.launch_id=launch.id))
            """)
    int attachGenericJudgeOwner(
            @Param("launchId") String launchId, @Param("launchVersion") long launchVersion,
            @Param("preparedOwnerVersion") long preparedOwnerVersion,
            @Param("externalSessionId") String externalSessionId);

    @Update("""
            UPDATE ai_candidate_internal_launch SET state='SETTLED',
              settled_owner_version=#{settledOwnerVersion},settled_at=#{settledAt},
              create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
              failure_phase=NULL,last_error_code=NULL,last_error_detail=NULL,
              updated_at=#{settledAt},version=version+1
            WHERE id=#{launchId} AND version=#{launchVersion} AND state='CREATED'
              AND settled_owner_version IS NULL AND termination_proof IS NULL
              AND prepared_owner_version+1=#{settledOwnerVersion}
              AND NOT EXISTS(SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
                WHERE cleanup.launch_id=#{launchId})
              AND NOT EXISTS(SELECT 1 FROM ai_candidate_internal_termination_intent intent
                WHERE intent.launch_id=#{launchId} AND intent.state<>'COMPLETED')
            """)
    int settleGenericCandidateInternalLaunch(
            @Param("launchId") String launchId, @Param("launchVersion") long launchVersion,
            @Param("settledOwnerVersion") long settledOwnerVersion,
            @Param("settledAt") String settledAt);

    @Update("""
            UPDATE ai_candidate_internal_launch SET state='FAILED_STOPPED',
              create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
              failure_phase='REMOTE_STOP',last_error_code=#{errorCode},last_error_detail=#{errorDetail},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{launchId} AND version=#{launchVersion} AND state='STOPPING'
              AND settled_owner_version IS NULL AND termination_proof IS NULL
              AND EXISTS(SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
                WHERE cleanup.launch_id=#{launchId})
              AND NOT EXISTS(SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
                WHERE cleanup.launch_id=#{launchId} AND cleanup.state<>'STOPPED')
            """)
    int finishGenericCandidateInternalLaunchAfterCleanup(
            @Param("launchId") String launchId, @Param("launchVersion") long launchVersion,
            @Param("errorCode") String errorCode, @Param("errorDetail") String errorDetail,
            @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO ai_candidate_internal_launch_cleanup_remote(
              launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
              directory_sha256,title_sha256,purpose,state,termination_proof,proof_at,
              stop_claim_owner,stop_claim_token,stop_claim_expires_at,stop_fence,
              stop_dispatch_attempted,stop_dispatch_started_at,last_error_code,last_error_detail,
              created_at,updated_at,version)
            VALUES(#{launchId},#{externalSessionId},#{runtimeGenerationId},#{endpointFingerprint},
              #{directorySha256},#{titleSha256},#{purpose},#{state},#{terminationProof},#{proofAt},
              #{stopClaimOwner},#{stopClaimToken},#{stopClaimExpiresAt},#{stopFence},
              #{stopDispatchAttempted},#{stopDispatchStartedAt},#{lastErrorCode},#{lastErrorDetail},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertGenericCandidateInternalLaunchCleanupRemote(
            GenericCandidateInternalLaunchCleanupRemoteRow row);

    @Select("""
            SELECT * FROM ai_candidate_internal_launch_cleanup_remote
            WHERE launch_id=#{launchId} ORDER BY external_session_id
            """)
    List<GenericCandidateInternalLaunchCleanupRemoteRow>
            listGenericCandidateInternalLaunchCleanupRemotes(String launchId);

    @Update("""
            UPDATE ai_candidate_internal_launch_cleanup_remote SET state='STOPPING',
              stop_claim_owner=#{claimOwner},stop_claim_token=#{claimToken},
              stop_claim_expires_at=#{claimExpiresAt},stop_fence=#{claimFence},
              last_error_code=NULL,last_error_detail=NULL,updated_at=#{updatedAt},version=version+1
            WHERE launch_id=#{launchId} AND external_session_id=#{externalSessionId}
              AND version=#{version} AND state=#{expectedState} AND termination_proof IS NULL
              AND (stop_claim_owner IS NULL OR stop_claim_expires_at<=#{claimedAt})
            """)
    int claimGenericCandidateInternalLaunchCleanupRemote(
            @Param("launchId") String launchId, @Param("externalSessionId") String externalSessionId,
            @Param("version") long version, @Param("expectedState") String expectedState,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimExpiresAt") String claimExpiresAt, @Param("claimFence") long claimFence,
            @Param("claimedAt") String claimedAt, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE ai_candidate_internal_launch_cleanup_remote
            SET stop_dispatch_attempted=1,stop_dispatch_started_at=#{dispatchStartedAt},
              updated_at=#{updatedAt},version=version+1
            WHERE launch_id=#{launchId} AND external_session_id=#{externalSessionId}
              AND version=#{version} AND state='STOPPING' AND stop_dispatch_attempted=0
              AND stop_claim_owner=#{claimOwner} AND stop_claim_token=#{claimToken}
              AND stop_fence=#{claimFence} AND stop_claim_expires_at>#{dispatchStartedAt}
            """)
    int markGenericCandidateInternalLaunchCleanupStopDispatchStarted(
            @Param("launchId") String launchId, @Param("externalSessionId") String externalSessionId,
            @Param("version") long version,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimFence") long claimFence, @Param("dispatchStartedAt") String dispatchStartedAt,
            @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE ai_candidate_internal_launch_cleanup_remote SET state=#{row.state},
              termination_proof=#{row.terminationProof},proof_at=#{row.proofAt},
              stop_claim_owner=#{row.stopClaimOwner},stop_claim_token=#{row.stopClaimToken},
              stop_claim_expires_at=#{row.stopClaimExpiresAt},stop_fence=#{row.stopFence},
              stop_dispatch_attempted=#{row.stopDispatchAttempted},
              stop_dispatch_started_at=#{row.stopDispatchStartedAt},
              last_error_code=#{row.lastErrorCode},last_error_detail=#{row.lastErrorDetail},
              updated_at=#{row.updatedAt},version=version+1
            WHERE launch_id=#{row.launchId} AND external_session_id=#{row.externalSessionId}
              AND version=#{row.version} AND state='STOPPING'
              AND stop_claim_owner=#{claimOwner} AND stop_claim_token=#{claimToken}
              AND stop_fence=#{claimFence}
            """)
    int updateGenericCandidateInternalLaunchCleanupRemoteAsClaimHolder(
            @Param("row") GenericCandidateInternalLaunchCleanupRemoteRow row,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimFence") long claimFence);

    @Select("SELECT * FROM ai_candidate_internal_launch_settlement_certificate WHERE launch_id=#{launchId}")
    Optional<GenericCandidateInternalLaunchSettlementCertificateRow>
            findGenericCandidateInternalLaunchSettlementCertificate(String launchId);

    @Select("SELECT * FROM ai_candidate_internal_launch_run_requirement WHERE launch_id=#{launchId}")
    Optional<GenericCandidateInternalLaunchRunRequirementRow>
            findGenericCandidateInternalLaunchRunRequirement(String launchId);
}
