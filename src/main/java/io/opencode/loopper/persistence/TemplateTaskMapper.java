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
public interface TemplateTaskMapper {
    @Insert("INSERT INTO template_task_plan(task_id,review_batches,contributor_batches) VALUES(#{taskId},#{reviews},#{contributors}) ON CONFLICT(task_id) DO NOTHING")
    int insertPlan(@Param("taskId") String taskId, @Param("reviews") int reviews, @Param("contributors") int contributors);
    @Delete("DELETE FROM template_task_plan WHERE task_id=#{taskId}") int deletePlanForTask(String taskId);
    @Delete("DELETE FROM template_candidate_submission WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{taskId})")
    int deleteCandidateSubmissionsForTask(String taskId);
    @Delete("DELETE FROM template_length_continuation WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{taskId})")
    int deleteContinuationsForTask(String taskId);
    @Delete("DELETE FROM template_task_batch WHERE task_id=#{taskId}") int deleteBatchesForTask(String taskId);
    @Delete("DELETE FROM template_report_bundle WHERE task_id=#{taskId}") int deleteReportBundlesForTask(String taskId);
    @Delete("DELETE FROM template_task_run WHERE task_id=#{taskId}") int deleteRunForTask(String taskId);
    @Update("""
            UPDATE task SET worktree_path=#{worktreePath},branch_name=#{branchName},source_branch=#{sourceBranch},
                updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version} AND state='PREPARING'
            """)
    int bindWorkspace(TaskRow row);
    @Insert("""
            INSERT INTO template_task_run(task_id,request_key,request_sha256,template_id,template_version,
                branch_id,branch_label,branch_ref,remote_name,start_date,end_date,contract_json,
                snapshot_json,snapshot_sha256,repair_round,bypass_cache,created_at,updated_at,version)
            VALUES(#{taskId},#{requestKey},#{requestSha256},#{templateId},#{templateVersion},#{branchId},#{branchLabel},
                #{branchRef},#{remoteName},#{startDate},#{endDate},#{contractJson},#{snapshotJson},#{snapshotSha256},
                #{repairRound},#{bypassCache},#{createdAt},#{updatedAt},#{version})
            """)
    int insertRun(TemplateTaskRunRow row);

    @Select("SELECT * FROM template_task_run WHERE task_id=#{taskId}") Optional<TemplateTaskRunRow> findRun(String taskId);
    @Select("SELECT * FROM template_task_run WHERE request_key=#{requestKey}") Optional<TemplateTaskRunRow> findRequest(String requestKey);

    @Update("""
            UPDATE template_task_run SET snapshot_json=#{snapshotJson},snapshot_sha256=#{snapshotSha256},
                updated_at=#{updatedAt},version=version+1 WHERE task_id=#{taskId} AND version=#{version} AND snapshot_json IS NULL
            """)
    int freezeSnapshot(TemplateTaskRunRow row);

    @Update("""
            UPDATE template_task_run SET repair_round=repair_round+1,updated_at=#{now},version=version+1
            WHERE task_id=#{taskId} AND version=#{version} AND repair_round<2
            """)
    int advanceRepair(@Param("taskId") String taskId, @Param("version") long version, @Param("now") String now);

    @Select("""
            SELECT task.id FROM task JOIN template_task_run run ON run.task_id=task.id
            WHERE task.state IN ('QUEUED','PREPARING','READY','RUNNING','VERIFYING','RETRY_WAIT','JUDGING','STOPPING','AWAITING_DECISION','WAITING_INPUT')
            ORDER BY task.created_at,task.id
            """)
    List<String> activeTaskIds();

    @Insert("""
            INSERT INTO template_task_batch(id,task_id,attempt_id,session_id,ordinal,purpose,input_json,input_sha256,state,
                creation_plan_json,prompt_json,prompt_sha256,output_json,error_code,error_message,created_at,updated_at,version,generation)
            VALUES(#{id},#{taskId},#{attemptId},#{sessionId},#{ordinal},#{purpose},#{inputJson},#{inputSha256},#{state},
                #{creationPlanJson},#{promptJson},#{promptSha256},#{outputJson},#{errorCode},#{errorMessage},#{createdAt},#{updatedAt},#{version},#{generation})
            """)
    int insertBatch(TemplateTaskBatchRow row);
    @Select("SELECT * FROM template_task_batch WHERE id=#{id}") Optional<TemplateTaskBatchRow> findBatch(String id);
    @Select("SELECT * FROM template_task_batch WHERE task_id=#{taskId} AND attempt_id=#{attemptId} AND purpose=#{purpose} AND ordinal=#{ordinal} ORDER BY generation DESC LIMIT 1")
    Optional<TemplateTaskBatchRow> findBatchOrdinal(@Param("taskId") String taskId, @Param("attemptId") String attemptId,
            @Param("purpose") String purpose, @Param("ordinal") int ordinal);
    @Select("SELECT * FROM template_task_batch WHERE task_id=#{taskId} AND attempt_id=#{attemptId} ORDER BY purpose DESC,ordinal")
    List<TemplateTaskBatchRow> batches(@Param("taskId") String taskId, @Param("attemptId") String attemptId);

    @Select("""
            SELECT DISTINCT error_message FROM template_task_batch
            WHERE task_id=#{taskId} AND state='FAILED' AND error_code='TEMPLATE_CANDIDATE_INVALID'
              AND error_message IS NOT NULL ORDER BY error_message LIMIT 32
            """)
    List<String> candidateRepairErrors(String taskId);

    @Update("""
            UPDATE template_task_batch SET session_id=#{sessionId},creation_plan_json=#{creationPlanJson},
                prompt_json=#{promptJson},prompt_sha256=#{promptSha256},output_json=#{outputJson},error_code=#{errorCode},
                error_message=#{errorMessage},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}
            """)
    int updateBatchTransport(TemplateTaskBatchRow row);

    @Update("UPDATE template_task_batch SET state=#{state},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateBatchState(TemplateTaskBatchRow row);

    @Select("""
            SELECT batch.output_json FROM template_task_batch batch JOIN task ON task.id=batch.task_id
            JOIN attempt accepted ON accepted.id=batch.attempt_id
            WHERE batch.input_sha256=#{inputSha256} AND batch.state='VALIDATED' AND task.state='COMPLETED'
                AND accepted.state='SUCCEEDED' AND NOT EXISTS (
                    SELECT 1 FROM attempt newer WHERE newer.stage_id=accepted.stage_id AND newer.ordinal>accepted.ordinal)
            ORDER BY batch.updated_at DESC,batch.id DESC LIMIT 1
            """)
    Optional<String> acceptedCachedOutput(String inputSha256);
}
