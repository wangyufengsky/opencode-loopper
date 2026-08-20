package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Domain-focused persistence contract composed by {@link LoopperMapper}. */
public interface LoopperInfrastructureMapper {
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
}
