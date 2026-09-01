package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Narrow persistence primitives for the Acceptance v7 internal-launch saga. */
public interface LoopperAcceptanceCandidateLaunchMapper {
    @Insert("""
            INSERT INTO acceptance_candidate_internal_launch(
              id,compilation_id,designer_session_id,work_package_id,source_design_revision,
              source_design_message_id,source_draft_version,source_design_sha256,
              planning_version,planning_binding_source,planning_binding_json,planning_binding_sha256,
              route_plan_json,route_plan_sha256,candidate_run_id,contract_version,workflow_step,state,
              prepared_owner_version,settled_owner_version,settled_at,exact_title,canonical_directory,
              runtime_generation_id,managed,internal_mcp_server,endpoint_fingerprint,
              model_provider_id,model_id,thinking,profile,permission_policy_json,permission_policy_digest,
              create_request_sha256,creation_credential,attestation_type,
              create_claim_owner,create_claim_token,create_claim_expires_at,create_fence,
              create_dispatch_attempted,create_dispatch_started_at,external_session_id,external_attested_at,
              termination_proof,proof_at,failure_phase,last_error_code,last_error_detail,
              created_at,updated_at,version)
            VALUES(#{id},#{compilationId},#{designerSessionId},#{workPackageId},#{sourceDesignRevision},
              #{sourceDesignMessageId},#{sourceDraftVersion},#{sourceDesignSha256},
              #{planningVersion},#{planningBindingSource},#{planningBindingJson},#{planningBindingSha256},
              #{routePlanJson},#{routePlanSha256},#{candidateRunId},#{contractVersion},#{workflowStep},#{state},
              #{preparedOwnerVersion},#{settledOwnerVersion},#{settledAt},#{exactTitle},#{canonicalDirectory},
              #{runtimeGenerationId},#{managed},#{internalMcpServer},#{endpointFingerprint},
              #{modelProviderId},#{modelId},#{thinking},#{profile},#{permissionPolicyJson},#{permissionPolicyDigest},
              #{createRequestSha256},#{creationCredential},#{attestationType},
              #{createClaimOwner},#{createClaimToken},#{createClaimExpiresAt},#{createFence},
              #{createDispatchAttempted},#{createDispatchStartedAt},#{externalSessionId},#{externalAttestedAt},
              #{terminationProof},#{proofAt},#{failurePhase},#{lastErrorCode},#{lastErrorDetail},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertAcceptanceCandidateInternalLaunch(AcceptanceCandidateInternalLaunchRow row);

    @Select("SELECT * FROM acceptance_candidate_internal_launch WHERE id=#{id}")
    Optional<AcceptanceCandidateInternalLaunchRow> findAcceptanceCandidateInternalLaunch(String id);

    @Select("SELECT * FROM acceptance_candidate_internal_launch WHERE compilation_id=#{compilationId}")
    Optional<AcceptanceCandidateInternalLaunchRow> findAcceptanceCandidateInternalLaunchForCompilation(
            String compilationId);

    @Select("""
            SELECT * FROM acceptance_candidate_internal_launch
            WHERE state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
            ORDER BY updated_at,id
            """)
    List<AcceptanceCandidateInternalLaunchRow> listActiveAcceptanceCandidateInternalLaunches();

    @Update("""
            UPDATE acceptance_candidate_internal_launch
            SET create_claim_owner=#{claimOwner},create_claim_token=#{claimToken},
              create_claim_expires_at=#{claimExpiresAt},create_fence=#{claimFence},
              last_error_code=NULL,last_error_detail=NULL,updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state=#{expectedState}
              AND settled_owner_version IS NULL AND termination_proof IS NULL
              AND (create_claim_owner IS NULL OR create_claim_expires_at<=#{claimedAt})
            """)
    int claimAcceptanceCandidateInternalLaunchCreate(
            @Param("id") String id, @Param("version") long version,
            @Param("expectedState") String expectedState,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimExpiresAt") String claimExpiresAt, @Param("claimFence") long claimFence,
            @Param("claimedAt") String claimedAt, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE acceptance_candidate_internal_launch
            SET state='CREATING',create_dispatch_attempted=1,
              create_dispatch_started_at=#{dispatchStartedAt},updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state='PREPARED'
              AND create_dispatch_attempted=0 AND external_session_id IS NULL
              AND create_claim_owner=#{claimOwner} AND create_claim_token=#{claimToken}
              AND create_fence=#{claimFence} AND create_claim_expires_at>#{dispatchStartedAt}
            """)
    int markAcceptanceCandidateInternalLaunchCreateDispatchStarted(
            @Param("id") String id, @Param("version") long version,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimFence") long claimFence, @Param("dispatchStartedAt") String dispatchStartedAt,
            @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE acceptance_candidate_internal_launch SET state=#{row.state},
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
    int updateAcceptanceCandidateInternalLaunchAsClaimHolder(
            @Param("row") AcceptanceCandidateInternalLaunchRow row,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimFence") long claimFence);

    @Update("""
            UPDATE loop_spec_compilation
            SET state='RUNNING',external_session_id=#{externalSessionId},
              external_session_state='CANDIDATE_PROMPT_PENDING',last_error_code=NULL,last_error_detail=NULL,
              updated_at=#{updatedAt},version=version+1
            WHERE id=(SELECT launch.compilation_id FROM acceptance_candidate_internal_launch launch
                      WHERE launch.id=#{launchId})
              AND state='PENDING_HANDOFF' AND version=#{preparedOwnerVersion}
              AND external_session_id IS NULL
              AND EXISTS (
                SELECT 1 FROM acceptance_candidate_internal_launch launch
                WHERE launch.id=#{launchId} AND launch.version=#{launchVersion}
                  AND launch.state='CREATED'
                  AND launch.prepared_owner_version=#{preparedOwnerVersion}
                  AND launch.external_session_id=#{externalSessionId}
                  AND launch.settled_owner_version IS NULL AND launch.termination_proof IS NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
                    WHERE cleanup.launch_id=launch.id))
            """)
    int advanceAcceptanceCandidateCompilationForInternalLaunch(
            @Param("launchId") String launchId, @Param("launchVersion") long launchVersion,
            @Param("preparedOwnerVersion") long preparedOwnerVersion,
            @Param("externalSessionId") String externalSessionId, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE acceptance_candidate_internal_launch
            SET state='SETTLED',settled_owner_version=#{settledOwnerVersion},settled_at=#{settledAt},
              create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
              failure_phase=NULL,last_error_code=NULL,last_error_detail=NULL,
              updated_at=#{settledAt},version=version+1
            WHERE id=#{launchId} AND version=#{launchVersion} AND state='CREATED'
              AND settled_owner_version IS NULL AND termination_proof IS NULL
              AND prepared_owner_version+1=#{settledOwnerVersion}
              AND NOT EXISTS (
                SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
                WHERE cleanup.launch_id=#{launchId})
            """)
    int settleAcceptanceCandidateInternalLaunch(
            @Param("launchId") String launchId, @Param("launchVersion") long launchVersion,
            @Param("settledOwnerVersion") long settledOwnerVersion,
            @Param("settledAt") String settledAt);

    @Update("""
            UPDATE acceptance_candidate_internal_launch
            SET state='FAILED_STOPPED',create_claim_owner=NULL,create_claim_token=NULL,
              create_claim_expires_at=NULL,failure_phase='REMOTE_STOP',
              last_error_code=#{errorCode},last_error_detail=#{errorDetail},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{launchId} AND version=#{launchVersion} AND state='STOPPING'
              AND settled_owner_version IS NULL AND termination_proof IS NULL
              AND EXISTS (
                SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
                WHERE cleanup.launch_id=#{launchId})
              AND NOT EXISTS (
                SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
                WHERE cleanup.launch_id=#{launchId} AND cleanup.state<>'STOPPED')
            """)
    int finishAcceptanceCandidateInternalLaunchAfterCleanup(
            @Param("launchId") String launchId, @Param("launchVersion") long launchVersion,
            @Param("errorCode") String errorCode, @Param("errorDetail") String errorDetail,
            @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO acceptance_candidate_internal_launch_cleanup_remote(
              launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
              directory_sha256,title_sha256,purpose,termination_intent_id,state,termination_proof,proof_at,
              stop_claim_owner,stop_claim_token,stop_claim_expires_at,stop_fence,
              stop_dispatch_attempted,stop_dispatch_started_at,last_error_code,last_error_detail,
              created_at,updated_at,version)
            VALUES(#{launchId},#{externalSessionId},#{runtimeGenerationId},#{endpointFingerprint},
              #{directorySha256},#{titleSha256},#{purpose},#{terminationIntentId},
              #{state},#{terminationProof},#{proofAt},
              #{stopClaimOwner},#{stopClaimToken},#{stopClaimExpiresAt},#{stopFence},
              #{stopDispatchAttempted},#{stopDispatchStartedAt},#{lastErrorCode},#{lastErrorDetail},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertAcceptanceCandidateInternalLaunchCleanupRemote(
            AcceptanceCandidateInternalLaunchCleanupRemoteRow row);

    @Select("""
            SELECT * FROM acceptance_candidate_internal_launch_cleanup_remote
            WHERE launch_id=#{launchId} ORDER BY external_session_id
            """)
    List<AcceptanceCandidateInternalLaunchCleanupRemoteRow>
            listAcceptanceCandidateInternalLaunchCleanupRemotes(String launchId);

    @Update("""
            UPDATE acceptance_candidate_internal_launch_cleanup_remote
            SET state='STOPPING',stop_claim_owner=#{claimOwner},stop_claim_token=#{claimToken},
              stop_claim_expires_at=#{claimExpiresAt},stop_fence=#{claimFence},
              last_error_code=NULL,last_error_detail=NULL,updated_at=#{updatedAt},version=version+1
            WHERE launch_id=#{launchId} AND external_session_id=#{externalSessionId}
              AND version=#{version} AND state=#{expectedState} AND termination_proof IS NULL
              AND (stop_claim_owner IS NULL OR stop_claim_expires_at<=#{claimedAt})
            """)
    int claimAcceptanceCandidateInternalLaunchCleanupRemote(
            @Param("launchId") String launchId, @Param("externalSessionId") String externalSessionId,
            @Param("version") long version, @Param("expectedState") String expectedState,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimExpiresAt") String claimExpiresAt, @Param("claimFence") long claimFence,
            @Param("claimedAt") String claimedAt, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE acceptance_candidate_internal_launch_cleanup_remote
            SET stop_dispatch_attempted=1,stop_dispatch_started_at=#{dispatchStartedAt},
              updated_at=#{updatedAt},version=version+1
            WHERE launch_id=#{launchId} AND external_session_id=#{externalSessionId}
              AND version=#{version} AND state='STOPPING' AND stop_dispatch_attempted=0
              AND stop_claim_owner=#{claimOwner} AND stop_claim_token=#{claimToken}
              AND stop_fence=#{claimFence} AND stop_claim_expires_at>#{dispatchStartedAt}
            """)
    int markAcceptanceCandidateInternalLaunchCleanupStopDispatchStarted(
            @Param("launchId") String launchId, @Param("externalSessionId") String externalSessionId,
            @Param("version") long version,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimFence") long claimFence, @Param("dispatchStartedAt") String dispatchStartedAt,
            @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE acceptance_candidate_internal_launch_cleanup_remote SET state=#{row.state},
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
    int updateAcceptanceCandidateInternalLaunchCleanupRemoteAsClaimHolder(
            @Param("row") AcceptanceCandidateInternalLaunchCleanupRemoteRow row,
            @Param("claimOwner") String claimOwner, @Param("claimToken") String claimToken,
            @Param("claimFence") long claimFence);
}
