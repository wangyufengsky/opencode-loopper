package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Narrow persistence primitives for V57 durable termination intents. */
public interface LoopperGenericCandidateTerminationMapper {
    @Insert("""
            INSERT INTO ai_candidate_internal_termination_intent(
              id,launch_id,candidate_run_id,intent_kind,target_launch_state,state,reason_code,
              owner_cancel_requested,archive_when_complete,anchor_owner_version,ready_at,completed_at,last_error_code,last_error_detail,
              created_at,updated_at,version)
            VALUES(#{id},#{launchId},#{candidateRunId},#{intentKind},#{targetLaunchState},#{state},
              #{reasonCode},#{ownerCancelRequested},#{archiveWhenComplete},#{anchorOwnerVersion},#{readyAt},#{completedAt},#{lastErrorCode},
              #{lastErrorDetail},#{createdAt},#{updatedAt},#{version})
            """)
    int insertGenericCandidateInternalTerminationIntent(GenericCandidateInternalTerminationIntentRow row);

    @Select("SELECT * FROM ai_candidate_internal_termination_intent WHERE id=#{id}")
    Optional<GenericCandidateInternalTerminationIntentRow>
            findGenericCandidateInternalTerminationIntent(String id);

    @Select("SELECT * FROM ai_candidate_internal_termination_intent WHERE launch_id=#{launchId}")
    Optional<GenericCandidateInternalTerminationIntentRow>
            findGenericCandidateInternalTerminationIntentForLaunch(String launchId);

    @Select("""
            SELECT * FROM ai_candidate_internal_termination_intent
            WHERE state<>'COMPLETED' ORDER BY updated_at,id
            """)
    List<GenericCandidateInternalTerminationIntentRow>
            listRecoverableGenericCandidateInternalTerminationIntents();

    @Update("""
            UPDATE ai_candidate_internal_termination_intent SET state=#{state},reason_code=#{reasonCode},
              ready_at=#{readyAt},completed_at=#{completedAt},last_error_code=#{lastErrorCode},
              last_error_detail=#{lastErrorDetail},updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateGenericCandidateInternalTerminationIntent(GenericCandidateInternalTerminationIntentRow row);

    @Update("""
            UPDATE ai_candidate_internal_termination_intent
            SET owner_cancel_requested=1,
              archive_when_complete=CASE WHEN #{archiveWhenComplete} THEN 1 ELSE archive_when_complete END,
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state<>'COMPLETED'
            """)
    int requestGenericCandidateInternalOwnerCancel(
            @Param("id") String id, @Param("version") long version,
            @Param("archiveWhenComplete") boolean archiveWhenComplete,
            @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE ai_candidate_internal_termination_intent SET state='COMPLETED',
              completed_at=#{completedAt},last_error_code=NULL,last_error_detail=NULL,
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state='READY'
            """)
    int completeGenericCandidateInternalTerminationIntent(
            @Param("id") String id, @Param("version") long version,
            @Param("completedAt") String completedAt, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE ai_candidate_internal_launch SET state=#{row.state},
              create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
              termination_proof=#{row.terminationProof},proof_at=#{row.proofAt},
              failure_phase=#{row.failurePhase},last_error_code=#{row.lastErrorCode},
              last_error_detail=#{row.lastErrorDetail},updated_at=#{row.updatedAt},version=version+1
            WHERE id=#{row.id} AND version=#{row.version}
              AND EXISTS(SELECT 1 FROM ai_candidate_internal_termination_intent intent
                WHERE intent.id=#{intentId} AND intent.launch_id=#{row.id}
                  AND intent.state IN ('REQUESTED','DISCONNECTED'))
            """)
    int updateGenericCandidateInternalLaunchForTermination(
            @Param("row") GenericCandidateInternalLaunchRow row,
            @Param("intentId") String intentId);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM ai_candidate_internal_launch launch
              WHERE launch.external_session_id=#{externalSessionId}
              UNION ALL
              SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote cleanup
              WHERE cleanup.external_session_id=#{externalSessionId})
            """)
    boolean existsGenericCandidateInternalTrackedExternalSession(String externalSessionId);
}
