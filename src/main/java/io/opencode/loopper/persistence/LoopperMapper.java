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
    @Update("""
            UPDATE project_convention_draft SET
              external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState},
              proposed_content=#{proposedContent}, error_message=#{errorMessage}, updated_at=#{updatedAt}, version=version+1
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

    @Insert("INSERT INTO designer_session(id,project_id,state,access_mode,external_session_id,external_session_state,loop_draft_id,created_at,updated_at,version) VALUES(#{id},#{projectId},#{state},#{accessMode},#{externalSessionId},#{externalSessionState},#{loopDraftId},#{createdAt},#{updatedAt},#{version})")
    int insertDesignerSession(DesignerSessionRow row);
    @Select("SELECT * FROM designer_session WHERE id=#{id}") Optional<DesignerSessionRow> findDesignerSession(String id);
    @Select("SELECT * FROM designer_session WHERE loop_draft_id=#{draftId} ORDER BY created_at DESC LIMIT 1")
    Optional<DesignerSessionRow> findLatestDesignerSessionByDraft(String draftId);
    @Select("SELECT * FROM designer_session WHERE state='RUNNING' AND external_session_id IS NOT NULL ORDER BY updated_at")
    List<DesignerSessionRow> activeDesignerHandoffs();
    @Update("UPDATE designer_session SET state=#{state}, access_mode=#{accessMode}, external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState}, loop_draft_id=#{loopDraftId}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDesignerSession(DesignerSessionRow row);
    @Update("UPDATE designer_session SET access_mode=#{accessMode}, external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState}, loop_draft_id=#{loopDraftId}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDesignerSessionProjection(DesignerSessionRow row);
    @Select("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM designer_message WHERE designer_session_id=#{sessionId}")
    int nextDesignerMessageOrdinal(String sessionId);
    @Insert("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) VALUES(#{id},#{designerSessionId},#{ordinal},#{role},#{content},#{deliveryState},#{createdAt})")
    int insertDesignerMessage(DesignerMessageRow row);
    @Select("SELECT * FROM designer_message WHERE designer_session_id=#{sessionId} ORDER BY ordinal")
    List<DesignerMessageRow> listDesignerMessages(String sessionId);
    @Select("""
            SELECT message.* FROM designer_message message
            JOIN designer_session session ON session.id=message.designer_session_id
            WHERE session.loop_draft_id=#{draftId}
              AND message.role='ASSISTANT'
              AND message.delivery_state='PERSISTED'
              AND TRIM(message.content)<>''
            ORDER BY message.created_at DESC, message.ordinal DESC
            LIMIT 1
            """)
    Optional<DesignerMessageRow> findLatestPersistedDesignerMessageByDraft(String draftId);

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

    @Select("SELECT * FROM workspace_lease WHERE canonical_root=#{canonicalRoot}")
    Optional<WorkspaceLeaseRow> findWorkspaceLease(String canonicalRoot);
    @Select("SELECT * FROM workspace_lease WHERE state IN ('HELD','RELEASE_PENDING') ORDER BY heartbeat_at")
    List<WorkspaceLeaseRow> blockingWorkspaceLeases();
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

    /**
     * A Provider request id can be observed more than once with richer permission details.  A newly observed
     * dangerous payload may only promote an open/stale interaction to HARD_DENIED; it must never reopen a
     * terminal interaction or relax an existing local hard deny.
     */
    @Insert("""
            INSERT INTO interaction(id,scope_type,scope_id,task_id,designer_session_id,local_session_id,
              external_session_id,external_request_id,kind,state,payload_json,resolved_action,response_json,
              created_at,updated_at,resolved_at,version)
            VALUES(#{id},#{scopeType},#{scopeId},#{taskId},#{designerSessionId},#{localSessionId},
              #{externalSessionId},#{externalRequestId},#{kind},#{state},#{payloadJson},#{resolvedAction},#{responseJson},
              #{createdAt},#{updatedAt},#{resolvedAt},#{version})
            ON CONFLICT(external_session_id,external_request_id,kind) DO UPDATE SET
              state=CASE
                WHEN interaction.state IN ('PENDING','RESOLVING','STALE') AND excluded.state='HARD_DENIED' THEN 'HARD_DENIED'
                ELSE interaction.state
              END,
              payload_json=CASE
                WHEN interaction.state IN ('PENDING','RESOLVING','STALE') AND excluded.state='HARD_DENIED' THEN excluded.payload_json
                ELSE interaction.payload_json
              END,
              updated_at=CASE
                WHEN interaction.state IN ('PENDING','RESOLVING','STALE') AND excluded.state='HARD_DENIED' THEN excluded.updated_at
                ELSE interaction.updated_at
              END,
              version=CASE
                WHEN interaction.state IN ('PENDING','RESOLVING','STALE') AND excluded.state='HARD_DENIED' THEN interaction.version + 1
                ELSE interaction.version
              END
            """)
    int upsertInteraction(InteractionRow row);
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
}
