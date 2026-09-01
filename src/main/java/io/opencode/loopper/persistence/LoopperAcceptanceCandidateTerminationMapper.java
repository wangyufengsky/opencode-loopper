package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

/** Narrow persistence primitives for durable internal-launch termination intent. */
public interface LoopperAcceptanceCandidateTerminationMapper {
    @Insert("""
            INSERT INTO acceptance_candidate_internal_termination_intent(
              id,launch_id,designer_session_id,compilation_id,candidate_run_id,kind,target_state,
              archive_when_complete,reason_code,parent_action,state,
              anchor_designer_version,anchor_requirement_revision_id,anchor_discussion_revision,
              ready_at,completed_at,last_error_code,last_error_detail,created_at,updated_at,version)
            VALUES(#{id},#{launchId},#{designerSessionId},#{compilationId},#{candidateRunId},#{kind},
              #{targetState},#{archiveWhenComplete},#{reasonCode},#{parentAction},#{state},#{anchorDesignerVersion},
              #{anchorRequirementRevisionId},
              #{anchorDiscussionRevision},#{readyAt},#{completedAt},#{lastErrorCode},#{lastErrorDetail},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertAcceptanceCandidateInternalTerminationIntent(
            AcceptanceCandidateInternalTerminationIntentRow row);

    @Select("SELECT * FROM acceptance_candidate_internal_termination_intent WHERE id=#{id}")
    Optional<AcceptanceCandidateInternalTerminationIntentRow>
            findAcceptanceCandidateInternalTerminationIntent(String id);

    @Select("SELECT * FROM acceptance_candidate_internal_termination_intent WHERE launch_id=#{launchId}")
    Optional<AcceptanceCandidateInternalTerminationIntentRow>
            findAcceptanceCandidateInternalTerminationIntentForLaunch(String launchId);

    @Select("""
            SELECT * FROM acceptance_candidate_internal_termination_intent
            WHERE compilation_id=#{compilationId} AND state<>'COMPLETED'
            ORDER BY created_at,id LIMIT 1
            """)
    Optional<AcceptanceCandidateInternalTerminationIntentRow>
            findActiveAcceptanceCandidateInternalTerminationIntentForCompilation(String compilationId);

    @Select("""
            SELECT * FROM acceptance_candidate_internal_termination_intent
            WHERE designer_session_id=#{designerSessionId} AND state<>'COMPLETED'
            ORDER BY created_at,id
            """)
    List<AcceptanceCandidateInternalTerminationIntentRow>
            listActiveAcceptanceCandidateInternalTerminationIntents(String designerSessionId);

    @Select("""
            SELECT * FROM acceptance_candidate_internal_termination_intent
            WHERE state<>'COMPLETED'
            ORDER BY updated_at,id
            """)
    List<AcceptanceCandidateInternalTerminationIntentRow>
            listRecoverableAcceptanceCandidateInternalTerminationIntents();

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM acceptance_candidate_internal_termination_intent
              WHERE designer_session_id=#{designerSessionId} AND state<>'COMPLETED')
            """)
    boolean existsActiveAcceptanceCandidateInternalTerminationIntentForDesigner(String designerSessionId);

    @Select("""
            SELECT * FROM acceptance_candidate_internal_launch
            WHERE designer_session_id=#{designerSessionId}
              AND state IN ('PREPARED','CREATING','DISCONNECTED','CREATED','STOPPING','SETTLED')
            ORDER BY updated_at,id
            """)
    List<AcceptanceCandidateInternalLaunchRow>
            listTerminableAcceptanceCandidateInternalLaunchesForDesigner(String designerSessionId);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM acceptance_candidate_internal_launch launch
              WHERE launch.external_session_id=#{externalSessionId}
              UNION ALL
              SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
              WHERE cleanup.external_session_id=#{externalSessionId})
            """)
    boolean existsAcceptanceCandidateInternalTrackedExternalSession(String externalSessionId);

    @Update("""
            UPDATE acceptance_candidate_internal_termination_intent
            SET state=#{state},ready_at=#{readyAt},completed_at=#{completedAt},
              last_error_code=#{lastErrorCode},last_error_detail=#{lastErrorDetail},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateAcceptanceCandidateInternalTerminationIntent(
            AcceptanceCandidateInternalTerminationIntentRow row);

    @Update("""
            UPDATE acceptance_candidate_internal_termination_intent
            SET parent_action=#{parentAction},archive_when_complete=#{archiveWhenComplete},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state<>'COMPLETED'
              AND kind='INITIAL_PROMPT_FAILURE' AND parent_action='NONE'
              AND ((#{parentAction}='DESIGNER_CANCEL' AND EXISTS (
                    SELECT 1 FROM designer_session designer
                    WHERE designer.id=designer_session_id AND designer.state='STOPPING'))
                OR (#{parentAction}='OWNER_REPLACEMENT' AND #{archiveWhenComplete}=0 AND EXISTS (
                    SELECT 1 FROM designer_session designer
                    JOIN design_requirement_revision revision ON revision.id=anchor_requirement_revision_id
                    WHERE designer.id=designer_session_id
                      AND designer.current_requirement_revision=revision.revision
                      AND designer.discussion_revision=anchor_discussion_revision
                      AND designer.state NOT IN ('STOPPING','CANCELLED'))))
            """)
    int promoteAcceptanceCandidateInitialTerminationParentAction(
            @Param("id") String id, @Param("version") long version,
            @Param("parentAction") String parentAction,
            @Param("archiveWhenComplete") boolean archiveWhenComplete,
            @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE acceptance_candidate_internal_termination_intent
            SET state='COMPLETED',completed_at=#{completedAt},last_error_code=NULL,last_error_detail=NULL,
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state='READY'
              AND EXISTS (
                SELECT 1 FROM acceptance_candidate_internal_launch launch
                WHERE launch.id=acceptance_candidate_internal_termination_intent.launch_id
                  AND launch.state IN (acceptance_candidate_internal_termination_intent.target_state,
                    'FAILED_STOPPED')
                  AND NOT EXISTS (
                    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
                    WHERE cleanup.launch_id=launch.id AND cleanup.state<>'STOPPED')
                  AND NOT EXISTS (
                    SELECT 1 FROM ai_candidate_prompt_dispatch prompt
                    WHERE prompt.internal_launch_id=launch.id
                      AND prompt.state NOT IN ('STOPPED','CANCELLED'))
                  AND NOT EXISTS (
                    SELECT 1 FROM ai_candidate_submission_run run
                    WHERE run.id=launch.candidate_run_id AND run.state='OPEN'))
              AND ((parent_action='DESIGNER_CANCEL' AND EXISTS (
                    SELECT 1 FROM designer_session designer
                    WHERE designer.id=designer_session_id AND designer.state='CANCELLED'))
                OR (parent_action='OWNER_REPLACEMENT' AND EXISTS (
                    SELECT 1 FROM design_requirement_revision revision
                    WHERE revision.id=anchor_requirement_revision_id AND revision.state='SUPERSEDED'))
                OR (kind='INITIAL_PROMPT_FAILURE' AND parent_action='NONE' AND EXISTS (
                    SELECT 1
                    FROM loop_spec_compilation compilation
                    JOIN designer_session designer
                      ON designer.id=acceptance_candidate_internal_termination_intent.designer_session_id
                    JOIN design_requirement_revision revision
                      ON revision.id=acceptance_candidate_internal_termination_intent.anchor_requirement_revision_id
                    WHERE compilation.id=acceptance_candidate_internal_termination_intent.compilation_id
                      AND revision.designer_session_id=
                        acceptance_candidate_internal_termination_intent.designer_session_id
                      AND ((revision.state='SUPERSEDED')
                        OR (designer.state='CANCELLED'
                          AND compilation.state='SESSION_ERROR'
                          AND compilation.last_error_code='DESIGNER_CANCELLED')
                        OR (acceptance_candidate_internal_termination_intent.reason_code='BUDGET_EXHAUSTED'
                          AND compilation.state='DESIGN_INCOMPLETE'
                          AND compilation.last_error_code='WORK_PACKAGE_MODEL_CALL_LIMIT')
                        OR (acceptance_candidate_internal_termination_intent.reason_code='LOOKUP_UNSUPPORTED'
                          AND compilation.state='DESIGN_INCOMPLETE'
                          AND compilation.last_error_code='DESIGN_INCOMPLETE')
                        OR (acceptance_candidate_internal_termination_intent.reason_code='RESULT_UNKNOWN'
                          AND compilation.state='SESSION_ERROR'
                          AND compilation.last_error_code='OPENCODE_PROMPT_RESULT_UNKNOWN')
                        OR EXISTS (
                          SELECT 1 FROM ai_candidate_submission_run run
                          WHERE run.id=acceptance_candidate_internal_termination_intent.candidate_run_id
                            AND ((run.state='ACCEPTED'
                                  AND compilation.state IN ('COMPLETED','DESIGN_INCOMPLETE','SESSION_ERROR'))
                              OR (run.state='WAITING_INPUT'
                                  AND compilation.state='DESIGN_INCOMPLETE'
                                  AND compilation.last_error_code='ACCEPTANCE_CANDIDATE_WAITING_INPUT')
                              OR (run.state='FALLBACK_REQUIRED' AND EXISTS (
                                  SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
                                  WHERE handoff.compilation_id=compilation.id))
                              OR (run.state='CLOSED' AND (
                                  (run.close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION' AND EXISTS (
                                    SELECT 1 FROM acceptance_candidate_legacy_handoff handoff
                                    WHERE handoff.compilation_id=compilation.id))
                                  OR (run.close_reason IN (
                                      'INTERACTION_FORBIDDEN','TIMEOUT','REMOTE_FAILED')
                                    AND compilation.state='SESSION_ERROR')))))))))
            """)
    int completeAcceptanceCandidateInternalTerminationIntent(
            @Param("id") String id, @Param("version") long version,
            @Param("completedAt") String completedAt, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE acceptance_candidate_internal_launch
            SET state=#{row.state},settled_owner_version=#{row.settledOwnerVersion},settled_at=#{row.settledAt},
              create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
              termination_proof=#{row.terminationProof},proof_at=#{row.proofAt},
              failure_phase=#{row.failurePhase},last_error_code=#{row.lastErrorCode},
              last_error_detail=#{row.lastErrorDetail},updated_at=#{row.updatedAt},version=version+1
            WHERE id=#{row.id} AND version=#{row.version}
              AND EXISTS (
                SELECT 1 FROM acceptance_candidate_internal_termination_intent intent
                WHERE intent.id=#{intentId} AND intent.launch_id=#{row.id}
                  AND intent.state IN ('REQUESTED','DISCONNECTED'))
            """)
    int updateAcceptanceCandidateInternalLaunchForTermination(
            @Param("row") AcceptanceCandidateInternalLaunchRow row,
            @Param("intentId") String intentId);
}
