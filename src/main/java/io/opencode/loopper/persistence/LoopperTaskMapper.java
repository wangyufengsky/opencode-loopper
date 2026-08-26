package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Domain-focused persistence contract composed by {@link LoopperMapper}. */
public interface LoopperTaskMapper {
    @Insert("INSERT INTO task(id,project_id,loop_draft_id,title,state,worktree_path,branch_name,source_branch,baseline_commit,created_at,updated_at,version,task_profile_id,role_pack_id,role_pack_version,execution_mode,workspace_policy) VALUES(#{id},#{projectId},#{loopDraftId},#{title},#{state},#{worktreePath},#{branchName},#{sourceBranch},#{baselineCommit},#{createdAt},#{updatedAt},#{version},#{taskProfileId},#{rolePackId},#{rolePackVersion},#{executionMode},#{workspacePolicy})")
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
    @Select("SELECT * FROM task WHERE state IN ('PREPARING','RUNNING','VERIFYING','RETRY_WAIT','JUDGING','PACKAGE_DESIGNING','STOPPING') ORDER BY created_at") List<TaskRow> listRecoverableTasks();
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

    @Insert("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at,version,work_package_id,stage_kind,execution_strategy,artifact_plan_id,role_pack_id,role_pack_version,test_policy,technologies_json,project_stack_profile_id,component_keys_json,stack_fingerprint,package_run_id) VALUES(#{id},#{taskId},#{ordinal},#{objective},#{allowedPathsJson},#{forbiddenPathsJson},#{deliverablesJson},#{verifiersJson},#{state},#{createdAt},#{updatedAt},#{version},#{workPackageId},#{stageKind},#{executionStrategy},#{artifactPlanId},#{rolePackId},#{rolePackVersion},#{testPolicy},#{technologiesJson},#{projectStackProfileId},#{componentKeysJson},#{stackFingerprint},#{packageRunId})")
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
              supplemental_prompt,budget_json,failure_code,failure_message,authorized_at,started_at,ended_at,version,
              package_run_id,cycle_type)
            VALUES(#{id},#{taskId},#{ordinal},#{kind},#{state},#{startStageId},#{startStageOrdinal},
              #{supplementalPrompt},#{budgetJson},#{failureCode},#{failureMessage},#{authorizedAt},#{startedAt},#{endedAt},#{version},
              #{packageRunId},#{cycleType})
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
            INSERT INTO task_package_plan_revision(id,task_id,designer_session_id,requirement_revision_id,
              revision,state,origin,plan_json,impact_json,external_session_id,external_session_state,
              last_error_code,last_error_detail,base_checkpoint_id,base_task_version,base_package_run_id,
              base_package_version,created_at,updated_at,approved_at,superseded_at,version)
            VALUES(#{id},#{taskId},#{designerSessionId},#{requirementRevisionId},#{revision},#{state},#{origin},
              #{planJson},#{impactJson},#{externalSessionId},#{externalSessionState},#{lastErrorCode},#{lastErrorDetail},
              #{baseCheckpointId},#{baseTaskVersion},#{basePackageRunId},#{basePackageVersion},#{createdAt},#{updatedAt},
              #{approvedAt},#{supersededAt},#{version})
            """)
    int insertTaskPackagePlanRevision(TaskPackagePlanRevisionRow row);
    @Select("SELECT * FROM task_package_plan_revision WHERE task_id=#{taskId} AND state='ACTIVE' LIMIT 1")
    Optional<TaskPackagePlanRevisionRow> activeTaskPackagePlanRevision(String taskId);
    @Select("SELECT * FROM task_package_plan_revision WHERE id=#{id}")
    Optional<TaskPackagePlanRevisionRow> findTaskPackagePlanRevision(String id);
    @Select("SELECT * FROM task_package_plan_revision WHERE task_id=#{taskId} ORDER BY revision DESC")
    List<TaskPackagePlanRevisionRow> listTaskPackagePlanRevisions(String taskId);
    @Select("SELECT * FROM task_package_plan_revision WHERE state='GENERATING' ORDER BY created_at")
    List<TaskPackagePlanRevisionRow> listGeneratingTaskPackagePlanRevisions();
    @Update("""
            UPDATE task_package_plan_revision SET state=#{state},plan_json=#{planJson},impact_json=#{impactJson},
              external_session_id=#{externalSessionId},external_session_state=#{externalSessionState},
              last_error_code=#{lastErrorCode},last_error_detail=#{lastErrorDetail},updated_at=#{updatedAt},
              approved_at=#{approvedAt},superseded_at=#{supersededAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateTaskPackagePlanRevision(TaskPackagePlanRevisionRow row);

    @Insert("""
            INSERT INTO task_package_run(id,task_id,plan_revision_id,design_work_package_id,package_key,
              ordinal,title,state,correction_of_package_run_id,discussion_revision,design_revision,
              accepted_design_revision,waiting_reason_code,created_at,updated_at,version,resume_checkpoint_id)
            VALUES(#{id},#{taskId},#{planRevisionId},#{designWorkPackageId},#{packageKey},#{ordinal},#{title},
              #{state},#{correctionOfPackageRunId},#{discussionRevision},#{designRevision},
              #{acceptedDesignRevision},#{waitingReasonCode},#{createdAt},#{updatedAt},#{version},#{resumeCheckpointId})
            """)
    int insertTaskPackageRun(TaskPackageRunRow row);
    @Select("SELECT * FROM task_package_run WHERE id=#{id}") Optional<TaskPackageRunRow> findTaskPackageRun(String id);
    @Select("SELECT * FROM task_package_run WHERE task_id=#{taskId} ORDER BY ordinal,created_at")
    List<TaskPackageRunRow> listTaskPackageRuns(String taskId);
    @Select("SELECT * FROM task_package_run WHERE task_id=#{taskId} AND state NOT IN ('FACT_FROZEN','SUPERSEDED','CANCELLED') ORDER BY ordinal LIMIT 1")
    Optional<TaskPackageRunRow> currentTaskPackageRun(String taskId);
    @Update("""
            UPDATE task_package_run SET state=#{state},discussion_revision=#{discussionRevision},
              design_revision=#{designRevision},accepted_design_revision=#{acceptedDesignRevision},
              waiting_reason_code=#{waitingReasonCode},resume_checkpoint_id=#{resumeCheckpointId},
              updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateTaskPackageRun(TaskPackageRunRow row);
    @Update("UPDATE task_package_run SET resume_checkpoint_id=#{checkpointId},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateTaskPackageRunResumeCheckpoint(@Param("id") String id, @Param("checkpointId") String checkpointId,
                                             @Param("updatedAt") String updatedAt, @Param("version") long version);

    @Insert("""
            INSERT INTO task_spec_revision(id,task_id,revision,package_run_id,spec_json,spec_sha256,stage_count,created_at)
            VALUES(#{id},#{taskId},#{revision},#{packageRunId},#{specJson},#{specSha256},#{stageCount},#{createdAt})
            """)
    int insertTaskSpecRevision(TaskSpecRevisionRow row);
    @Select("SELECT * FROM task_spec_revision WHERE task_id=#{taskId} ORDER BY revision DESC LIMIT 1")
    Optional<TaskSpecRevisionRow> latestTaskSpecRevision(String taskId);
    @Select("SELECT * FROM task_spec_revision WHERE task_id=#{taskId} ORDER BY revision")
    List<TaskSpecRevisionRow> listTaskSpecRevisions(String taskId);

    @Insert("""
            INSERT INTO package_fact_snapshot(id,task_id,package_run_id,checkpoint_id,successful_attempt_id,
              input_tree,output_tree,manifest_sha256,diff_sha256,evidence_sha256,proven_json,
              accepted_contract_json,navigation_summary,task_spec_sha256,created_at)
            VALUES(#{id},#{taskId},#{packageRunId},#{checkpointId},#{successfulAttemptId},#{inputTree},
              #{outputTree},#{manifestSha256},#{diffSha256},#{evidenceSha256},#{provenJson},
              #{acceptedContractJson},#{navigationSummary},#{taskSpecSha256},#{createdAt})
            """)
    int insertPackageFactSnapshot(PackageFactSnapshotRow row);
    @Select("SELECT * FROM package_fact_snapshot WHERE package_run_id=#{packageRunId}")
    Optional<PackageFactSnapshotRow> findPackageFactSnapshot(String packageRunId);
    @Select("SELECT * FROM package_fact_snapshot WHERE task_id=#{taskId} ORDER BY created_at")
    List<PackageFactSnapshotRow> listPackageFactSnapshots(String taskId);
    @Delete("DELETE FROM package_fact_snapshot WHERE task_id=#{taskId}")
    int deletePackageFactSnapshotsForTask(String taskId);
    @Delete("DELETE FROM task_spec_revision WHERE task_id=#{taskId}")
    int deleteTaskSpecRevisionsForTask(String taskId);
    @Update("UPDATE task_package_run SET resume_checkpoint_id=NULL,correction_of_package_run_id=NULL WHERE task_id=#{taskId}")
    int detachTaskPackageRunReferences(String taskId);
    @Update("UPDATE task_package_plan_revision SET base_checkpoint_id=NULL,base_package_run_id=NULL WHERE task_id=#{taskId}")
    int detachTaskPackagePlanRevisionReferences(String taskId);
    @Delete("DELETE FROM task_execution_cycle WHERE task_id=#{taskId}")
    int deleteTaskExecutionCyclesForTask(String taskId);
    @Delete("DELETE FROM task_package_run WHERE task_id=#{taskId}")
    int deleteTaskPackageRunsForTask(String taskId);
    @Delete("DELETE FROM task_package_plan_revision WHERE task_id=#{taskId}")
    int deleteTaskPackagePlanRevisionsForTask(String taskId);

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

    @Insert("INSERT INTO task_lineage(child_task_id,parent_task_id,recovery_mode,parent_stage_id,workspace_fingerprint,created_at,design_source_task_id,design_source_loop_draft_id,design_source_designer_session_id) VALUES(#{childTaskId},#{parentTaskId},#{recoveryMode},#{parentStageId},#{workspaceFingerprint},#{createdAt},#{designSourceTaskId},#{designSourceLoopDraftId},#{designSourceDesignerSessionId})")
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
    @Select("""
            SELECT * FROM designer_auto_mode
            WHERE state='ACTIVE'
               OR (state='BLOCKED' AND error_code='TASK_PROFILE_DECISION_REQUIRED')
            ORDER BY updated_at,designer_session_id
            """)
    List<DesignerAutoModeRow> listDesignerAutoModesForAdvance();
    @Update("""
            UPDATE designer_auto_mode SET state=#{state},last_action=#{lastAction},error_code=#{errorCode},
              error_detail=#{errorDetail},task_id=#{taskId},authorized_at=#{authorizedAt},
              disabled_at=#{disabledAt},updated_at=#{updatedAt},version=version+1
            WHERE designer_session_id=#{designerSessionId} AND version=#{version}
            """)
    int updateDesignerAutoMode(DesignerAutoModeRow row);
}
