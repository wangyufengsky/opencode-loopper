package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LoopperMapper {
    @Select("SELECT * FROM app_settings WHERE id=1")
    Optional<AppSettingsRow> findAppSettings();
    @Insert("""
            INSERT INTO app_settings(id,cli_path,allowed_root,provider_id,model_id,max_task_attempts,attempt_timeout_minutes,auto_approve,updated_at)
            VALUES(1,#{cliPath},#{allowedRoot},#{providerId},#{modelId},#{maxTaskAttempts},#{attemptTimeoutMinutes},#{autoApprove},#{updatedAt})
            ON CONFLICT(id) DO UPDATE SET
              cli_path=excluded.cli_path,
              allowed_root=excluded.allowed_root,
              provider_id=excluded.provider_id,
              model_id=excluded.model_id,
              max_task_attempts=excluded.max_task_attempts,
              attempt_timeout_minutes=excluded.attempt_timeout_minutes,
              auto_approve=excluded.auto_approve,
              updated_at=excluded.updated_at
            """)
    int upsertAppSettings(AppSettingsRow row);

    @Insert("INSERT INTO project(id,name,root_path,description,created_at,updated_at,managed,version) VALUES(#{id},#{name},#{rootPath},#{description},#{createdAt},#{updatedAt},#{managed},#{version})")
    int insertProject(ProjectRow row);
    @Select("SELECT * FROM project WHERE id=#{id}") Optional<ProjectRow> findProject(String id);
    @Select("SELECT * FROM project WHERE root_path=#{rootPath}") Optional<ProjectRow> findProjectByRoot(String rootPath);
    @Select("SELECT * FROM project WHERE managed=1 ORDER BY created_at DESC") List<ProjectRow> listProjects();
    @Select("SELECT COUNT(*) FROM task WHERE project_id=#{projectId}") int countTasksForProject(String projectId);
    @Update("UPDATE project SET name=#{name}, description=#{description}, updated_at=#{updatedAt}, managed=#{managed}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateProject(ProjectRow row);
    @Update("UPDATE project SET managed=0, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND managed=1")
    int unmanageProject(@Param("id") String id, @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO project_convention_draft(
              id,project_id,state,external_session_id,external_session_state,
              source_exists,source_sha256,source_content,proposed_content,error_message,
              created_at,updated_at,version)
            VALUES(#{id},#{projectId},#{state},#{externalSessionId},#{externalSessionState},
              #{sourceExists},#{sourceSha256},#{sourceContent},#{proposedContent},#{errorMessage},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertProjectConventionDraft(ProjectConventionDraftRow row);
    @Select("SELECT * FROM project_convention_draft WHERE id=#{id}")
    Optional<ProjectConventionDraftRow> findProjectConventionDraft(String id);
    @Select("""
            SELECT * FROM project_convention_draft
            WHERE project_id=#{projectId} AND state='RUNNING'
            ORDER BY created_at DESC LIMIT 1
            """)
    Optional<ProjectConventionDraftRow> activeProjectConventionDraft(String projectId);
    @Select("SELECT * FROM project_convention_draft WHERE state='RUNNING' AND external_session_id IS NOT NULL ORDER BY updated_at")
    List<ProjectConventionDraftRow> activeProjectConventionDrafts();
    @Update("""
            UPDATE project_convention_draft SET
              state=#{state}, external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState},
              proposed_content=#{proposedContent}, error_message=#{errorMessage}, updated_at=#{updatedAt}, version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateProjectConventionDraft(ProjectConventionDraftRow row);

    @Insert("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at,version) VALUES(#{id},#{projectId},#{goal},#{specJson},#{status},#{createdAt},#{updatedAt},#{version})")
    int insertDraft(LoopDraftRow row);
    @Select("SELECT * FROM loop_draft WHERE id=#{id}") Optional<LoopDraftRow> findDraft(String id);
    @Update("UPDATE loop_draft SET goal=#{goal}, spec_json=#{specJson}, status=#{status}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDraft(LoopDraftRow row);

    @Insert("INSERT INTO designer_session(id,project_id,state,access_mode,external_session_id,external_session_state,loop_draft_id,created_at,updated_at,version) VALUES(#{id},#{projectId},#{state},#{accessMode},#{externalSessionId},#{externalSessionState},#{loopDraftId},#{createdAt},#{updatedAt},#{version})")
    int insertDesignerSession(DesignerSessionRow row);
    @Select("SELECT * FROM designer_session WHERE id=#{id}") Optional<DesignerSessionRow> findDesignerSession(String id);
    @Select("SELECT * FROM designer_session WHERE loop_draft_id=#{draftId} ORDER BY created_at DESC LIMIT 1")
    Optional<DesignerSessionRow> findLatestDesignerSessionByDraft(String draftId);
    @Select("SELECT * FROM designer_session WHERE state='RUNNING' AND external_session_id IS NOT NULL ORDER BY updated_at")
    List<DesignerSessionRow> activeDesignerHandoffs();
    @Update("UPDATE designer_session SET state=#{state}, access_mode=#{accessMode}, external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState}, loop_draft_id=#{loopDraftId}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDesignerSession(DesignerSessionRow row);
    @Select("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM designer_message WHERE designer_session_id=#{sessionId}")
    int nextDesignerMessageOrdinal(String sessionId);
    @Insert("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) VALUES(#{id},#{designerSessionId},#{ordinal},#{role},#{content},#{deliveryState},#{createdAt})")
    int insertDesignerMessage(DesignerMessageRow row);
    @Select("SELECT * FROM designer_message WHERE designer_session_id=#{sessionId} ORDER BY ordinal")
    List<DesignerMessageRow> listDesignerMessages(String sessionId);

    @Insert("INSERT INTO task(id,project_id,loop_draft_id,title,state,worktree_path,branch_name,baseline_commit,created_at,updated_at,version) VALUES(#{id},#{projectId},#{loopDraftId},#{title},#{state},#{worktreePath},#{branchName},#{baselineCommit},#{createdAt},#{updatedAt},#{version})")
    int insertTask(TaskRow row);
    @Select("SELECT * FROM task WHERE id=#{id}") Optional<TaskRow> findTask(String id);
    @Select("SELECT * FROM task WHERE loop_draft_id=#{draftId} ORDER BY created_at DESC LIMIT 1") Optional<TaskRow> findTaskByDraft(String draftId);
    @Select("SELECT * FROM task ORDER BY created_at DESC") List<TaskRow> listTasks();
    @Select("SELECT COUNT(*) > 0 FROM task_archive WHERE task_id=#{taskId}") boolean isTaskArchived(String taskId);
    @Insert("INSERT INTO task_archive(task_id,archived_at) VALUES(#{taskId},#{archivedAt}) ON CONFLICT(task_id) DO UPDATE SET archived_at=excluded.archived_at")
    int archiveTask(@Param("taskId") String taskId, @Param("archivedAt") String archivedAt);
    @org.apache.ibatis.annotations.Delete("DELETE FROM task_archive WHERE task_id=#{taskId}")
    int restoreTask(String taskId);
    @Select("SELECT * FROM task WHERE state IN ('PREPARING','RUNNING','VERIFYING','RETRY_WAIT','JUDGING') ORDER BY created_at") List<TaskRow> listRecoverableTasks();
    @Update("UPDATE task SET state=#{state}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateTaskState(TaskRow row);
    @Update("UPDATE task SET worktree_path=#{worktreePath}, branch_name=#{branchName}, baseline_commit=#{baselineCommit}, state=#{state}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int prepareTask(TaskRow row);

    @Insert("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at,version) VALUES(#{id},#{taskId},#{ordinal},#{objective},#{allowedPathsJson},#{forbiddenPathsJson},#{deliverablesJson},#{verifiersJson},#{state},#{createdAt},#{updatedAt},#{version})")
    int insertStage(StageRow row);
    @Select("SELECT * FROM stage WHERE id=#{id}") Optional<StageRow> findStage(String id);
    @Select("SELECT * FROM stage WHERE task_id=#{taskId} ORDER BY ordinal") List<StageRow> listStages(String taskId);
    @Update("UPDATE stage SET state=#{state}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateStageState(StageRow row);

    @Insert("INSERT INTO attempt(id,task_id,stage_id,ordinal,state,failure_kind,summary,created_at,ended_at,version) VALUES(#{id},#{taskId},#{stageId},#{ordinal},#{state},#{failureKind},#{summary},#{createdAt},#{endedAt},#{version})")
    int insertAttempt(AttemptRow row);
    @Select("SELECT * FROM attempt WHERE id=#{id}") Optional<AttemptRow> findAttempt(String id);
    @Select("SELECT * FROM attempt WHERE task_id=#{taskId} ORDER BY created_at DESC") List<AttemptRow> listAttempts(String taskId);
    @Select("SELECT * FROM attempt WHERE stage_id=#{stageId} ORDER BY ordinal DESC LIMIT 1") Optional<AttemptRow> latestAttempt(String stageId);
    @Select("SELECT COUNT(*) FROM attempt WHERE stage_id=#{stageId}") int countAttemptsForStage(String stageId);
    @Select("SELECT COUNT(*) FROM attempt WHERE task_id=#{taskId}") int countAttemptsForTask(String taskId);
    @Select("SELECT COUNT(*) FROM attempt WHERE stage_id=#{stageId} AND state='SESSION_ERROR'") int countSessionErrorsForStage(String stageId);
    @Update("UPDATE attempt SET state=#{state}, failure_kind=#{failureKind}, summary=#{summary}, ended_at=#{endedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int finishAttempt(AttemptRow row);

    @Insert("INSERT INTO execution_session(id,task_id,stage_id,attempt_id,external_session_id,state,created_at,ended_at,version) VALUES(#{id},#{taskId},#{stageId},#{attemptId},#{externalSessionId},#{state},#{createdAt},#{endedAt},#{version})")
    int insertSession(ExecutionSessionRow row);
    @Select("SELECT * FROM execution_session WHERE id=#{id}") Optional<ExecutionSessionRow> findSession(String id);
    @Select("SELECT * FROM execution_session WHERE attempt_id=#{attemptId} ORDER BY created_at DESC LIMIT 1") Optional<ExecutionSessionRow> latestSessionForAttempt(String attemptId);
    @Select("SELECT * FROM execution_session WHERE task_id=#{taskId} ORDER BY created_at DESC") List<ExecutionSessionRow> listSessions(String taskId);
    @Select("SELECT * FROM execution_session WHERE task_id=#{taskId} AND state IN ('CREATING','RUNNING') ORDER BY created_at DESC") List<ExecutionSessionRow> activeSessions(String taskId);
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
    @Update("UPDATE execution_session SET external_session_id=#{externalSessionId}, state=#{state}, ended_at=#{endedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateSessionState(ExecutionSessionRow row);

    @Insert("INSERT INTO verification_result(id,attempt_id,verifier_index,type,state,summary,evidence_json,created_at) VALUES(#{id},#{attemptId},#{verifierIndex},#{type},#{state},#{summary},#{evidenceJson},#{createdAt})")
    int insertVerification(VerificationResultRow row);
    @Select("SELECT * FROM verification_result WHERE attempt_id=#{attemptId} ORDER BY verifier_index") List<VerificationResultRow> listVerifications(String attemptId);

    @Insert("INSERT INTO judge_run(id,task_id,attempt_id,role,ordinal,external_session_id,state,verdict,reason,raw_output,created_at,ended_at,version) VALUES(#{id},#{taskId},#{attemptId},#{role},#{ordinal},#{externalSessionId},#{state},#{verdict},#{reason},#{rawOutput},#{createdAt},#{endedAt},#{version})")
    int insertJudgeRun(JudgeRunRow row);
    @Select("SELECT * FROM judge_run WHERE id=#{id}") Optional<JudgeRunRow> findJudgeRun(String id);
    @Select("SELECT * FROM judge_run WHERE task_id=#{taskId} ORDER BY created_at, role, ordinal") List<JudgeRunRow> listJudgeRuns(String taskId);
    @Select("SELECT * FROM judge_run WHERE task_id=#{taskId} AND state IN ('CREATING','RUNNING') ORDER BY created_at") List<JudgeRunRow> activeJudgeRuns(String taskId);
    @Select("SELECT * FROM judge_run WHERE task_id=#{taskId} AND role=#{role} ORDER BY ordinal DESC LIMIT 1") Optional<JudgeRunRow> latestJudgeRun(@Param("taskId") String taskId, @Param("role") String role);
    @Select("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM judge_run WHERE task_id=#{taskId} AND role=#{role}") int nextJudgeOrdinal(@Param("taskId") String taskId, @Param("role") String role);
    @Select("SELECT COUNT(*) FROM judge_run WHERE task_id=#{taskId} AND role=#{role} AND state='SESSION_ERROR'") int countJudgeSessionErrors(@Param("taskId") String taskId, @Param("role") String role);
    @Update("UPDATE judge_run SET external_session_id=#{externalSessionId}, state=#{state}, verdict=#{verdict}, reason=#{reason}, raw_output=#{rawOutput}, ended_at=#{endedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateJudgeRun(JudgeRunRow row);

    @Insert("INSERT INTO task_artifact(id,task_id,attempt_id,judge_run_id,kind,name,content_type,content,metadata_json,created_at) VALUES(#{id},#{taskId},#{attemptId},#{judgeRunId},#{kind},#{name},#{contentType},#{content},#{metadataJson},#{createdAt})")
    int insertTaskArtifact(TaskArtifactRow row);
    @Select("SELECT * FROM task_artifact WHERE task_id=#{taskId} ORDER BY created_at DESC") List<TaskArtifactRow> listTaskArtifacts(String taskId);

    @Insert("INSERT INTO error_event(id,task_id,stage_id,attempt_id,session_id,layer,code,message,retryable,evidence_json,occurred_at) VALUES(#{id},#{taskId},#{stageId},#{attemptId},#{sessionId},#{layer},#{code},#{message},#{retryable},#{evidenceJson},#{occurredAt})")
    int insertError(ErrorEventRow row);
    @Select("SELECT * FROM error_event WHERE task_id=#{taskId} ORDER BY occurred_at DESC") List<ErrorEventRow> listErrors(String taskId);

    @Select("SELECT COALESCE(MAX(sequence), 0) FROM task_event WHERE task_id=#{taskId}") long maxEventSequence(String taskId);
    @Insert("INSERT INTO task_event(id,task_id,sequence,type,payload_json,occurred_at) VALUES(#{id},#{taskId},#{sequence},#{type},#{payloadJson},#{occurredAt})")
    int insertTaskEvent(TaskEventRow row);
    @Select("SELECT * FROM task_event WHERE task_id=#{taskId} AND sequence > #{sequence} ORDER BY sequence")
    List<TaskEventRow> eventsAfter(@Param("taskId") String taskId, @Param("sequence") long sequence);
}
