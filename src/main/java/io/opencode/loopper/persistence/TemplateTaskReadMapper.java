package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bounded summaries; none of these queries reads frozen contracts, source patches or report bodies. */
@Mapper
public interface TemplateTaskReadMapper {
    @org.apache.ibatis.annotations.Select("SELECT id,name,created_at,document_path FROM project WHERE id=#{id}")
    java.util.Optional<ProjectChoice> project(String id);
    @Select("""
            SELECT b.id,b.ordinal,b.purpose,b.generation,b.state,b.error_message,b.version,b.created_at
            FROM template_task_batch b JOIN attempt a ON a.id=b.attempt_id JOIN stage s ON s.id=a.stage_id
            WHERE b.task_id=#{taskId} AND (b.state='FAILED' OR (b.state='STOPPED' AND b.session_id IS NOT NULL))
              AND a.ordinal=(SELECT max(newer.ordinal) FROM attempt newer WHERE newer.stage_id=s.id)
              AND NOT EXISTS (SELECT 1 FROM template_task_batch n WHERE n.attempt_id=b.attempt_id
                AND n.purpose=b.purpose AND n.ordinal=b.ordinal AND n.generation>b.generation)
              AND (#{time} IS NULL OR b.created_at>#{time} OR (b.created_at=#{time} AND b.id>#{id}))
            ORDER BY b.created_at,b.id LIMIT #{limit}
            """)
    List<FailedBatch> failedBatches(@Param("taskId") String taskId, @Param("time") String time,
            @Param("id") String id, @Param("limit") int limit);
    /** Selection opens only after independent work has drained. */
    @Select("""
            SELECT EXISTS (SELECT 1 FROM task WHERE id=#{taskId} AND state='WAITING_INPUT') AND NOT EXISTS (
                SELECT 1 FROM template_task_batch b
                JOIN attempt a ON a.id=b.attempt_id JOIN stage s ON s.id=a.stage_id
                WHERE b.task_id=#{taskId}
                  AND b.state NOT IN ('VALIDATED','FAILED','STOPPED')
                  AND a.ordinal=(SELECT max(n.ordinal) FROM attempt n WHERE n.stage_id=s.id)
                  AND NOT EXISTS (SELECT 1 FROM template_task_batch n WHERE n.attempt_id=b.attempt_id
                    AND n.purpose=b.purpose AND n.ordinal=b.ordinal AND n.generation>b.generation)
            )
            """)
    boolean retrySelectionReady(String taskId);
    record FailedBatch(String id, int ordinal, String purpose, int generation, String state,
                       String errorMessage, long version, String createdAt) { }

    @Select("""
            WITH current AS (
                SELECT attempt.id FROM attempt JOIN stage ON stage.id=attempt.stage_id
                WHERE attempt.task_id=#{taskId} AND stage.ordinal=1
                  AND attempt.ordinal=(SELECT repair_round+1 FROM template_task_run WHERE task_id=#{taskId})
                ORDER BY attempt.ordinal DESC,attempt.created_at DESC,attempt.id DESC LIMIT 1
            )
            SELECT plan.review_batches,plan.contributor_batches,
                COALESCE(SUM(CASE WHEN batch.purpose='REVIEW' AND batch.state='VALIDATED' THEN 1 ELSE 0 END),0) AS completed_reviews,
                COALESCE(SUM(CASE WHEN batch.purpose='CONTRIBUTOR' AND batch.state='VALIDATED' THEN 1 ELSE 0 END),0) AS completed_contributors,
                COALESCE(SUM(CASE WHEN batch.state IN ('CREATING','PROMPT_READY','DISPATCHING','RUNNING') THEN 1 ELSE 0 END),0) AS active_batches,
                COALESCE(SUM(CASE WHEN batch.state='FAILED' THEN 1 ELSE 0 END),0) AS failed_batches,
                run.template_version,
                (SELECT COUNT(*) FROM task_artifact WHERE task_id=run.task_id AND kind='TEMPLATE_REPORT') AS report_count,
                run.repair_round,json_extract(run.contract_json,'$.documentPath') AS document_path,
                current.id AS report_attempt_id,
                (SELECT folder_name FROM template_report_bundle WHERE task_id=run.task_id AND attempt_id=current.id) AS report_folder_name
            FROM template_task_run run LEFT JOIN template_task_plan plan ON plan.task_id=run.task_id
            LEFT JOIN current ON 1=1 LEFT JOIN template_task_batch batch ON batch.attempt_id=current.id AND batch.task_id=run.task_id
                AND NOT EXISTS (SELECT 1 FROM template_task_batch newer WHERE newer.attempt_id=batch.attempt_id
                    AND newer.purpose=batch.purpose AND newer.ordinal=batch.ordinal AND newer.generation>batch.generation)
            WHERE run.task_id=#{taskId} GROUP BY run.task_id
            """)
    java.util.Optional<TemplateTaskProgressRow> progress(String taskId);

    @Select("""
            SELECT id,name,created_at,document_path FROM project WHERE managed=1 AND (#{query}='' OR instr(lower(name),lower(#{query}))>0)
            AND (#{time} IS NULL OR created_at>#{time} OR (created_at=#{time} AND id>#{id}))
            ORDER BY created_at,id LIMIT #{limit}
            """)
    List<ProjectChoice> projects(@Param("query") String query, @Param("time") String time, @Param("id") String id, @Param("limit") int limit);

    @Select("""
            SELECT sr.mode,json_extract(sr.snapshot_json,'$.targetSha') AS target_sha,
                json_extract(sr.snapshot_json,'$.baselineSha') AS baseline_sha,sr.plan_revision,
                (SELECT group_concat(state,',') FROM (SELECT state FROM stage WHERE task_id=sr.task_id ORDER BY ordinal)) AS stages,
                SUM(CASE WHEN b.purpose IN ('SNAPSHOT_PLAN','SNAPSHOT_LINKS') THEN 1 ELSE 0 END) AS planning,
                SUM(CASE WHEN b.purpose IN ('SNAPSHOT_PLAN','SNAPSHOT_LINKS') AND b.state='VALIDATED' THEN 1 ELSE 0 END) AS planned,
                SUM(CASE WHEN b.purpose IN ('SNAPSHOT_ANALYSIS','SNAPSHOT_SUPPLEMENT','SNAPSHOT_RELATION_ANALYSIS') THEN 1 ELSE 0 END) AS analyses,
                SUM(CASE WHEN b.purpose IN ('SNAPSHOT_ANALYSIS','SNAPSHOT_SUPPLEMENT','SNAPSHOT_RELATION_ANALYSIS') AND b.state='VALIDATED' THEN 1 ELSE 0 END) AS analyzed,
                SUM(CASE WHEN b.purpose IN ('SNAPSHOT_REVIEW','SNAPSHOT_RELATION_REVIEW') THEN 1 ELSE 0 END) AS reviews,
                SUM(CASE WHEN b.purpose IN ('SNAPSHOT_REVIEW','SNAPSHOT_RELATION_REVIEW') AND b.state='VALIDATED' THEN 1 ELSE 0 END) AS reviewed,
                SUM(CASE WHEN b.state IN ('CREATING','PROMPT_READY','DISPATCHING','RUNNING','STOPPING') THEN 1 ELSE 0 END) AS active,
                SUM(CASE WHEN b.state='FAILED' OR b.state='STOPPED' AND b.session_id IS NOT NULL THEN 1 ELSE 0 END) AS failed,
                SUM(CASE WHEN b.purpose='SNAPSHOT_SUPPLEMENT' THEN 1 ELSE 0 END) AS supplements,
                (SELECT COUNT(*) FROM task_artifact WHERE task_id=sr.task_id AND kind='TEMPLATE_REPORT') AS report_count,
                json_extract(tr.contract_json,'$.documentPath') AS document_path,
                (SELECT folder_name FROM template_report_bundle WHERE task_id=sr.task_id ORDER BY sequence DESC LIMIT 1) AS folder
            FROM snapshot_review_run sr JOIN template_task_run tr ON tr.task_id=sr.task_id
            LEFT JOIN template_task_batch b ON b.task_id=sr.task_id
                AND NOT EXISTS (SELECT 1 FROM template_task_batch n WHERE n.attempt_id=b.attempt_id AND n.purpose=b.purpose AND n.ordinal=b.ordinal AND n.generation>b.generation)
            WHERE sr.task_id=#{taskId} GROUP BY sr.task_id
            """)
    java.util.Optional<SnapshotReviewProgressRow> snapshotProgress(String taskId);

    @Select("""
            SELECT b.id,b.purpose,b.state,b.ordinal,b.generation,b.created_at,
                json_extract(b.input_json,'$.snapshot.objective') AS title,b.error_message
            FROM template_task_batch b WHERE b.task_id=#{taskId} AND b.purpose LIKE 'SNAPSHOT_%'
                AND NOT EXISTS (SELECT 1 FROM template_task_batch n WHERE n.attempt_id=b.attempt_id AND n.purpose=b.purpose AND n.ordinal=b.ordinal AND n.generation>b.generation)
                AND (#{time} IS NULL OR b.created_at>#{time} OR (b.created_at=#{time} AND b.id>#{id})) ORDER BY b.created_at,b.id LIMIT #{limit}
            """)
    List<SnapshotBatchSummary> snapshotBatches(String taskId, String time, String id, int limit);
    record SnapshotBatchSummary(String id, String purpose, String state, int ordinal, int generation, String createdAt, String title, String errorMessage) { }

    @Select("""
            SELECT task.id,task.title,task.state,project.name AS project_name,run.template_id,run.branch_label,
                run.start_date,run.end_date,run.repair_round,task.created_at,task.updated_at
            FROM task JOIN template_task_run run ON run.task_id=task.id JOIN project ON project.id=task.project_id
            WHERE (#{projectId} IS NULL OR task.project_id=#{projectId})
              AND (#{time} IS NULL OR task.created_at<#{time} OR (task.created_at=#{time} AND task.id<#{id}))
            ORDER BY task.created_at DESC,task.id DESC LIMIT #{limit}
            """)
    List<RunSummary> runs(@Param("projectId") String projectId, @Param("time") String time, @Param("id") String id, @Param("limit") int limit);

    record ProjectChoice(String id, String name, String createdAt, String documentPath) { }
    record RunSummary(String id, String title, String state, String projectName, String templateId, String branchLabel,
                      String startDate, String endDate, int repairRound, String createdAt, String updatedAt) { }
}
