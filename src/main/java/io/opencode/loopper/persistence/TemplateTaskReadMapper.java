package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bounded summaries; none of these queries reads frozen contracts, source patches or report bodies. */
@Mapper
public interface TemplateTaskReadMapper {
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
                run.repair_round,json_extract(run.contract_json,'$.documentPath') AS document_path,
                current.id AS report_attempt_id,
                (SELECT folder_name FROM template_report_bundle WHERE task_id=run.task_id AND attempt_id=current.id) AS report_folder_name
            FROM template_task_run run LEFT JOIN template_task_plan plan ON plan.task_id=run.task_id
            LEFT JOIN current ON 1=1 LEFT JOIN template_task_batch batch ON batch.attempt_id=current.id AND batch.task_id=run.task_id
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
