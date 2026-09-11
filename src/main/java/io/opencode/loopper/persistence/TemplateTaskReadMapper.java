package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bounded summaries; none of these queries reads frozen contracts, source patches or report bodies. */
@Mapper
public interface TemplateTaskReadMapper {
    @Select("""
            SELECT id,name,created_at FROM project WHERE (#{query}='' OR instr(lower(name),lower(#{query}))>0)
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

    record ProjectChoice(String id, String name, String createdAt) { }
    record RunSummary(String id, String title, String state, String projectName, String templateId, String branchLabel,
                      String startDate, String endDate, int repairRound, String createdAt, String updatedAt) { }
}
