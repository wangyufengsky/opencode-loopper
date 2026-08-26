package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Domain-focused persistence contract composed by {@link LoopperMapper}. */
public interface LoopperDesignerMapper {
    @Insert("INSERT INTO designer_session(id,project_id,state,access_mode,external_session_id,external_session_state,loop_draft_id,workflow_phase,design_revision,redesign_count,current_requirement_revision,active_work_package_id,discussion_scope,discussion_revision,candidate_sync_state,created_at,updated_at,version) VALUES(#{id},#{projectId},#{state},#{accessMode},#{externalSessionId},#{externalSessionState},#{loopDraftId},#{workflowPhase},#{designRevision},#{redesignCount},#{currentRequirementRevision},#{activeWorkPackageId},#{discussionScope},#{discussionRevision},#{candidateSyncState},#{createdAt},#{updatedAt},#{version})")
    int insertDesignerSession(DesignerSessionRow row);
    @Select("SELECT * FROM designer_session WHERE id=#{id}") Optional<DesignerSessionRow> findDesignerSession(String id);
    @Select("SELECT * FROM designer_session WHERE task_id=#{taskId} ORDER BY updated_at DESC LIMIT 1")
    Optional<DesignerSessionRow> findDesignerSessionByTask(String taskId);
    @Update("UPDATE designer_session SET task_id=#{taskId},updated_at=#{updatedAt},version=version+1 WHERE id=#{sessionId} AND version=#{version} AND task_id IS NULL")
    int bindDesignerSessionTask(@Param("sessionId") String sessionId, @Param("taskId") String taskId,
                                @Param("updatedAt") String updatedAt, @Param("version") long version);
    @Select("SELECT * FROM designer_session WHERE loop_draft_id=#{draftId} ORDER BY created_at DESC LIMIT 1")
    Optional<DesignerSessionRow> findLatestDesignerSessionByDraft(String draftId);
    @Select("""
            SELECT s.* FROM designer_session s
            JOIN loop_draft d ON d.id=s.loop_draft_id
            WHERE s.project_id=#{projectId}
              AND d.status<>'CONFIRMED'
              AND NOT EXISTS (
                SELECT 1 FROM designer_session_archive archive
                WHERE archive.designer_session_id=s.id
              )
              AND s.id=(
                SELECT latest.id FROM designer_session latest
                WHERE latest.loop_draft_id=s.loop_draft_id
                ORDER BY latest.created_at DESC, latest.id DESC
                LIMIT 1
              )
            ORDER BY s.updated_at DESC, s.id DESC
            """)
    List<DesignerSessionRow> listOpenDesignerSessionsForProject(String projectId);
    @Select("""
            SELECT s.id,s.project_id,p.name AS project_name,s.state,s.workflow_phase,
              s.created_at,s.updated_at,d.id AS draft_id,d.status AS draft_status,d.goal,
              s.current_requirement_revision AS requirement_revision,s.active_work_package_id,
              CASE WHEN archive.designer_session_id IS NULL THEN 0 ELSE 1 END AS archived,
              archive.archived_at,t.id AS task_id,t.state AS task_state
            FROM designer_session s
            JOIN loop_draft d ON d.id=s.loop_draft_id
            JOIN project p ON p.id=s.project_id
            LEFT JOIN designer_session_archive archive ON archive.designer_session_id=s.id
            LEFT JOIN task t ON t.loop_draft_id=d.id
            WHERE (#{projectId} IS NULL OR s.project_id=#{projectId})
              AND s.id=(
                SELECT latest.id FROM designer_session latest
                WHERE latest.loop_draft_id=s.loop_draft_id
                ORDER BY latest.created_at DESC, latest.id DESC
                LIMIT 1
              )
            ORDER BY s.updated_at DESC,s.id DESC
            """)
    List<DesignerSessionHistoryRow> listDesignerSessionHistory(@Param("projectId") String projectId);
    @Select("SELECT COUNT(*) > 0 FROM designer_session_archive WHERE designer_session_id=#{sessionId}")
    boolean isDesignerSessionArchived(String sessionId);
    @Insert("INSERT INTO designer_session_archive(designer_session_id,archived_at) VALUES(#{sessionId},#{archivedAt}) ON CONFLICT(designer_session_id) DO UPDATE SET archived_at=excluded.archived_at")
    int archiveDesignerSession(@Param("sessionId") String sessionId, @Param("archivedAt") String archivedAt);
    @Delete("DELETE FROM designer_session_archive WHERE designer_session_id=#{sessionId}")
    int restoreDesignerSession(String sessionId);
    @Insert("""
            INSERT INTO designer_task_profile(id,designer_session_id,requirement_revision_id,state,intent,
              workflow_template,mutation_mode,artifact_kinds_json,technologies_json,test_policy,
              execution_strategy,role_pack_id,role_pack_version,confidence,evidence_json,resolution_source,
              decision_required,created_at,updated_at,version,project_stack_profile_id,component_keys_json,
              stack_fingerprint)
            VALUES(#{id},#{designerSessionId},#{requirementRevisionId},#{state},#{intent},
              #{workflowTemplate},#{mutationMode},#{artifactKindsJson},#{technologiesJson},#{testPolicy},
              #{executionStrategy},#{rolePackId},#{rolePackVersion},#{confidence},#{evidenceJson},#{resolutionSource},
              #{decisionRequired},#{createdAt},#{updatedAt},#{version},#{projectStackProfileId},
              #{componentKeysJson},#{stackFingerprint})
            """)
    int insertDesignerTaskProfile(DesignerTaskProfileRow row);
    @Select("SELECT * FROM designer_task_profile WHERE id=#{id}")
    Optional<DesignerTaskProfileRow> findDesignerTaskProfile(String id);
    @Select("""
            SELECT * FROM designer_task_profile WHERE designer_session_id=#{sessionId}
            AND state IN ('PROVISIONAL','FROZEN') ORDER BY created_at DESC LIMIT 1
            """)
    Optional<DesignerTaskProfileRow> findCurrentDesignerTaskProfile(String sessionId);
    @Select("""
            SELECT profile.* FROM designer_task_profile profile
            JOIN designer_session session ON session.id=profile.designer_session_id
            WHERE session.loop_draft_id=#{draftId} AND profile.state='FROZEN'
            ORDER BY profile.created_at DESC LIMIT 1
            """)
    Optional<DesignerTaskProfileRow> findFrozenTaskProfileByDraft(String draftId);
    @Select("SELECT * FROM designer_task_profile WHERE designer_session_id=#{sessionId} ORDER BY created_at DESC")
    List<DesignerTaskProfileRow> listDesignerTaskProfiles(String sessionId);
    @Update("""
            UPDATE designer_task_profile SET requirement_revision_id=#{requirementRevisionId},state=#{state},
              intent=#{intent},workflow_template=#{workflowTemplate},mutation_mode=#{mutationMode},
              artifact_kinds_json=#{artifactKindsJson},technologies_json=#{technologiesJson},test_policy=#{testPolicy},
              execution_strategy=#{executionStrategy},role_pack_id=#{rolePackId},role_pack_version=#{rolePackVersion},
              confidence=#{confidence},evidence_json=#{evidenceJson},resolution_source=#{resolutionSource},
              decision_required=#{decisionRequired},project_stack_profile_id=#{projectStackProfileId},
              component_keys_json=#{componentKeysJson},stack_fingerprint=#{stackFingerprint},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateDesignerTaskProfile(DesignerTaskProfileRow row);
    @Update("UPDATE designer_task_profile SET requirement_revision_id=#{requirementRevisionId},updated_at=#{updatedAt},version=version+1 WHERE id=#{profileId} AND state='FROZEN'")
    int bindTaskProfileRequirement(@Param("profileId") String profileId,
                                   @Param("requirementRevisionId") String requirementRevisionId,
                                   @Param("updatedAt") String updatedAt);
    @Update("UPDATE designer_task_profile SET state='SUPERSEDED',updated_at=#{updatedAt},version=version+1 WHERE designer_session_id=#{sessionId} AND state='PROVISIONAL'")
    int supersedeProvisionalTaskProfiles(@Param("sessionId") String sessionId, @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO task_profile_router_run(id,designer_session_id,state,requirement_snapshot,
              repository_evidence_json,external_session_id,external_session_state,response_mode,
              semantic_labels_json,error_code,error_detail,created_at,updated_at,version,
              project_stack_profile_id,component_keys_json,stack_fingerprint)
            VALUES(#{id},#{designerSessionId},#{state},#{requirementSnapshot},#{repositoryEvidenceJson},
              #{externalSessionId},#{externalSessionState},#{responseMode},#{semanticLabelsJson},
              #{errorCode},#{errorDetail},#{createdAt},#{updatedAt},#{version},#{projectStackProfileId},
              #{componentKeysJson},#{stackFingerprint})
            """)
    int insertTaskProfileRouterRun(TaskProfileRouterRunRow row);
    @Select("SELECT * FROM task_profile_router_run WHERE id=#{id}")
    Optional<TaskProfileRouterRunRow> findTaskProfileRouterRun(String id);
    @Select("""
            SELECT * FROM task_profile_router_run WHERE designer_session_id=#{sessionId}
            ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    Optional<TaskProfileRouterRunRow> findLatestTaskProfileRouterRun(String sessionId);
    @Select("SELECT * FROM task_profile_router_run WHERE state IN ('PENDING','RUNNING') ORDER BY created_at,id")
    List<TaskProfileRouterRunRow> listActiveTaskProfileRouterRuns();
    @Update("""
            UPDATE task_profile_router_run SET state=#{state},external_session_id=#{externalSessionId},
              external_session_state=#{externalSessionState},response_mode=#{responseMode},
              semantic_labels_json=#{semanticLabelsJson},error_code=#{errorCode},error_detail=#{errorDetail},
              updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}
            """)
    int updateTaskProfileRouterRun(TaskProfileRouterRunRow row);
    @Update("""
            UPDATE task_profile_router_run SET state='SUPERSEDED',updated_at=#{updatedAt},version=version+1
            WHERE designer_session_id=#{sessionId} AND state IN ('PENDING','RUNNING')
            """)
    int supersedeActiveTaskProfileRouterRuns(@Param("sessionId") String sessionId,
                                              @Param("updatedAt") String updatedAt);
    @Update("UPDATE task_profile_router_run SET state='SUPERSEDED',external_session_state='ABORTED',updated_at=#{updatedAt},version=version+1 WHERE designer_session_id=#{sessionId} AND state IN ('PENDING','RUNNING')")
    int stopTaskProfileRouterRuns(@Param("sessionId") String sessionId, @Param("updatedAt") String updatedAt);
    @Update("""
            UPDATE designer_task_profile SET state='SUPERSEDED',updated_at=#{updatedAt},version=version+1
            WHERE designer_session_id=#{sessionId} AND state IN ('PROVISIONAL','FROZEN')
            """)
    int supersedeActiveTaskProfiles(@Param("sessionId") String sessionId, @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO analysis_report(id,designer_session_id,task_profile_id,state,title,markdown,evidence_json,
              content_sha256,source_snapshot_sha256,error_code,error_detail,created_at,updated_at,version,
              external_session_id,external_session_state,source_requirement,role_pack_id,role_pack_version,
              reviewer_contract_version,response_mode,findings_json,deadline_at)
            VALUES(#{id},#{designerSessionId},#{taskProfileId},#{state},#{title},#{markdown},#{evidenceJson},
              #{contentSha256},#{sourceSnapshotSha256},#{errorCode},#{errorDetail},#{createdAt},#{updatedAt},#{version},
              #{externalSessionId},#{externalSessionState},#{sourceRequirement},#{rolePackId},#{rolePackVersion},
              #{reviewerContractVersion},#{responseMode},#{findingsJson},#{deadlineAt})
            """)
    int insertAnalysisReport(AnalysisReportRow row);
    @Select("SELECT * FROM analysis_report WHERE id=#{id} AND designer_session_id=#{sessionId}")
    Optional<AnalysisReportRow> findAnalysisReport(@Param("sessionId") String sessionId, @Param("id") String id);
    @Select("SELECT * FROM analysis_report WHERE designer_session_id=#{sessionId} ORDER BY created_at DESC")
    List<AnalysisReportRow> listAnalysisReports(String sessionId);
    @Select("SELECT * FROM analysis_report WHERE state IN ('RUNNING','VALIDATING') AND external_session_id IS NOT NULL ORDER BY updated_at")
    List<AnalysisReportRow> activeAnalysisReports();
    @Update("""
            UPDATE analysis_report SET state=#{state},title=#{title},markdown=#{markdown},evidence_json=#{evidenceJson},
              content_sha256=#{contentSha256},source_snapshot_sha256=#{sourceSnapshotSha256},error_code=#{errorCode},
              error_detail=#{errorDetail},external_session_id=#{externalSessionId},
              external_session_state=#{externalSessionState},source_requirement=#{sourceRequirement},
              role_pack_id=#{rolePackId},role_pack_version=#{rolePackVersion},
              reviewer_contract_version=#{reviewerContractVersion},response_mode=#{responseMode},
              findings_json=#{findingsJson},deadline_at=#{deadlineAt},
              updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}
            """)
    int updateAnalysisReport(AnalysisReportRow row);
    @Update("UPDATE analysis_report SET state='FAILED',external_session_state='ABORTED',error_code='DESIGNER_CANCELLED',error_detail='Designer session was cancelled',updated_at=#{updatedAt},version=version+1 WHERE designer_session_id=#{sessionId} AND state IN ('RUNNING','VALIDATING')")
    int stopAnalysisReports(@Param("sessionId") String sessionId, @Param("updatedAt") String updatedAt);
    @Insert("""
            INSERT INTO artifact_plan(id,designer_session_id,task_profile_id,kind,state,plan_json,plan_sha256,
              created_at,updated_at,version)
            VALUES(#{id},#{designerSessionId},#{taskProfileId},#{kind},#{state},#{planJson},#{planSha256},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertArtifactPlan(ArtifactPlanRow row);
    @Select("SELECT * FROM artifact_plan WHERE id=#{id}") Optional<ArtifactPlanRow> findArtifactPlan(String id);
    @Select("SELECT * FROM artifact_plan WHERE designer_session_id=#{sessionId} AND state IN ('PROVISIONAL','FROZEN') ORDER BY created_at DESC")
    List<ArtifactPlanRow> listCurrentArtifactPlans(String sessionId);
    @Update("UPDATE artifact_plan SET state=#{state},plan_json=#{planJson},plan_sha256=#{planSha256},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateArtifactPlan(ArtifactPlanRow row);
    @Select("SELECT * FROM designer_session WHERE state='RUNNING' AND workflow_phase IN ('DISCUSSING_REQUIREMENT','DESIGNING','REDESIGNING','QUESTIONING_PACKAGE') AND external_session_id IS NOT NULL ORDER BY updated_at")
    List<DesignerSessionRow> activeDesignerHandoffs();
    @Update("UPDATE designer_session SET state=#{state}, access_mode=#{accessMode}, external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState}, loop_draft_id=#{loopDraftId}, workflow_phase=#{workflowPhase}, design_revision=#{designRevision}, redesign_count=#{redesignCount}, current_requirement_revision=#{currentRequirementRevision}, active_work_package_id=#{activeWorkPackageId}, discussion_scope=#{discussionScope}, discussion_revision=#{discussionRevision}, candidate_sync_state=#{candidateSyncState}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDesignerSession(DesignerSessionRow row);
    @Update("UPDATE designer_session SET access_mode=#{accessMode}, external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState}, loop_draft_id=#{loopDraftId}, workflow_phase=#{workflowPhase}, design_revision=#{designRevision}, redesign_count=#{redesignCount}, current_requirement_revision=#{currentRequirementRevision}, active_work_package_id=#{activeWorkPackageId}, discussion_scope=#{discussionScope}, discussion_revision=#{discussionRevision}, candidate_sync_state=#{candidateSyncState}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDesignerSessionProjection(DesignerSessionRow row);
    @Delete("DELETE FROM designer_session WHERE loop_draft_id=#{draftId}")
    int deleteDesignerSessionsByDraft(String draftId);
    @Select("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM designer_message WHERE designer_session_id=#{sessionId}")
    int nextDesignerMessageOrdinal(String sessionId);
    @Insert("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at,actor,requirement_revision,work_package_id) VALUES(#{id},#{designerSessionId},#{ordinal},#{role},#{content},#{deliveryState},#{createdAt},#{actor},#{requirementRevision},#{workPackageId})")
    int insertDesignerMessage(DesignerMessageRow row);
    @Select("SELECT * FROM designer_message WHERE designer_session_id=#{sessionId} ORDER BY ordinal")
    List<DesignerMessageRow> listDesignerMessages(String sessionId);
    @Select("SELECT * FROM designer_message WHERE id=#{id}")
    Optional<DesignerMessageRow> findDesignerMessage(String id);

    @Insert("""
            INSERT INTO design_discussion_revision(
              id,designer_session_id,requirement_revision,scope_key,work_package_id,revision,state,
              source_message_id,design_message_id,snapshot_markdown,decision_log_json,
              question_required,question_answered,question_retry_count,candidate_compilation_id,
              last_error_code,last_error_detail,created_at,updated_at,version)
            VALUES(#{id},#{designerSessionId},#{requirementRevision},#{scopeKey},#{workPackageId},#{revision},#{state},
              #{sourceMessageId},#{designMessageId},#{snapshotMarkdown},#{decisionLogJson},
              #{questionRequired},#{questionAnswered},#{questionRetryCount},#{candidateCompilationId},
              #{lastErrorCode},#{lastErrorDetail},#{createdAt},#{updatedAt},#{version})
            """)
    int insertDesignDiscussionRevision(DesignDiscussionRevisionRow row);
    @Select("SELECT * FROM design_discussion_revision WHERE id=#{id}")
    Optional<DesignDiscussionRevisionRow> findDesignDiscussionRevision(String id);
    @Select("""
            SELECT * FROM design_discussion_revision
            WHERE designer_session_id=#{sessionId} AND scope_key=#{scopeKey}
            ORDER BY revision DESC LIMIT 1
            """)
    Optional<DesignDiscussionRevisionRow> findLatestDesignDiscussionRevision(
            @Param("sessionId") String sessionId, @Param("scopeKey") String scopeKey);
    @Select("SELECT * FROM design_discussion_revision WHERE designer_session_id=#{sessionId} ORDER BY created_at, revision")
    List<DesignDiscussionRevisionRow> listDesignDiscussionRevisions(String sessionId);
    @Update("""
            UPDATE design_discussion_revision SET state=#{state},source_message_id=#{sourceMessageId},
              design_message_id=#{designMessageId},snapshot_markdown=#{snapshotMarkdown},
              decision_log_json=#{decisionLogJson},question_required=#{questionRequired},
              question_answered=#{questionAnswered},question_retry_count=#{questionRetryCount},
              candidate_compilation_id=#{candidateCompilationId},last_error_code=#{lastErrorCode},
              last_error_detail=#{lastErrorDetail},updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateDesignDiscussionRevision(DesignDiscussionRevisionRow row);
    @Update("UPDATE design_discussion_revision SET requirement_revision=#{requirementRevision} WHERE designer_session_id=#{sessionId} AND scope_key='REQUIREMENT' AND requirement_revision IS NULL")
    int bindOpenRequirementDiscussions(@Param("sessionId") String sessionId,
                                       @Param("requirementRevision") int requirementRevision);
    @Select("""
            SELECT message.* FROM designer_message message
            JOIN designer_session session ON session.id=message.designer_session_id
            WHERE session.loop_draft_id=#{draftId}
              AND message.actor='DESIGNER'
              AND message.delivery_state='PERSISTED'
              AND TRIM(message.content)<>''
            ORDER BY message.created_at DESC, message.ordinal DESC
            LIMIT 1
            """)
    Optional<DesignerMessageRow> findLatestPersistedDesignerMessageByDraft(String draftId);

    @Insert("""
            INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,
              requirement_text,requirement_segments_json,source_draft_version,state,model_calls_used,max_model_calls,
              created_at,updated_at,version,task_profile_id)
            VALUES(#{id},#{designerSessionId},#{revision},#{sourceMessageId},#{requirementText},
              #{requirementSegmentsJson},#{sourceDraftVersion},#{state},#{modelCallsUsed},#{maxModelCalls},
              #{createdAt},#{updatedAt},#{version},
              (SELECT id FROM designer_task_profile WHERE designer_session_id=#{designerSessionId} AND state='FROZEN' ORDER BY created_at DESC LIMIT 1))
            """)
    int insertDesignRequirementRevision(DesignRequirementRevisionRow row);
    @Select("SELECT * FROM design_requirement_revision WHERE id=#{id}")
    Optional<DesignRequirementRevisionRow> findDesignRequirementRevision(String id);
    @Select("SELECT * FROM design_requirement_revision WHERE designer_session_id=#{sessionId} ORDER BY revision DESC")
    List<DesignRequirementRevisionRow> listDesignRequirementRevisions(String sessionId);
    @Select("SELECT * FROM design_requirement_revision WHERE designer_session_id=#{sessionId} AND state<>'SUPERSEDED' ORDER BY revision DESC LIMIT 1")
    Optional<DesignRequirementRevisionRow> findCurrentDesignRequirementRevision(String sessionId);
    @Update("UPDATE design_requirement_revision SET source_draft_version=#{sourceDraftVersion},state=#{state},model_calls_used=#{modelCallsUsed},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDesignRequirementRevision(DesignRequirementRevisionRow row);

    @Insert("""
            INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,result_type,
              normalized_goal,global_constraints_json,plan_json,external_session_id,external_session_state,
              repair_count,transport_retry_count,source_draft_version,last_error_code,last_error_detail,
              created_at,updated_at,version,workflow_step,planning_json,planning_repair_count,
              planning_response_mode,planning_response_schema_id,planning_format_fallback_used,
              final_response_mode,final_response_schema_id,final_format_fallback_used,
              semantic_plan_json,format_repair_count,semantic_repair_count,server_compiled,task_profile_id)
            VALUES(#{id},#{designerSessionId},#{requirementRevisionId},#{state},#{resultType},#{normalizedGoal},
              #{globalConstraintsJson},#{planJson},#{externalSessionId},#{externalSessionState},#{repairCount},
              #{transportRetryCount},#{sourceDraftVersion},#{lastErrorCode},#{lastErrorDetail},
              #{createdAt},#{updatedAt},#{version},#{workflowStep},#{planningJson},#{planningRepairCount},
              #{planningResponseMode},#{planningResponseSchemaId},#{planningFormatFallbackUsed},
              #{finalResponseMode},#{finalResponseSchemaId},#{finalFormatFallbackUsed},
              #{semanticPlanJson},#{formatRepairCount},#{semanticRepairCount},#{serverCompiled},
              (SELECT id FROM designer_task_profile WHERE designer_session_id=#{designerSessionId} AND state='FROZEN' ORDER BY created_at DESC LIMIT 1))
            """)
    int insertTaskDecomposition(TaskDecompositionRow row);
    @Select("SELECT * FROM task_decomposition WHERE id=#{id}")
    Optional<TaskDecompositionRow> findTaskDecomposition(String id);
    @Select("SELECT * FROM task_decomposition WHERE designer_session_id=#{sessionId} ORDER BY created_at DESC LIMIT 1")
    Optional<TaskDecompositionRow> findLatestTaskDecomposition(String sessionId);
    @Select("SELECT * FROM task_decomposition WHERE requirement_revision_id=#{revisionId} ORDER BY created_at DESC LIMIT 1")
    Optional<TaskDecompositionRow> findTaskDecompositionByRevision(String revisionId);
    @Select("SELECT * FROM task_decomposition WHERE state='RUNNING' AND external_session_id IS NOT NULL ORDER BY updated_at")
    List<TaskDecompositionRow> activeTaskDecompositions();
    @Update("""
            UPDATE task_decomposition SET state=#{state},result_type=#{resultType},normalized_goal=#{normalizedGoal},
              global_constraints_json=#{globalConstraintsJson},plan_json=#{planJson},
              external_session_id=#{externalSessionId},external_session_state=#{externalSessionState},
              repair_count=#{repairCount},transport_retry_count=#{transportRetryCount},
              workflow_step=#{workflowStep},planning_json=#{planningJson},planning_repair_count=#{planningRepairCount},
              planning_response_mode=#{planningResponseMode},planning_response_schema_id=#{planningResponseSchemaId},
              planning_format_fallback_used=#{planningFormatFallbackUsed},
              final_response_mode=#{finalResponseMode},final_response_schema_id=#{finalResponseSchemaId},
              final_format_fallback_used=#{finalFormatFallbackUsed},
              semantic_plan_json=#{semanticPlanJson},format_repair_count=#{formatRepairCount},
              semantic_repair_count=#{semanticRepairCount},server_compiled=#{serverCompiled},
              last_error_code=#{lastErrorCode},last_error_detail=#{lastErrorDetail},
              updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}
            """)
    int updateTaskDecomposition(TaskDecompositionRow row);
    @Update("UPDATE task_decomposition SET state='SESSION_ERROR',external_session_state='ABORTED',last_error_code='DESIGNER_CANCELLED',last_error_detail='Designer session was cancelled',updated_at=#{updatedAt},version=version+1 WHERE designer_session_id=#{sessionId} AND state IN ('PENDING_HANDOFF','RUNNING','VALIDATING')")
    int stopTaskDecompositions(@Param("sessionId") String sessionId, @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO design_work_package(id,designer_session_id,requirement_revision_id,decomposition_id,
              package_id,ordinal,title,objective,scope_in_json,scope_out_json,dependencies_json,deliverables_json,
              acceptance_intent_json,requirement_refs_json,state,designer_external_session_id,
              designer_external_session_state,design_message_id,design_revision,redesign_count,
              designer_transport_retry_count,compiler_summary,handoff_summary,last_error_code,last_error_detail,
              approved_design_revision,discussion_round_count,invalidated_by_package_id,approved_at,
              created_at,updated_at,version,task_profile_id,role_pack_id,role_pack_version,
              plan_revision,correction_of_package_id,superseded_at)
            VALUES(#{id},#{designerSessionId},#{requirementRevisionId},#{decompositionId},#{packageId},#{ordinal},
              #{title},#{objective},#{scopeInJson},#{scopeOutJson},#{dependenciesJson},#{deliverablesJson},
              #{acceptanceIntentJson},#{requirementRefsJson},#{state},#{designerExternalSessionId},
              #{designerExternalSessionState},#{designMessageId},#{designRevision},#{redesignCount},
              #{designerTransportRetryCount},#{compilerSummary},#{handoffSummary},#{lastErrorCode},#{lastErrorDetail},
              #{approvedDesignRevision},#{discussionRoundCount},#{invalidatedByPackageId},#{approvedAt},
              #{createdAt},#{updatedAt},#{version},
              (SELECT id FROM designer_task_profile WHERE designer_session_id=#{designerSessionId} AND state='FROZEN' ORDER BY created_at DESC LIMIT 1),
              (SELECT role_pack_id FROM designer_task_profile WHERE designer_session_id=#{designerSessionId} AND state='FROZEN' ORDER BY created_at DESC LIMIT 1),
              (SELECT role_pack_version FROM designer_task_profile WHERE designer_session_id=#{designerSessionId} AND state='FROZEN' ORDER BY created_at DESC LIMIT 1),
              #{planRevision},#{correctionOfPackageId},#{supersededAt})
            """)
    int insertDesignWorkPackage(DesignWorkPackageRow row);
    @Update("""
            UPDATE design_work_package SET task_profile_id=#{taskProfileId},role_pack_id=#{rolePackId},
              role_pack_version=#{rolePackVersion},execution_strategy=#{executionStrategy},
              test_policy=#{testPolicy},technologies_json=#{technologiesJson},
              project_stack_profile_id=#{projectStackProfileId},component_keys_json=#{componentKeysJson},
              stack_fingerprint=#{stackFingerprint}
            WHERE id=#{id}
            """)
    int assignWorkPackageRoleProfile(WorkPackageRoleProfileRow row);
    @Select("""
            SELECT id,designer_session_id,package_id,task_profile_id,role_pack_id,role_pack_version,
              execution_strategy,test_policy,technologies_json,project_stack_profile_id,
              component_keys_json,stack_fingerprint FROM design_work_package
            WHERE id=#{id} AND role_pack_id IS NOT NULL AND role_pack_version IS NOT NULL
              AND execution_strategy IS NOT NULL AND test_policy IS NOT NULL
            """)
    Optional<WorkPackageRoleProfileRow> findWorkPackageRoleProfile(String id);
    @Select("SELECT * FROM design_work_package WHERE id=#{id}")
    Optional<DesignWorkPackageRow> findDesignWorkPackage(String id);
    @Select("SELECT * FROM design_work_package WHERE requirement_revision_id=#{revisionId} AND plan_revision=(SELECT MAX(plan_revision) FROM design_work_package WHERE requirement_revision_id=#{revisionId}) ORDER BY ordinal")
    List<DesignWorkPackageRow> listDesignWorkPackages(String revisionId);
    @Select("SELECT * FROM design_work_package WHERE designer_session_id=#{sessionId} AND package_id=#{packageId} ORDER BY plan_revision DESC,created_at DESC LIMIT 1")
    Optional<DesignWorkPackageRow> findLatestDesignWorkPackage(@Param("sessionId") String sessionId,
                                                               @Param("packageId") String packageId);
    @Select("SELECT * FROM design_work_package WHERE state IN ('QUESTIONING','DESIGNING') AND designer_external_session_id IS NOT NULL ORDER BY updated_at")
    List<DesignWorkPackageRow> activeDesignWorkPackages();
    @Update("""
            UPDATE design_work_package SET state=#{state},designer_external_session_id=#{designerExternalSessionId},
              designer_external_session_state=#{designerExternalSessionState},design_message_id=#{designMessageId},
              design_revision=#{designRevision},redesign_count=#{redesignCount},
              designer_transport_retry_count=#{designerTransportRetryCount},compiler_summary=#{compilerSummary},
              handoff_summary=#{handoffSummary},last_error_code=#{lastErrorCode},last_error_detail=#{lastErrorDetail},
              approved_design_revision=#{approvedDesignRevision},discussion_round_count=#{discussionRoundCount},
              invalidated_by_package_id=#{invalidatedByPackageId},approved_at=#{approvedAt},
              superseded_at=#{supersededAt},updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateDesignWorkPackage(DesignWorkPackageRow row);
    @Update("UPDATE design_work_package SET state='FAILED',designer_external_session_state='ABORTED',last_error_code='DESIGNER_CANCELLED',last_error_detail='Designer session was cancelled',updated_at=#{updatedAt},version=version+1 WHERE designer_session_id=#{sessionId} AND state IN ('QUESTIONING','DESIGNING','COMPILING','VALIDATING')")
    int stopDesignWorkPackages(@Param("sessionId") String sessionId, @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,
              external_session_id,external_session_state,repair_count,source_design_message_id,
              source_draft_version,last_error_code,last_error_detail,created_at,updated_at,version,
              work_package_id,transport_retry_count,compiled_package_json,workflow_step,planning_json,
              planning_repair_count,planning_response_mode,planning_response_schema_id,
              planning_format_fallback_used,final_response_mode,final_response_schema_id,
              final_format_fallback_used,semantic_plan_json,format_repair_count,
              semantic_repair_count,server_compiled)
            VALUES(#{id},#{designerSessionId},#{designRevision},#{state},#{externalSessionId},
              #{externalSessionState},#{repairCount},#{sourceDesignMessageId},#{sourceDraftVersion},
              #{lastErrorCode},#{lastErrorDetail},#{createdAt},#{updatedAt},#{version},
              #{workPackageId},#{transportRetryCount},#{compiledPackageJson},#{workflowStep},#{planningJson},
              #{planningRepairCount},#{planningResponseMode},#{planningResponseSchemaId},
              #{planningFormatFallbackUsed},#{finalResponseMode},#{finalResponseSchemaId},
              #{finalFormatFallbackUsed},#{semanticPlanJson},#{formatRepairCount},
              #{semanticRepairCount},#{serverCompiled})
            """)
    int insertLoopSpecCompilation(LoopSpecCompilationRow row);

    @Insert("""
            INSERT INTO design_acceptance_planning(compilation_id,designer_session_id,work_package_id,
              design_revision,contract_version,design_sha256,state,facts_json,capabilities_json,
              binding_source,binding_json,diagnostics_json,error_code,error_detail,created_at,updated_at,version)
            VALUES(#{compilationId},#{designerSessionId},#{workPackageId},#{designRevision},#{contractVersion},
              #{designSha256},#{state},#{factsJson},#{capabilitiesJson},#{bindingSource},#{bindingJson},#{diagnosticsJson},
              #{errorCode},#{errorDetail},#{createdAt},#{updatedAt},#{version})
            """)
    int insertDesignAcceptancePlanning(DesignAcceptancePlanningRow row);

    @Select("SELECT * FROM design_acceptance_planning WHERE compilation_id=#{compilationId}")
    Optional<DesignAcceptancePlanningRow> findDesignAcceptancePlanning(String compilationId);

    @Update("""
            UPDATE design_acceptance_planning SET state=#{state},binding_source=#{bindingSource},binding_json=#{bindingJson},
              diagnostics_json=#{diagnosticsJson},error_code=#{errorCode},error_detail=#{errorDetail},
              updated_at=#{updatedAt},version=version+1
            WHERE compilation_id=#{compilationId} AND version=#{version}
            """)
    int updateDesignAcceptancePlanning(DesignAcceptancePlanningRow row);

    @Select("SELECT * FROM loop_spec_compilation WHERE id=#{id}")
    Optional<LoopSpecCompilationRow> findLoopSpecCompilation(String id);
    @Select("SELECT * FROM loop_spec_compilation WHERE designer_session_id=#{sessionId} ORDER BY created_at DESC LIMIT 1")
    Optional<LoopSpecCompilationRow> findLatestLoopSpecCompilation(String sessionId);
    @Select("SELECT * FROM loop_spec_compilation WHERE designer_session_id=#{sessionId} AND work_package_id=#{packageId} ORDER BY created_at DESC LIMIT 1")
    Optional<LoopSpecCompilationRow> findLatestLoopSpecCompilationForPackage(@Param("sessionId") String sessionId,
                                                                             @Param("packageId") String packageId);
    @Select("SELECT * FROM loop_spec_compilation WHERE designer_session_id=#{sessionId} AND work_package_id=#{packageId} AND state='COMPLETED' ORDER BY design_revision DESC, created_at DESC LIMIT 1")
    Optional<LoopSpecCompilationRow> findLatestCompletedLoopSpecCompilationForPackage(
            @Param("sessionId") String sessionId, @Param("packageId") String packageId);
    @Select("SELECT * FROM loop_spec_compilation WHERE designer_session_id=#{sessionId} AND work_package_id=#{packageId} AND design_revision=#{designRevision} ORDER BY created_at DESC LIMIT 1")
    Optional<LoopSpecCompilationRow> findLoopSpecCompilationForPackageRevision(
            @Param("sessionId") String sessionId, @Param("packageId") String packageId,
            @Param("designRevision") int designRevision);
    @Select("""
            SELECT compilation.* FROM loop_spec_compilation compilation
            WHERE compilation.state IN ('PENDING_HANDOFF','RUNNING')
              AND (compilation.external_session_id IS NOT NULL
                OR compilation.external_session_state='SERVER_DIRECT'
                OR EXISTS (
                  SELECT 1 FROM design_acceptance_planning planning
                  WHERE planning.compilation_id=compilation.id
                    AND planning.binding_source='SERVER_STAGE_HINTS'))
            ORDER BY compilation.updated_at
            """)
    List<LoopSpecCompilationRow> activeLoopSpecCompilations();
    @Update("""
            UPDATE loop_spec_compilation SET state=#{state},external_session_id=#{externalSessionId},
              external_session_state=#{externalSessionState},repair_count=#{repairCount},
              transport_retry_count=#{transportRetryCount},
              compiled_package_json=#{compiledPackageJson},
              workflow_step=#{workflowStep},planning_json=#{planningJson},planning_repair_count=#{planningRepairCount},
              planning_response_mode=#{planningResponseMode},planning_response_schema_id=#{planningResponseSchemaId},
              planning_format_fallback_used=#{planningFormatFallbackUsed},
              final_response_mode=#{finalResponseMode},final_response_schema_id=#{finalResponseSchemaId},
              final_format_fallback_used=#{finalFormatFallbackUsed},
              semantic_plan_json=#{semanticPlanJson},format_repair_count=#{formatRepairCount},
              semantic_repair_count=#{semanticRepairCount},server_compiled=#{serverCompiled},
              last_error_code=#{lastErrorCode},last_error_detail=#{lastErrorDetail},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateLoopSpecCompilation(LoopSpecCompilationRow row);
    @Update("UPDATE loop_spec_compilation SET state='SESSION_ERROR',external_session_state='ABORTED',last_error_code='DESIGNER_CANCELLED',last_error_detail='Designer session was cancelled',updated_at=#{updatedAt},version=version+1 WHERE designer_session_id=#{sessionId} AND state IN ('PENDING_HANDOFF','RUNNING')")
    int stopLoopSpecCompilations(@Param("sessionId") String sessionId, @Param("updatedAt") String updatedAt);

    @Select("""
            SELECT external_session_id FROM designer_session
            WHERE id=#{sessionId} AND external_session_id IS NOT NULL
            UNION SELECT external_session_id FROM task_profile_router_run
            WHERE designer_session_id=#{sessionId} AND state IN ('PENDING','RUNNING')
              AND external_session_id IS NOT NULL
            UNION SELECT external_session_id FROM task_decomposition
            WHERE designer_session_id=#{sessionId} AND state IN ('PENDING_HANDOFF','RUNNING')
              AND external_session_id IS NOT NULL
            UNION SELECT designer_external_session_id FROM design_work_package
            WHERE designer_session_id=#{sessionId} AND state IN ('QUESTIONING','DESIGNING')
              AND designer_external_session_id IS NOT NULL
            UNION SELECT external_session_id FROM loop_spec_compilation
            WHERE designer_session_id=#{sessionId} AND state IN ('PENDING_HANDOFF','RUNNING')
              AND external_session_id IS NOT NULL
            UNION SELECT external_session_id FROM analysis_report
            WHERE designer_session_id=#{sessionId} AND state IN ('RUNNING','VALIDATING')
              AND external_session_id IS NOT NULL
            """)
    List<String> listDesignerRemoteSessionIds(@Param("sessionId") String sessionId);


}
