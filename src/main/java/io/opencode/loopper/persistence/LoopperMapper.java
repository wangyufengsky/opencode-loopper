package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LoopperMapper {
    @Insert("""
            INSERT INTO local_sync_conflict_session(
              id,task_id,source_root,baseline_commit,task_commit,source_head,state,conflict_count,resolved_count,
              backup_dir,recovery_log_json,verification_evidence_json,error_message,created_at,updated_at,version)
            VALUES(#{id},#{taskId},#{sourceRoot},#{baselineCommit},#{taskCommit},#{sourceHead},#{state},
              #{conflictCount},#{resolvedCount},#{backupDir},#{recoveryLogJson},#{verificationEvidenceJson},
              #{errorMessage},#{createdAt},#{updatedAt},#{version})
            """)
    int insertLocalSyncConflictSession(LocalSyncConflictSessionRow row);
    @Select("SELECT * FROM local_sync_conflict_session WHERE id=#{id}")
    Optional<LocalSyncConflictSessionRow> findLocalSyncConflictSession(String id);
    @Select("""
            SELECT * FROM local_sync_conflict_session
            WHERE task_id=#{taskId} AND state IN ('OPEN','READY','APPLYING','VERIFYING','STALE','ROLLED_BACK','ROLLBACK_FAILED')
            ORDER BY updated_at DESC LIMIT 1
            """)
    Optional<LocalSyncConflictSessionRow> findActiveLocalSyncConflictSession(String taskId);
    @Select("SELECT * FROM local_sync_conflict_session WHERE state IN ('APPLYING','VERIFYING') ORDER BY updated_at")
    List<LocalSyncConflictSessionRow> recoverableLocalSyncConflictSessions();
    @Update("""
            UPDATE local_sync_conflict_session SET state=#{state},conflict_count=#{conflictCount},
              resolved_count=#{resolvedCount},backup_dir=#{backupDir},recovery_log_json=#{recoveryLogJson},
              verification_evidence_json=#{verificationEvidenceJson},error_message=#{errorMessage},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateLocalSyncConflictSession(LocalSyncConflictSessionRow row);
    @Insert("""
            INSERT INTO local_sync_conflict_file(
              id,session_id,path,source_path,task_path,change_type,content_type,
              base_hash,source_hash,task_hash,base_mode,source_mode,task_mode,
              base_content,source_content,task_content,merged_content,resolution,resolved_content,
              ai_suggestion,ai_suggestion_hash,external_dir,created_at,updated_at,version)
            VALUES(#{id},#{sessionId},#{path},#{sourcePath},#{taskPath},#{changeType},#{contentType},
              #{baseHash},#{sourceHash},#{taskHash},#{baseMode},#{sourceMode},#{taskMode},
              #{baseContent},#{sourceContent},#{taskContent},#{mergedContent},#{resolution},#{resolvedContent},
              #{aiSuggestion},#{aiSuggestionHash},#{externalDir},#{createdAt},#{updatedAt},#{version})
            """)
    int insertLocalSyncConflictFile(LocalSyncConflictFileRow row);
    @Select("SELECT * FROM local_sync_conflict_file WHERE session_id=#{sessionId} ORDER BY path")
    List<LocalSyncConflictFileRow> listLocalSyncConflictFiles(String sessionId);
    @Select("SELECT * FROM local_sync_conflict_file WHERE session_id=#{sessionId} AND path=#{path}")
    Optional<LocalSyncConflictFileRow> findLocalSyncConflictFile(@Param("sessionId") String sessionId,
                                                                 @Param("path") String path);
    @Update("""
            UPDATE local_sync_conflict_file SET resolution=#{resolution},resolved_content=#{resolvedContent},
              ai_suggestion=#{aiSuggestion},ai_suggestion_hash=#{aiSuggestionHash},updated_at=#{updatedAt},
              version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateLocalSyncConflictFile(LocalSyncConflictFileRow row);

    @Insert("""
            INSERT INTO state_transition_event(
              id,machine_type,entity_id,scope_type,scope_id,event,from_state,to_state,reason_code,metadata_json,occurred_at)
            VALUES(#{id},#{machineType},#{entityId},#{scopeType},#{scopeId},#{event},#{fromState},#{toState},
              #{reasonCode},#{metadataJson},#{occurredAt})
            """)
    int insertStateTransitionEvent(StateTransitionEventRow row);
    @Select("""
            SELECT * FROM state_transition_event
            WHERE machine_type=#{machineType} AND entity_id=#{entityId} AND sequence>#{afterSequence}
            ORDER BY sequence LIMIT #{limit}
            """)
    List<StateTransitionEventRow> listStateTransitionsForEntity(
            @Param("machineType") String machineType, @Param("entityId") String entityId,
            @Param("afterSequence") long afterSequence, @Param("limit") int limit);
    @Select("""
            SELECT * FROM state_transition_event
            WHERE scope_type=#{scopeType} AND scope_id=#{scopeId} AND sequence>#{afterSequence}
            ORDER BY sequence LIMIT #{limit}
            """)
    List<StateTransitionEventRow> listStateTransitionsForScope(
            @Param("scopeType") String scopeType, @Param("scopeId") String scopeId,
            @Param("afterSequence") long afterSequence, @Param("limit") int limit);
    @Delete("DELETE FROM state_transition_event WHERE scope_type=#{scopeType} AND scope_id=#{scopeId}")
    int deleteStateTransitionsForScope(@Param("scopeType") String scopeType, @Param("scopeId") String scopeId);

    @Delete("DELETE FROM local_sync_conflict_file WHERE session_id IN (SELECT id FROM local_sync_conflict_session WHERE task_id=#{taskId})")
    int deleteLocalSyncConflictFilesForTask(String taskId);
    @Delete("DELETE FROM local_sync_conflict_session WHERE task_id=#{taskId}")
    int deleteLocalSyncConflictSessionsForTask(String taskId);
    @Delete("DELETE FROM session_todo WHERE execution_session_id IN (SELECT id FROM execution_session WHERE task_id=#{taskId})")
    int deleteSessionTodosForTask(String taskId);
    @Delete("DELETE FROM session_checkpoint WHERE task_id=#{taskId}")
    int deleteSessionCheckpointsForTask(String taskId);
    @Delete("DELETE FROM session_usage WHERE task_id=#{taskId}")
    int deleteSessionUsageForTask(String taskId);
    @Delete("DELETE FROM binary_artifact WHERE task_id=#{taskId}")
    int deleteBinaryArtifactsForTask(String taskId);
    @Delete("DELETE FROM task_artifact WHERE task_id=#{taskId}")
    int deleteTaskArtifactsForTask(String taskId);
    @Delete("DELETE FROM verification_result WHERE attempt_id IN (SELECT id FROM attempt WHERE task_id=#{taskId})")
    int deleteVerificationResultsForTask(String taskId);
    @Delete("DELETE FROM verifier_runtime WHERE task_id=#{taskId}")
    int deleteVerifierRuntimesForTask(String taskId);
    @Delete("DELETE FROM interaction WHERE task_id=#{taskId}")
    int deleteInteractionsForTask(String taskId);
    @Delete("DELETE FROM error_event WHERE task_id=#{taskId}")
    int deleteErrorsForTask(String taskId);
    @Delete("DELETE FROM task_event WHERE task_id=#{taskId}")
    int deleteEventsForTask(String taskId);
    @Delete("DELETE FROM judge_run WHERE task_id=#{taskId}")
    int deleteJudgeRunsForTask(String taskId);
    @Update("UPDATE workspace_lease SET writer_session_id=NULL WHERE writer_session_id IN (SELECT id FROM execution_session WHERE task_id=#{taskId})")
    int detachWorkspaceLeaseWriterSessions(String taskId);
    @Delete("DELETE FROM execution_session WHERE task_id=#{taskId}")
    int deleteExecutionSessionsForTask(String taskId);
    @Delete("DELETE FROM attempt WHERE task_id=#{taskId}")
    int deleteAttemptsForTask(String taskId);
    @Delete("DELETE FROM task_lineage WHERE child_task_id=#{taskId}")
    int deleteTaskLineageForChild(String taskId);
    @Delete("DELETE FROM task_retry_schedule WHERE task_id=#{taskId}")
    int deleteTaskRetrySchedulesForTask(String taskId);
    @Delete("DELETE FROM stage WHERE task_id=#{taskId}")
    int deleteStagesForTask(String taskId);
    @Delete("DELETE FROM task_queue WHERE task_id=#{taskId}")
    int deleteTaskQueueEntry(String taskId);
    @Delete("DELETE FROM task_archive WHERE task_id=#{taskId}")
    int deleteTaskArchiveEntry(String taskId);
    @Update("UPDATE automation_run SET task_id=NULL WHERE task_id=#{taskId}")
    int detachAutomationRunsFromTask(String taskId);

    @Select("SELECT * FROM app_settings WHERE id=1")
    Optional<AppSettingsRow> findAppSettings();
    @Insert("""
            INSERT INTO app_settings(id,cli_path,allowed_root,provider_id,model_id,max_task_attempts,attempt_timeout_minutes,auto_approve,settings_json,updated_at)
            VALUES(1,#{cliPath},#{allowedRoot},#{providerId},#{modelId},#{maxTaskAttempts},#{attemptTimeoutMinutes},#{autoApprove},#{settingsJson},#{updatedAt})
            ON CONFLICT(id) DO UPDATE SET
              cli_path=excluded.cli_path,
              allowed_root=excluded.allowed_root,
              provider_id=excluded.provider_id,
              model_id=excluded.model_id,
              max_task_attempts=excluded.max_task_attempts,
              attempt_timeout_minutes=excluded.attempt_timeout_minutes,
              auto_approve=excluded.auto_approve,
              settings_json=excluded.settings_json,
              updated_at=excluded.updated_at
            """)
    int upsertAppSettings(AppSettingsRow row);

    @Insert("""
            INSERT INTO task_retry_schedule(id,task_id,stage_id,cause,ordinal,delay_seconds,due_at,
              remaining_seconds,prompt,state,created_at,updated_at,version)
            VALUES(#{id},#{taskId},#{stageId},#{cause},#{ordinal},#{delaySeconds},#{dueAt},
              #{remainingSeconds},#{prompt},#{state},#{createdAt},#{updatedAt},#{version})
            """)
    int insertTaskRetrySchedule(TaskRetryScheduleRow row);
    @Select("SELECT * FROM task_retry_schedule WHERE id=#{id}")
    Optional<TaskRetryScheduleRow> findTaskRetrySchedule(String id);
    @Select("""
            SELECT * FROM task_retry_schedule
            WHERE task_id=#{taskId} AND state IN ('SCHEDULED','PAUSED')
            ORDER BY created_at DESC LIMIT 1
            """)
    Optional<TaskRetryScheduleRow> findActiveTaskRetrySchedule(String taskId);
    @Select("""
            SELECT * FROM task_retry_schedule
            WHERE state='SCHEDULED' AND due_at<=#{dueAt}
            ORDER BY due_at,id LIMIT #{limit}
            """)
    List<TaskRetryScheduleRow> listDueTaskRetrySchedules(@Param("dueAt") String dueAt, @Param("limit") int limit);
    @Select("""
            SELECT COUNT(*) FROM task_retry_schedule
            WHERE task_id=#{taskId} AND stage_id=#{stageId} AND cause=#{cause}
            """)
    int countTaskRetrySchedules(@Param("taskId") String taskId, @Param("stageId") String stageId,
                                @Param("cause") String cause);
    @Update("""
            UPDATE task_retry_schedule SET state=#{state},due_at=#{dueAt},remaining_seconds=#{remainingSeconds},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateTaskRetrySchedule(TaskRetryScheduleRow row);

    @Insert("INSERT INTO project(id,name,root_path,description,created_at,updated_at,managed,version) VALUES(#{id},#{name},#{rootPath},#{description},#{createdAt},#{updatedAt},#{managed},#{version})")
    int insertProject(ProjectRow row);
    @Select("SELECT * FROM project WHERE id=#{id}") Optional<ProjectRow> findProject(String id);
    @Select("SELECT * FROM project WHERE root_path=#{rootPath}") Optional<ProjectRow> findProjectByRoot(String rootPath);
    @Select("SELECT * FROM project WHERE managed=1 ORDER BY created_at DESC") List<ProjectRow> listProjects();
    @Select("SELECT COUNT(*) FROM task WHERE project_id=#{projectId}") int countTasksForProject(String projectId);
    @Select("""
            SELECT COUNT(*) FROM designer_session s
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
            """)
    int countOpenDesignerSessionsForProject(String projectId);
    @Update("UPDATE project SET name=#{name}, description=#{description}, updated_at=#{updatedAt}, managed=#{managed}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateProject(ProjectRow row);
    @Update("UPDATE project SET managed=0, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND managed=1")
    int unmanageProject(@Param("id") String id, @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO project_convention_draft(
              id,project_id,state,external_session_id,external_session_state,
              source_exists,source_sha256,source_content,proposed_content,normalization_notice,error_message,
              created_at,updated_at,version)
            VALUES(#{id},#{projectId},#{state},#{externalSessionId},#{externalSessionState},
              #{sourceExists},#{sourceSha256},#{sourceContent},#{proposedContent},#{normalizationNotice},#{errorMessage},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertProjectConventionDraft(ProjectConventionDraftRow row);
    @Select("SELECT * FROM project_convention_draft WHERE id=#{id}")
    Optional<ProjectConventionDraftRow> findProjectConventionDraft(String id);
    @Select("""
            SELECT * FROM project_convention_draft
            WHERE project_id=#{projectId} AND state IN ('RUNNING','APPLYING')
            ORDER BY created_at DESC LIMIT 1
            """)
    Optional<ProjectConventionDraftRow> activeProjectConventionDraft(String projectId);
    @Select("""
            SELECT * FROM project_convention_draft
            WHERE (state='RUNNING' AND external_session_id IS NOT NULL) OR state='APPLYING'
            ORDER BY updated_at
            """)
    List<ProjectConventionDraftRow> activeProjectConventionDrafts();
    @Update("""
            UPDATE project_convention_draft SET
              state=#{state}, external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState},
              proposed_content=#{proposedContent}, normalization_notice=#{normalizationNotice},
              error_message=#{errorMessage}, updated_at=#{updatedAt}, version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateProjectConventionDraft(ProjectConventionDraftRow row);
    @Update("""
            UPDATE project_convention_draft SET
              external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState},
              proposed_content=#{proposedContent}, normalization_notice=#{normalizationNotice},
              error_message=#{errorMessage}, updated_at=#{updatedAt}, version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateProjectConventionProjection(ProjectConventionDraftRow row);

    @Insert("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at,version) VALUES(#{id},#{projectId},#{goal},#{specJson},#{status},#{createdAt},#{updatedAt},#{version})")
    int insertDraft(LoopDraftRow row);
    @Select("SELECT * FROM loop_draft WHERE id=#{id}") Optional<LoopDraftRow> findDraft(String id);
    @Update("UPDATE loop_draft SET goal=#{goal}, spec_json=#{specJson}, status=#{status}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDraft(LoopDraftRow row);
    @Update("UPDATE loop_draft SET goal=#{goal}, spec_json=#{specJson}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDraftContent(LoopDraftRow row);
    @Delete("DELETE FROM loop_draft WHERE id=#{id}")
    int deleteDraft(String id);

    @Delete("DELETE FROM loop_spec_compilation WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteLoopSpecCompilationsByDraft(String draftId);
    @Delete("DELETE FROM design_discussion_revision WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignDiscussionRevisionsByDraft(String draftId);
    @Delete("DELETE FROM design_work_package WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignWorkPackagesByDraft(String draftId);
    @Delete("DELETE FROM task_decomposition WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteTaskDecompositionsByDraft(String draftId);
    @Delete("DELETE FROM design_requirement_revision WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignRequirementRevisionsByDraft(String draftId);
    @Delete("DELETE FROM designer_message WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignerMessagesByDraft(String draftId);
    @Delete("DELETE FROM interaction WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignerInteractionsByDraft(String draftId);
    @Update("UPDATE automation_run SET draft_id=NULL WHERE draft_id=#{draftId}")
    int detachAutomationRunsFromDraft(String draftId);

    @Insert("INSERT INTO designer_session(id,project_id,state,access_mode,external_session_id,external_session_state,loop_draft_id,workflow_phase,design_revision,redesign_count,current_requirement_revision,active_work_package_id,discussion_scope,discussion_revision,candidate_sync_state,created_at,updated_at,version) VALUES(#{id},#{projectId},#{state},#{accessMode},#{externalSessionId},#{externalSessionState},#{loopDraftId},#{workflowPhase},#{designRevision},#{redesignCount},#{currentRequirementRevision},#{activeWorkPackageId},#{discussionScope},#{discussionRevision},#{candidateSyncState},#{createdAt},#{updatedAt},#{version})")
    int insertDesignerSession(DesignerSessionRow row);
    @Select("SELECT * FROM designer_session WHERE id=#{id}") Optional<DesignerSessionRow> findDesignerSession(String id);
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
              created_at,updated_at,version)
            VALUES(#{id},#{designerSessionId},#{revision},#{sourceMessageId},#{requirementText},
              #{requirementSegmentsJson},#{sourceDraftVersion},#{state},#{modelCallsUsed},#{maxModelCalls},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertDesignRequirementRevision(DesignRequirementRevisionRow row);
    @Select("SELECT * FROM design_requirement_revision WHERE id=#{id}")
    Optional<DesignRequirementRevisionRow> findDesignRequirementRevision(String id);
    @Select("SELECT * FROM design_requirement_revision WHERE designer_session_id=#{sessionId} ORDER BY revision DESC")
    List<DesignRequirementRevisionRow> listDesignRequirementRevisions(String sessionId);
    @Select("SELECT * FROM design_requirement_revision WHERE designer_session_id=#{sessionId} AND state<>'SUPERSEDED' ORDER BY revision DESC LIMIT 1")
    Optional<DesignRequirementRevisionRow> findCurrentDesignRequirementRevision(String sessionId);
    @Update("UPDATE design_requirement_revision SET state=#{state},model_calls_used=#{modelCallsUsed},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDesignRequirementRevision(DesignRequirementRevisionRow row);

    @Insert("""
            INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,result_type,
              normalized_goal,global_constraints_json,plan_json,external_session_id,external_session_state,
              repair_count,transport_retry_count,source_draft_version,last_error_code,last_error_detail,
              created_at,updated_at,version,workflow_step,planning_json,planning_repair_count,
              planning_response_mode,planning_response_schema_id,planning_format_fallback_used,
              final_response_mode,final_response_schema_id,final_format_fallback_used,
              semantic_plan_json,format_repair_count,semantic_repair_count,server_compiled)
            VALUES(#{id},#{designerSessionId},#{requirementRevisionId},#{state},#{resultType},#{normalizedGoal},
              #{globalConstraintsJson},#{planJson},#{externalSessionId},#{externalSessionState},#{repairCount},
              #{transportRetryCount},#{sourceDraftVersion},#{lastErrorCode},#{lastErrorDetail},
              #{createdAt},#{updatedAt},#{version},#{workflowStep},#{planningJson},#{planningRepairCount},
              #{planningResponseMode},#{planningResponseSchemaId},#{planningFormatFallbackUsed},
              #{finalResponseMode},#{finalResponseSchemaId},#{finalFormatFallbackUsed},
              #{semanticPlanJson},#{formatRepairCount},#{semanticRepairCount},#{serverCompiled})
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

    @Insert("""
            INSERT INTO design_work_package(id,designer_session_id,requirement_revision_id,decomposition_id,
              package_id,ordinal,title,objective,scope_in_json,scope_out_json,dependencies_json,deliverables_json,
              acceptance_intent_json,requirement_refs_json,state,designer_external_session_id,
              designer_external_session_state,design_message_id,design_revision,redesign_count,
              designer_transport_retry_count,compiler_summary,handoff_summary,last_error_code,last_error_detail,
              approved_design_revision,discussion_round_count,invalidated_by_package_id,approved_at,
              created_at,updated_at,version)
            VALUES(#{id},#{designerSessionId},#{requirementRevisionId},#{decompositionId},#{packageId},#{ordinal},
              #{title},#{objective},#{scopeInJson},#{scopeOutJson},#{dependenciesJson},#{deliverablesJson},
              #{acceptanceIntentJson},#{requirementRefsJson},#{state},#{designerExternalSessionId},
              #{designerExternalSessionState},#{designMessageId},#{designRevision},#{redesignCount},
              #{designerTransportRetryCount},#{compilerSummary},#{handoffSummary},#{lastErrorCode},#{lastErrorDetail},
              #{approvedDesignRevision},#{discussionRoundCount},#{invalidatedByPackageId},#{approvedAt},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertDesignWorkPackage(DesignWorkPackageRow row);
    @Select("SELECT * FROM design_work_package WHERE id=#{id}")
    Optional<DesignWorkPackageRow> findDesignWorkPackage(String id);
    @Select("SELECT * FROM design_work_package WHERE requirement_revision_id=#{revisionId} ORDER BY ordinal")
    List<DesignWorkPackageRow> listDesignWorkPackages(String revisionId);
    @Select("SELECT * FROM design_work_package WHERE designer_session_id=#{sessionId} AND package_id=#{packageId} ORDER BY created_at DESC LIMIT 1")
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
              updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}
            """)
    int updateDesignWorkPackage(DesignWorkPackageRow row);

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
    @Select("SELECT * FROM loop_spec_compilation WHERE state='RUNNING' AND external_session_id IS NOT NULL ORDER BY updated_at")
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

    @Insert("INSERT INTO task(id,project_id,loop_draft_id,title,state,worktree_path,branch_name,source_branch,baseline_commit,created_at,updated_at,version) VALUES(#{id},#{projectId},#{loopDraftId},#{title},#{state},#{worktreePath},#{branchName},#{sourceBranch},#{baselineCommit},#{createdAt},#{updatedAt},#{version})")
    int insertTask(TaskRow row);
    @Select("SELECT * FROM task WHERE id=#{id}") Optional<TaskRow> findTask(String id);
    @Select("SELECT * FROM task WHERE loop_draft_id=#{draftId} ORDER BY created_at DESC LIMIT 1") Optional<TaskRow> findTaskByDraft(String draftId);
    @Select("SELECT * FROM task ORDER BY created_at DESC") List<TaskRow> listTasks();
    @Select("SELECT COUNT(*) > 0 FROM task_archive WHERE task_id=#{taskId}") boolean isTaskArchived(String taskId);
    @Insert("INSERT INTO task_archive(task_id,archived_at) VALUES(#{taskId},#{archivedAt}) ON CONFLICT(task_id) DO UPDATE SET archived_at=excluded.archived_at")
    int archiveTask(@Param("taskId") String taskId, @Param("archivedAt") String archivedAt);
    @org.apache.ibatis.annotations.Delete("DELETE FROM task_archive WHERE task_id=#{taskId}")
    int restoreTask(String taskId);
    @Delete("DELETE FROM task WHERE id=#{id}")
    int deleteTask(String id);
    @Select("SELECT * FROM task WHERE state IN ('PREPARING','RUNNING','VERIFYING','RETRY_WAIT','JUDGING') ORDER BY created_at") List<TaskRow> listRecoverableTasks();
    @Update("UPDATE task SET state=#{state}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateTaskState(TaskRow row);
    @Update("UPDATE task SET worktree_path=#{worktreePath}, branch_name=#{branchName}, source_branch=#{sourceBranch}, baseline_commit=#{baselineCommit}, state=#{state}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int prepareTask(TaskRow row);

    @Insert("""
            INSERT INTO task_publication(task_id,state,remote_name,remote_url,provider,source_branch,target_branch,
              task_commit_sha,commit_message,creation_requested_at,merge_request_iid,merge_request_url,
              merge_request_state,merge_request_head_sha,merge_commit_sha,merge_request_opened_at,merged_at,
              last_checked_at,last_check_error,created_at,updated_at,version)
            VALUES(#{taskId},#{state},#{remoteName},#{remoteUrl},#{provider},#{sourceBranch},#{targetBranch},
              #{taskCommitSha},#{commitMessage},#{creationRequestedAt},#{mergeRequestIid},#{mergeRequestUrl},
              #{mergeRequestState},#{mergeRequestHeadSha},#{mergeCommitSha},#{mergeRequestOpenedAt},#{mergedAt},
              #{lastCheckedAt},#{lastCheckError},#{createdAt},#{updatedAt},#{version})
            """)
    int insertTaskPublication(TaskPublicationRow row);
    @Select("SELECT * FROM task_publication WHERE task_id=#{taskId}") Optional<TaskPublicationRow> findTaskPublication(String taskId);
    @Update("""
            UPDATE task_publication SET state=#{state},remote_name=#{remoteName},remote_url=#{remoteUrl},
              provider=#{provider},source_branch=#{sourceBranch},target_branch=#{targetBranch},
              task_commit_sha=#{taskCommitSha},commit_message=#{commitMessage},creation_requested_at=#{creationRequestedAt},
              merge_request_iid=#{mergeRequestIid},merge_request_url=#{mergeRequestUrl},merge_request_state=#{mergeRequestState},
              merge_request_head_sha=#{mergeRequestHeadSha},merge_commit_sha=#{mergeCommitSha},
              merge_request_opened_at=#{mergeRequestOpenedAt},merged_at=#{mergedAt},last_checked_at=#{lastCheckedAt},
              last_check_error=#{lastCheckError},updated_at=#{updatedAt},version=version+1
            WHERE task_id=#{taskId} AND version=#{version}
            """)
    int updateTaskPublication(TaskPublicationRow row);
    @Delete("DELETE FROM task_publication WHERE task_id=#{taskId}") int deleteTaskPublicationForTask(String taskId);

    @Insert("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at,version,work_package_id) VALUES(#{id},#{taskId},#{ordinal},#{objective},#{allowedPathsJson},#{forbiddenPathsJson},#{deliverablesJson},#{verifiersJson},#{state},#{createdAt},#{updatedAt},#{version},#{workPackageId})")
    int insertStage(StageRow row);
    @Select("SELECT * FROM stage WHERE id=#{id}") Optional<StageRow> findStage(String id);
    @Select("SELECT * FROM stage WHERE task_id=#{taskId} ORDER BY ordinal") List<StageRow> listStages(String taskId);
    @Update("UPDATE stage SET state=#{state}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateStageState(StageRow row);

    @Insert("INSERT INTO stage_java_baseline(stage_id,task_id,snapshot_json,snapshot_sha256,created_at) VALUES(#{stageId},#{taskId},#{snapshotJson},#{snapshotSha256},#{createdAt}) ON CONFLICT(stage_id) DO NOTHING")
    int insertStageJavaBaseline(StageJavaBaselineRow row);
    @Select("SELECT * FROM stage_java_baseline WHERE stage_id=#{stageId}")
    Optional<StageJavaBaselineRow> findStageJavaBaseline(String stageId);

    @Insert("INSERT INTO stage_workspace_baseline(stage_id,task_id,baseline_ref,created_at) VALUES(#{stageId},#{taskId},#{baselineRef},#{createdAt}) ON CONFLICT(stage_id) DO NOTHING")
    int insertStageWorkspaceBaseline(StageWorkspaceBaselineRow row);
    @Select("SELECT * FROM stage_workspace_baseline WHERE stage_id=#{stageId}")
    Optional<StageWorkspaceBaselineRow> findStageWorkspaceBaseline(String stageId);

    @Insert("INSERT INTO attempt(id,task_id,stage_id,execution_cycle_id,ordinal,state,failure_kind,summary,created_at,ended_at,version) VALUES(#{id},#{taskId},#{stageId},#{executionCycleId},#{ordinal},#{state},#{failureKind},#{summary},#{createdAt},#{endedAt},#{version})")
    int insertAttempt(AttemptRow row);
    @Select("SELECT * FROM attempt WHERE id=#{id}") Optional<AttemptRow> findAttempt(String id);
    @Select("SELECT * FROM attempt WHERE task_id=#{taskId} ORDER BY created_at DESC") List<AttemptRow> listAttempts(String taskId);
    @Select("SELECT * FROM attempt WHERE stage_id=#{stageId} ORDER BY ordinal DESC LIMIT 1") Optional<AttemptRow> latestAttempt(String stageId);
    @Select("SELECT COUNT(*) FROM attempt WHERE stage_id=#{stageId}") int countAttemptsForStage(String stageId);
    @Select("SELECT COUNT(*) FROM attempt WHERE task_id=#{taskId}") int countAttemptsForTask(String taskId);
    @Select("SELECT COUNT(*) FROM attempt WHERE execution_cycle_id=#{cycleId} AND stage_id=#{stageId}")
    int countAttemptsForCycleStage(@Param("cycleId") String cycleId, @Param("stageId") String stageId);
    @Select("SELECT COUNT(*) FROM attempt WHERE execution_cycle_id=#{cycleId}")
    int countAttemptsForCycle(String cycleId);
    @Select("SELECT COUNT(*) FROM attempt a JOIN stage s ON s.id=a.stage_id WHERE a.execution_cycle_id=#{cycleId} AND s.work_package_id=#{packageId}")
    int countAttemptsForCycleWorkPackage(@Param("cycleId") String cycleId, @Param("packageId") String packageId);
    @Select("SELECT COUNT(*) FROM attempt a JOIN stage s ON s.id=a.stage_id WHERE a.task_id=#{taskId} AND s.work_package_id=#{packageId}")
    int countAttemptsForWorkPackage(@Param("taskId") String taskId, @Param("packageId") String packageId);
    @Select("SELECT COUNT(*) FROM attempt WHERE stage_id=#{stageId} AND state='SESSION_ERROR'") int countSessionErrorsForStage(String stageId);
    @Update("UPDATE attempt SET state=#{state}, failure_kind=#{failureKind}, summary=#{summary}, ended_at=#{endedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int finishAttempt(AttemptRow row);

    @Insert("""
            INSERT INTO task_execution_cycle(id,task_id,ordinal,kind,state,start_stage_id,start_stage_ordinal,
              supplemental_prompt,budget_json,failure_code,failure_message,authorized_at,started_at,ended_at,version)
            VALUES(#{id},#{taskId},#{ordinal},#{kind},#{state},#{startStageId},#{startStageOrdinal},
              #{supplementalPrompt},#{budgetJson},#{failureCode},#{failureMessage},#{authorizedAt},#{startedAt},#{endedAt},#{version})
            """)
    int insertTaskExecutionCycle(TaskExecutionCycleRow row);
    @Select("SELECT * FROM task_execution_cycle WHERE id=#{id}") Optional<TaskExecutionCycleRow> findTaskExecutionCycle(String id);
    @Select("SELECT * FROM task_execution_cycle WHERE task_id=#{taskId} ORDER BY ordinal DESC")
    List<TaskExecutionCycleRow> listTaskExecutionCycles(String taskId);
    @Select("SELECT * FROM task_execution_cycle WHERE task_id=#{taskId} ORDER BY ordinal DESC LIMIT 1")
    Optional<TaskExecutionCycleRow> latestTaskExecutionCycle(String taskId);
    @Select("SELECT * FROM task_execution_cycle WHERE task_id=#{taskId} AND state='RUNNING' LIMIT 1")
    Optional<TaskExecutionCycleRow> activeTaskExecutionCycle(String taskId);
    @Select("SELECT COALESCE(MAX(ordinal),0) FROM task_execution_cycle WHERE task_id=#{taskId}")
    int maxTaskExecutionCycleOrdinal(String taskId);
    @Update("""
            UPDATE task_execution_cycle SET state=#{state},failure_code=#{failureCode},failure_message=#{failureMessage},
              ended_at=#{endedAt},version=version+1 WHERE id=#{id} AND version=#{version}
            """)
    int updateTaskExecutionCycle(TaskExecutionCycleRow row);

    @Insert("""
            INSERT INTO task_workspace_checkpoint(id,task_id,cycle_id,state,snapshot_id,canonical_root,root_fingerprint,
              branch_name,source_branch,baseline_commit,checkpoint_ref,checkpoint_commit,checkpoint_tree,
              manifest_json,manifest_sha256,stash_commit,blocker_code,blocker_message,created_at,updated_at,version)
            VALUES(#{id},#{taskId},#{cycleId},#{state},#{snapshotId},#{canonicalRoot},#{rootFingerprint},
              #{branchName},#{sourceBranch},#{baselineCommit},#{checkpointRef},#{checkpointCommit},#{checkpointTree},
              #{manifestJson},#{manifestSha256},#{stashCommit},#{blockerCode},#{blockerMessage},#{createdAt},#{updatedAt},#{version})
            """)
    int insertTaskWorkspaceCheckpoint(TaskWorkspaceCheckpointRow row);
    @Select("SELECT * FROM task_workspace_checkpoint WHERE id=#{id}") Optional<TaskWorkspaceCheckpointRow> findTaskWorkspaceCheckpoint(String id);
    @Select("SELECT * FROM task_workspace_checkpoint WHERE cycle_id=#{cycleId}") Optional<TaskWorkspaceCheckpointRow> findTaskWorkspaceCheckpointForCycle(String cycleId);
    @Select("SELECT * FROM task_workspace_checkpoint WHERE task_id=#{taskId} ORDER BY created_at DESC LIMIT 1")
    Optional<TaskWorkspaceCheckpointRow> latestTaskWorkspaceCheckpoint(String taskId);
    @Select("SELECT * FROM task_workspace_checkpoint WHERE state IN ('CAPTURING','RESTORING') ORDER BY created_at")
    List<TaskWorkspaceCheckpointRow> listIncompleteTaskWorkspaceCheckpoints();
    @Update("""
            UPDATE task_workspace_checkpoint SET state=#{state},snapshot_id=#{snapshotId},checkpoint_ref=#{checkpointRef},
              checkpoint_commit=#{checkpointCommit},checkpoint_tree=#{checkpointTree},manifest_json=#{manifestJson},
              manifest_sha256=#{manifestSha256},stash_commit=#{stashCommit},blocker_code=#{blockerCode},
              blocker_message=#{blockerMessage},updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateTaskWorkspaceCheckpoint(TaskWorkspaceCheckpointRow row);

    @Insert("INSERT INTO execution_session(id,task_id,stage_id,attempt_id,external_session_id,state,created_at,ended_at,version,todo_capability) VALUES(#{id},#{taskId},#{stageId},#{attemptId},#{externalSessionId},#{state},#{createdAt},#{endedAt},#{version},#{todoCapability})")
    int insertSession(ExecutionSessionRow row);
    @Select("SELECT * FROM execution_session WHERE id=#{id}") Optional<ExecutionSessionRow> findSession(String id);
    @Select("SELECT * FROM execution_session WHERE attempt_id=#{attemptId} ORDER BY created_at DESC LIMIT 1") Optional<ExecutionSessionRow> latestSessionForAttempt(String attemptId);
    @Select("SELECT * FROM execution_session WHERE task_id=#{taskId} ORDER BY created_at DESC") List<ExecutionSessionRow> listSessions(String taskId);
    @Select("SELECT * FROM execution_session WHERE task_id=#{taskId} AND state IN ('CREATING','RUNNING') ORDER BY created_at DESC") List<ExecutionSessionRow> activeSessions(String taskId);
    @Select("SELECT * FROM execution_session WHERE state IN ('CREATING','RUNNING') ORDER BY created_at") List<ExecutionSessionRow> activeExecutionSessions();
    @Select("""
            SELECT session.* FROM execution_session session
            WHERE session.state='DISCONNECTED'
              AND EXISTS (
                SELECT 1 FROM error_event error
                WHERE error.session_id=session.id AND error.code='SESSION_ABORT_UNCONFIRMED'
              )
              AND NOT EXISTS (
                SELECT 1 FROM error_event terminal_error
                WHERE terminal_error.session_id=session.id
                  AND terminal_error.code IN ('SESSION_ABORT_CLEANUP_CONFIRMED','SESSION_ABORT_CLEANUP_EXHAUSTED')
              )
            ORDER BY session.created_at
            """)
    List<ExecutionSessionRow> sessionsPendingAbortCleanup();
    @Select("""
            SELECT COUNT(*) FROM error_event
            WHERE session_id=#{sessionId}
              AND code IN ('SESSION_ABORT_CLEANUP_RETRY','SESSION_ABORT_CLEANUP_EXHAUSTED')
            """)
    int countAbortCleanupAttempts(String sessionId);
    @Update("UPDATE execution_session SET external_session_id=#{externalSessionId}, state=#{state}, ended_at=#{endedAt}, todo_capability=#{todoCapability}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateSessionState(ExecutionSessionRow row);

    @Insert("INSERT INTO verification_result(id,attempt_id,verifier_index,type,state,summary,evidence_json,created_at) VALUES(#{id},#{attemptId},#{verifierIndex},#{type},#{state},#{summary},#{evidenceJson},#{createdAt})")
    int insertVerification(VerificationResultRow row);
    @Select("SELECT * FROM verification_result WHERE attempt_id=#{attemptId} ORDER BY verifier_index") List<VerificationResultRow> listVerifications(String attemptId);

    @Insert("""
            INSERT INTO verifier_runtime(id,task_id,stage_id,attempt_id,state,pid,process_start_instant,port,
              argv_sha256,resolved_argv_json,temp_dir,evidence_json,created_at,updated_at,ended_at,version)
            VALUES(#{id},#{taskId},#{stageId},#{attemptId},#{state},#{pid},#{processStartInstant},#{port},
              #{argvSha256},#{resolvedArgvJson},#{tempDir},#{evidenceJson},#{createdAt},#{updatedAt},#{endedAt},#{version})
            """)
    int insertVerifierRuntime(VerifierRuntimeRow row);
    @Select("SELECT * FROM verifier_runtime WHERE id=#{id}")
    Optional<VerifierRuntimeRow> findVerifierRuntime(String id);
    @Select("SELECT * FROM verifier_runtime WHERE task_id=#{taskId} ORDER BY created_at DESC")
    List<VerifierRuntimeRow> listVerifierRuntimes(String taskId);
    @Select("SELECT * FROM verifier_runtime WHERE state IN ('STARTING','RUNNING','STOPPING') ORDER BY created_at")
    List<VerifierRuntimeRow> activeVerifierRuntimes();
    @Update("""
            UPDATE verifier_runtime SET state=#{state},pid=#{pid},process_start_instant=#{processStartInstant},
              port=#{port},argv_sha256=#{argvSha256},resolved_argv_json=#{resolvedArgvJson},temp_dir=#{tempDir},
              evidence_json=#{evidenceJson},updated_at=#{updatedAt},ended_at=#{endedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateVerifierRuntime(VerifierRuntimeRow row);

    @Insert("INSERT INTO judge_run(id,task_id,attempt_id,role,ordinal,external_session_id,state,verdict,reason,raw_output,created_at,ended_at,version,response_mode,response_schema_id) VALUES(#{id},#{taskId},#{attemptId},#{role},#{ordinal},#{externalSessionId},#{state},#{verdict},#{reason},#{rawOutput},#{createdAt},#{endedAt},#{version},#{responseMode},#{responseSchemaId})")
    int insertJudgeRun(JudgeRunRow row);
    @Select("SELECT * FROM judge_run WHERE id=#{id}") Optional<JudgeRunRow> findJudgeRun(String id);
    @Select("SELECT * FROM judge_run WHERE task_id=#{taskId} ORDER BY created_at, role, ordinal") List<JudgeRunRow> listJudgeRuns(String taskId);
    @Select("SELECT * FROM judge_run WHERE task_id=#{taskId} AND state IN ('CREATING','RUNNING') ORDER BY created_at") List<JudgeRunRow> activeJudgeRuns(String taskId);
    @Select("SELECT * FROM judge_run WHERE task_id=#{taskId} AND role=#{role} ORDER BY ordinal DESC LIMIT 1") Optional<JudgeRunRow> latestJudgeRun(@Param("taskId") String taskId, @Param("role") String role);
    @Select("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM judge_run WHERE task_id=#{taskId} AND role=#{role}") int nextJudgeOrdinal(@Param("taskId") String taskId, @Param("role") String role);
    @Select("SELECT COUNT(*) FROM judge_run WHERE task_id=#{taskId} AND role=#{role} AND state='SESSION_ERROR'") int countJudgeSessionErrors(@Param("taskId") String taskId, @Param("role") String role);
    @Update("UPDATE judge_run SET external_session_id=#{externalSessionId}, state=#{state}, verdict=#{verdict}, reason=#{reason}, raw_output=#{rawOutput}, ended_at=#{endedAt}, response_mode=#{responseMode}, response_schema_id=#{responseSchemaId}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateJudgeRun(JudgeRunRow row);

    @Insert("INSERT INTO task_artifact(id,task_id,attempt_id,judge_run_id,kind,name,content_type,content,metadata_json,created_at) VALUES(#{id},#{taskId},#{attemptId},#{judgeRunId},#{kind},#{name},#{contentType},#{content},#{metadataJson},#{createdAt})")
    int insertTaskArtifact(TaskArtifactRow row);
    @Select("SELECT * FROM task_artifact WHERE task_id=#{taskId} ORDER BY created_at DESC") List<TaskArtifactRow> listTaskArtifacts(String taskId);
    @Select("SELECT * FROM task_artifact WHERE task_id=#{taskId} AND kind=#{kind} ORDER BY created_at, id LIMIT 1")
    Optional<TaskArtifactRow> findFirstTaskArtifactByKind(@Param("taskId") String taskId, @Param("kind") String kind);

    @Insert("INSERT INTO error_event(id,task_id,stage_id,attempt_id,session_id,layer,code,message,retryable,evidence_json,occurred_at) VALUES(#{id},#{taskId},#{stageId},#{attemptId},#{sessionId},#{layer},#{code},#{message},#{retryable},#{evidenceJson},#{occurredAt})")
    int insertError(ErrorEventRow row);
    @Select("SELECT * FROM error_event WHERE task_id=#{taskId} ORDER BY occurred_at DESC") List<ErrorEventRow> listErrors(String taskId);

    @Select("SELECT COALESCE(MAX(sequence), 0) FROM task_event WHERE task_id=#{taskId}") long maxEventSequence(String taskId);
    @Insert("INSERT INTO task_event(id,task_id,sequence,type,payload_json,occurred_at) VALUES(#{id},#{taskId},#{sequence},#{type},#{payloadJson},#{occurredAt})")
    int insertTaskEvent(TaskEventRow row);
    @Select("SELECT * FROM task_event WHERE task_id=#{taskId} AND sequence > #{sequence} ORDER BY sequence")
    List<TaskEventRow> eventsAfter(@Param("taskId") String taskId, @Param("sequence") long sequence);

    @Insert("INSERT OR IGNORE INTO ai_output_handling_event(id,scope_type,scope_id,role,workflow_step,event_type,correction_categories_json,response_fingerprint,created_at) VALUES(#{id},#{scopeType},#{scopeId},#{role},#{workflowStep},#{eventType},#{correctionCategoriesJson},#{responseFingerprint},#{createdAt})")
    int insertAiOutputHandlingEvent(AiOutputHandlingEventRow row);
    @Select("SELECT * FROM ai_output_handling_event WHERE scope_type=#{scopeType} AND scope_id=#{scopeId} ORDER BY created_at,id")
    List<AiOutputHandlingEventRow> listAiOutputHandlingEvents(@Param("scopeType") String scopeType,
                                                              @Param("scopeId") String scopeId);

    @Select("SELECT * FROM workspace_lease WHERE canonical_root=#{canonicalRoot}")
    Optional<WorkspaceLeaseRow> findWorkspaceLease(String canonicalRoot);
    @Select("SELECT * FROM workspace_lease WHERE state IN ('HELD','RELEASE_PENDING') ORDER BY heartbeat_at")
    List<WorkspaceLeaseRow> blockingWorkspaceLeases();
    @Select("SELECT * FROM workspace_lease WHERE holder_task_id=#{taskId} AND state IN ('HELD','RELEASE_PENDING') LIMIT 1")
    Optional<WorkspaceLeaseRow> findActiveWorkspaceLeaseByHolder(String taskId);
    @Select("""
            SELECT lease.* FROM workspace_lease lease
            WHERE lease.state IN ('HELD','RELEASE_PENDING')
              AND EXISTS (
                SELECT 1 FROM task_queue queued
                WHERE queued.canonical_root=lease.canonical_root AND queued.state='QUEUED'
              )
            ORDER BY lease.heartbeat_at
            """)
    List<WorkspaceLeaseRow> blockingWorkspaceLeasesWithQueuedWaiter();
    @Insert("""
            INSERT INTO workspace_lease(canonical_root,root_fingerprint,mode,holder_task_id,writer_session_id,state,
              acquired_at,heartbeat_at,released_at,release_reason,version)
            VALUES(#{canonicalRoot},#{rootFingerprint},#{mode},#{holderTaskId},#{writerSessionId},#{state},
              #{acquiredAt},#{heartbeatAt},#{releasedAt},#{releaseReason},#{version})
            """)
    int insertWorkspaceLease(WorkspaceLeaseRow row);
    @Update("""
            UPDATE workspace_lease SET root_fingerprint=#{rootFingerprint},mode=#{mode},holder_task_id=#{holderTaskId},
              writer_session_id=#{writerSessionId},state=#{state},acquired_at=#{acquiredAt},heartbeat_at=#{heartbeatAt},
              released_at=#{releasedAt},release_reason=#{releaseReason},version=version+1
            WHERE canonical_root=#{canonicalRoot} AND version=#{version}
            """)
    int updateWorkspaceLease(WorkspaceLeaseRow row);
    @Update("""
            UPDATE workspace_lease SET root_fingerprint=#{rootFingerprint},mode=#{mode},holder_task_id=#{holderTaskId},
              writer_session_id=#{writerSessionId},acquired_at=#{acquiredAt},heartbeat_at=#{heartbeatAt},
              released_at=#{releasedAt},release_reason=#{releaseReason},version=version+1
            WHERE canonical_root=#{canonicalRoot} AND version=#{version}
            """)
    int updateWorkspaceLeaseDetails(WorkspaceLeaseRow row);

    @Select("SELECT COALESCE(MAX(position),0)+1 FROM task_queue WHERE canonical_root=#{canonicalRoot}")
    long nextQueuePosition(String canonicalRoot);
    @Insert("""
            INSERT INTO task_queue(task_id,canonical_root,root_fingerprint,position,source,state,enqueued_at,
              admitted_at,finished_at,version)
            VALUES(#{taskId},#{canonicalRoot},#{rootFingerprint},#{position},#{source},#{state},#{enqueuedAt},
              #{admittedAt},#{finishedAt},#{version})
            """)
    int insertTaskQueue(TaskQueueRow row);
    @Select("SELECT * FROM task_queue WHERE task_id=#{taskId}") Optional<TaskQueueRow> findTaskQueue(String taskId);
    @Select("SELECT * FROM task_queue WHERE canonical_root=#{canonicalRoot} ORDER BY position")
    List<TaskQueueRow> listTaskQueue(String canonicalRoot);
    @Select("SELECT * FROM task_queue WHERE canonical_root=#{canonicalRoot} AND state='QUEUED' ORDER BY position LIMIT 1")
    Optional<TaskQueueRow> nextQueuedTask(String canonicalRoot);
    @Update("""
            UPDATE task_queue SET state=#{state},admitted_at=#{admittedAt},finished_at=#{finishedAt},version=version+1
            WHERE task_id=#{taskId} AND version=#{version}
            """)
    int updateTaskQueue(TaskQueueRow row);
    @Update("""
            UPDATE task_queue SET root_fingerprint=#{rootFingerprint},position=#{position},source=#{source},state=#{state},
              enqueued_at=#{enqueuedAt},admitted_at=#{admittedAt},finished_at=#{finishedAt},version=version+1
            WHERE task_id=#{taskId} AND version=#{version}
            """)
    int requeueTask(TaskQueueRow row);

    @Insert("""
            INSERT INTO interaction(id,scope_type,scope_id,task_id,designer_session_id,local_session_id,
              external_session_id,external_request_id,kind,state,payload_json,resolved_action,response_json,
              created_at,updated_at,resolved_at,version)
            VALUES(#{id},#{scopeType},#{scopeId},#{taskId},#{designerSessionId},#{localSessionId},
              #{externalSessionId},#{externalRequestId},#{kind},#{state},#{payloadJson},#{resolvedAction},#{responseJson},
              #{createdAt},#{updatedAt},#{resolvedAt},#{version})
            """)
    int insertInteraction(InteractionRow row);
    @Update("""
            UPDATE interaction SET state='HARD_DENIED',payload_json=#{payloadJson},updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state IN ('PENDING','RESOLVING','STALE')
            """)
    int promoteInteractionToHardDenied(@Param("id") String id, @Param("version") long version,
                                       @Param("payloadJson") String payloadJson, @Param("updatedAt") String updatedAt);
    @Select("SELECT * FROM interaction WHERE id=#{id}") Optional<InteractionRow> findInteraction(String id);
    @Select("SELECT * FROM interaction WHERE external_session_id=#{externalSessionId} AND external_request_id=#{externalRequestId} AND kind=#{kind}")
    Optional<InteractionRow> findInteractionByExternalRequest(
            @Param("externalSessionId") String externalSessionId,
            @Param("externalRequestId") String externalRequestId,
            @Param("kind") String kind);
    @Select("SELECT * FROM interaction WHERE state='PENDING' ORDER BY created_at") List<InteractionRow> pendingInteractions();
    @Select("SELECT * FROM interaction WHERE state IN ('PENDING','RESOLVING','HARD_DENIED') ORDER BY created_at") List<InteractionRow> openInteractions();
    @Select("SELECT * FROM interaction WHERE scope_type=#{scopeType} AND scope_id=#{scopeId} ORDER BY created_at")
    List<InteractionRow> listInteractionsForScope(@Param("scopeType") String scopeType, @Param("scopeId") String scopeId);
    @Update("""
            UPDATE interaction SET state='RESOLVING',updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state='PENDING'
            """)
    int claimInteraction(@Param("id") String id, @Param("version") long version, @Param("updatedAt") String updatedAt);
    @Update("""
            UPDATE interaction SET state='PENDING',updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state='RESOLVING'
            """)
    int releaseInteractionClaim(@Param("id") String id, @Param("version") long version, @Param("updatedAt") String updatedAt);
    @Update("""
            UPDATE interaction SET state=#{state},resolved_action=#{resolvedAction},response_json=#{responseJson},
              updated_at=#{updatedAt},resolved_at=#{resolvedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state='RESOLVING'
            """)
    int resolveInteraction(InteractionRow row);
    @Select("""
            SELECT * FROM interaction
            WHERE external_session_id=#{externalSessionId} AND state IN ('PENDING','HARD_DENIED')
              AND external_request_id NOT IN (SELECT value FROM json_each(#{activeRequestIdsJson}))
            ORDER BY created_at
            """)
    List<InteractionRow> missingInteractionsForSession(
            @Param("externalSessionId") String externalSessionId,
            @Param("activeRequestIdsJson") String activeRequestIdsJson);
    @Select("""
            SELECT interaction.* FROM interaction
            WHERE interaction.state IN ('PENDING','RESOLVING','HARD_DENIED')
              AND interaction.local_session_id IN (
                SELECT id FROM execution_session WHERE state NOT IN ('CREATING','RUNNING')
              )
            ORDER BY interaction.created_at
            """)
    List<InteractionRow> terminalSessionInteractions();
    @Update("""
            UPDATE interaction SET state='STALE',updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state IN ('PENDING','RESOLVING','HARD_DENIED')
            """)
    int markInteractionStale(@Param("id") String id, @Param("version") long version,
                             @Param("updatedAt") String updatedAt);

    @Insert("INSERT INTO task_lineage(child_task_id,parent_task_id,recovery_mode,parent_stage_id,workspace_fingerprint,created_at) VALUES(#{childTaskId},#{parentTaskId},#{recoveryMode},#{parentStageId},#{workspaceFingerprint},#{createdAt})")
    int insertTaskLineage(TaskLineageRow row);
    @Select("SELECT * FROM task_lineage WHERE child_task_id=#{childTaskId}") Optional<TaskLineageRow> findTaskLineage(String childTaskId);
    @Select("SELECT * FROM task_lineage WHERE parent_task_id=#{parentTaskId} ORDER BY created_at DESC") List<TaskLineageRow> childTasks(String parentTaskId);

    @Insert("""
            INSERT INTO session_todo(id,execution_session_id,external_todo_id,content,status,priority,ordinal,payload_json,observed_at,version)
            VALUES(#{id},#{executionSessionId},#{externalTodoId},#{content},#{status},#{priority},#{ordinal},#{payloadJson},#{observedAt},#{version})
            ON CONFLICT(execution_session_id,external_todo_id) DO UPDATE SET content=excluded.content,status=excluded.status,
              priority=excluded.priority,ordinal=excluded.ordinal,payload_json=excluded.payload_json,observed_at=excluded.observed_at,version=session_todo.version+1
            """)
    int upsertSessionTodo(SessionTodoRow row);
    @Select("SELECT * FROM session_todo WHERE execution_session_id=#{sessionId} ORDER BY ordinal") List<SessionTodoRow> listSessionTodos(String sessionId);
    @Delete("""
            DELETE FROM session_todo
            WHERE execution_session_id=#{sessionId}
              AND external_todo_id NOT IN (SELECT value FROM json_each(#{activeTodoIdsJson}))
            """)
    int deleteMissingSessionTodos(@Param("sessionId") String sessionId,
                                  @Param("activeTodoIdsJson") String activeTodoIdsJson);

    @Insert("INSERT INTO session_checkpoint(id,task_id,execution_session_id,attempt_id,external_message_id,message_refs_json,todo_refs_json,diff_ref_json,content_sha256,created_at,version) VALUES(#{id},#{taskId},#{executionSessionId},#{attemptId},#{externalMessageId},#{messageRefsJson},#{todoRefsJson},#{diffRefJson},#{contentSha256},#{createdAt},#{version})")
    int insertSessionCheckpoint(SessionCheckpointRow row);
    @Select("SELECT * FROM session_checkpoint WHERE id=#{id}") Optional<SessionCheckpointRow> findSessionCheckpoint(String id);
    @Select("SELECT * FROM session_checkpoint WHERE execution_session_id=#{sessionId} ORDER BY created_at DESC") List<SessionCheckpointRow> listSessionCheckpoints(String sessionId);

    @Insert("""
            INSERT INTO session_usage(id,task_id,execution_session_id,judge_run_id,external_message_id,idempotency_key,provider_id,model_id,
              input_tokens,output_tokens,total_tokens,cost_amount,currency,reliable,observed_at)
            VALUES(#{id},#{taskId},#{executionSessionId},#{judgeRunId},#{externalMessageId},#{idempotencyKey},#{providerId},#{modelId},
              #{inputTokens},#{outputTokens},#{totalTokens},#{costAmount},#{currency},#{reliable},#{observedAt})
            ON CONFLICT(idempotency_key) DO NOTHING
            """)
    int insertSessionUsage(SessionUsageRow row);
    @Select("SELECT * FROM session_usage WHERE task_id=#{taskId} ORDER BY observed_at") List<SessionUsageRow> listTaskUsage(String taskId);
    @Select("SELECT * FROM session_usage ORDER BY observed_at") List<SessionUsageRow> listAllUsage();

    @Insert("INSERT INTO binary_artifact(id,task_id,attempt_id,execution_session_id,verification_result_id,kind,media_type,relative_path,sha256,size_bytes,metadata_json,created_at) VALUES(#{id},#{taskId},#{attemptId},#{executionSessionId},#{verificationResultId},#{kind},#{mediaType},#{relativePath},#{sha256},#{sizeBytes},#{metadataJson},#{createdAt})")
    int insertBinaryArtifact(BinaryArtifactRow row);
    @Select("SELECT * FROM binary_artifact WHERE task_id=#{taskId} ORDER BY created_at DESC") List<BinaryArtifactRow> listBinaryArtifacts(String taskId);
    @Select("SELECT * FROM binary_artifact WHERE verification_result_id=#{verificationResultId} ORDER BY created_at DESC") List<BinaryArtifactRow> listBinaryArtifactsForVerification(String verificationResultId);

    @Insert("INSERT INTO loopspec_template(id,name,description,state,created_at,updated_at,version) VALUES(#{id},#{name},#{description},#{state},#{createdAt},#{updatedAt},#{version})")
    int insertLoopSpecTemplate(LoopSpecTemplateRow row);
    @Select("SELECT * FROM loopspec_template WHERE id=#{id}") Optional<LoopSpecTemplateRow> findLoopSpecTemplate(String id);
    @Select("SELECT * FROM loopspec_template ORDER BY updated_at DESC") List<LoopSpecTemplateRow> listLoopSpecTemplates();
    @Update("UPDATE loopspec_template SET name=#{name},description=#{description},state=#{state},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateLoopSpecTemplate(LoopSpecTemplateRow row);
    @Update("UPDATE loopspec_template SET name=#{name},description=#{description},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateLoopSpecTemplateDetails(LoopSpecTemplateRow row);
    @Insert("INSERT INTO loopspec_template_version(id,template_id,version_number,spec_json,spec_sha256,immutable,auto_start_approved,created_at) VALUES(#{id},#{templateId},#{versionNumber},#{specJson},#{specSha256},#{immutable},#{autoStartApproved},#{createdAt})")
    int insertLoopSpecTemplateVersion(LoopSpecTemplateVersionRow row);
    @Select("SELECT * FROM loopspec_template_version WHERE id=#{id}") Optional<LoopSpecTemplateVersionRow> findLoopSpecTemplateVersion(String id);
    @Select("SELECT * FROM loopspec_template_version WHERE template_id=#{templateId} ORDER BY version_number DESC") List<LoopSpecTemplateVersionRow> listLoopSpecTemplateVersions(String templateId);
    @Select("SELECT COALESCE(MAX(version_number),0)+1 FROM loopspec_template_version WHERE template_id=#{templateId}") int nextLoopSpecTemplateVersion(String templateId);

    @Insert("INSERT INTO automation_rule(id,name,project_id,template_version_id,trigger_type,state,approval_mode,trigger_config_json,webhook_token_hash,last_observed_head,created_at,updated_at,version) VALUES(#{id},#{name},#{projectId},#{templateVersionId},#{triggerType},#{state},#{approvalMode},#{triggerConfigJson},#{webhookTokenHash},#{lastObservedHead},#{createdAt},#{updatedAt},#{version})")
    int insertAutomationRule(AutomationRuleRow row);
    @Select("SELECT * FROM automation_rule WHERE id=#{id}") Optional<AutomationRuleRow> findAutomationRule(String id);
    @Select("SELECT * FROM automation_rule ORDER BY updated_at DESC") List<AutomationRuleRow> listAutomationRules();
    @Select("SELECT * FROM automation_rule WHERE state='ENABLED' ORDER BY updated_at") List<AutomationRuleRow> enabledAutomationRules();
    @Update("UPDATE automation_rule SET name=#{name},template_version_id=#{templateVersionId},trigger_type=#{triggerType},state=#{state},approval_mode=#{approvalMode},trigger_config_json=#{triggerConfigJson},webhook_token_hash=#{webhookTokenHash},last_observed_head=#{lastObservedHead},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateAutomationRule(AutomationRuleRow row);
    @Update("UPDATE automation_rule SET name=#{name},template_version_id=#{templateVersionId},trigger_type=#{triggerType},approval_mode=#{approvalMode},trigger_config_json=#{triggerConfigJson},webhook_token_hash=#{webhookTokenHash},last_observed_head=#{lastObservedHead},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateAutomationRuleDetails(AutomationRuleRow row);
    @Insert("INSERT INTO automation_run(id,rule_id,trigger_type,idempotency_key,state,draft_id,task_id,evidence_json,detected_at,started_at,ended_at,version) VALUES(#{id},#{ruleId},#{triggerType},#{idempotencyKey},#{state},#{draftId},#{taskId},#{evidenceJson},#{detectedAt},#{startedAt},#{endedAt},#{version})")
    int insertAutomationRun(AutomationRunRow row);
    @Select("SELECT * FROM automation_run WHERE id=#{id}") Optional<AutomationRunRow> findAutomationRun(String id);
    @Select("SELECT * FROM automation_run WHERE rule_id=#{ruleId} ORDER BY detected_at DESC") List<AutomationRunRow> listAutomationRuns(String ruleId);
    @Update("UPDATE automation_run SET state=#{state},draft_id=#{draftId},task_id=#{taskId},evidence_json=#{evidenceJson},started_at=#{startedAt},ended_at=#{endedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateAutomationRun(AutomationRunRow row);

    @Insert("""
            INSERT INTO designer_auto_mode(designer_session_id,state,last_action,error_code,error_detail,task_id,
              authorized_at,disabled_at,updated_at,version)
            VALUES(#{designerSessionId},#{state},#{lastAction},#{errorCode},#{errorDetail},#{taskId},
              #{authorizedAt},#{disabledAt},#{updatedAt},#{version})
            """)
    int insertDesignerAutoMode(DesignerAutoModeRow row);
    @Select("SELECT * FROM designer_auto_mode WHERE designer_session_id=#{sessionId}")
    Optional<DesignerAutoModeRow> findDesignerAutoMode(String sessionId);
    @Select("SELECT * FROM designer_auto_mode WHERE state='ACTIVE' ORDER BY updated_at,designer_session_id")
    List<DesignerAutoModeRow> listActiveDesignerAutoModes();
    @Update("""
            UPDATE designer_auto_mode SET state=#{state},last_action=#{lastAction},error_code=#{errorCode},
              error_detail=#{errorDetail},task_id=#{taskId},authorized_at=#{authorizedAt},
              disabled_at=#{disabledAt},updated_at=#{updatedAt},version=version+1
            WHERE designer_session_id=#{designerSessionId} AND version=#{version}
            """)
    int updateDesignerAutoMode(DesignerAutoModeRow row);
}
