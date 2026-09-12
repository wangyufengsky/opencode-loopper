package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TemplateReportBundleMapper {
    @Select("SELECT * FROM template_report_bundle WHERE task_id=#{taskId} AND attempt_id=#{attemptId}")
    Optional<TemplateReportBundleRow> find(@Param("taskId") String taskId, @Param("attemptId") String attemptId);
    @Insert("""
            INSERT INTO template_report_sequence(namespace_key,last_sequence) VALUES(#{namespace},1)
            ON CONFLICT(namespace_key) DO UPDATE SET last_sequence=last_sequence+1
            """)
    int next(String namespace);
    @Select("SELECT last_sequence FROM template_report_sequence WHERE namespace_key=#{namespace}") long current(String namespace);
    @Insert("""
            INSERT INTO template_report_bundle(attempt_id,task_id,namespace_key,sequence,project_name,folder_name,main_path)
            VALUES(#{attemptId},#{taskId},#{namespaceKey},#{sequence},#{projectName},#{folderName},#{mainPath})
            """)
    int insert(TemplateReportBundleRow row);
    @Select("SELECT attempt_id FROM task_artifact WHERE task_id=#{taskId} AND id=#{artifactId} AND kind='TEMPLATE_REPORT'")
    Optional<String> reportAttempt(@Param("taskId") String taskId, @Param("artifactId") String artifactId);
    @Select("SELECT count(*) FROM task_artifact WHERE task_id=#{taskId} AND attempt_id=#{attemptId} AND kind='TEMPLATE_REPORT'")
    int reportCount(@Param("taskId") String taskId, @Param("attemptId") String attemptId);
    @Select("SELECT * FROM task_artifact WHERE task_id=#{taskId} AND attempt_id=#{attemptId} AND kind='TEMPLATE_REPORT' ORDER BY name,id")
    List<TaskArtifactRow> reports(@Param("taskId") String taskId, @Param("attemptId") String attemptId);
    @Select("SELECT COALESCE(sum(length(CAST(content AS BLOB))),0) FROM task_artifact WHERE task_id=#{taskId} AND attempt_id=#{attemptId} AND kind='TEMPLATE_REPORT'")
    long reportBytes(@Param("taskId") String taskId, @Param("attemptId") String attemptId);
}
