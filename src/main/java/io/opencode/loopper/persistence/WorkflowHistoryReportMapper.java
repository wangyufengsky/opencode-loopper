package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowHistoryReportMapper {
    record Format(String nodeRunId,String kind,String layoutJson,String sha256,String createdAt){ }
    record Bundle(String attemptId,String namespaceKey,long sequence,String projectName,String sourceSha256,String folderName,String mainPath){ }
    @Select("SELECT * FROM workflow_history_report_format WHERE node_run_id=#{id}") Optional<Format> format(String id);
    @Insert("INSERT INTO workflow_history_report_format(node_run_id,kind,layout_json,sha256,created_at) VALUES(#{nodeRunId},#{kind},#{layoutJson},#{sha256},#{createdAt})") int insertFormat(Format value);
    @Select("SELECT * FROM workflow_history_report_bundle WHERE attempt_id=#{id}") Optional<Bundle> bundle(String id);
    @Insert("INSERT INTO workflow_history_report_bundle(attempt_id,namespace_key,sequence,project_name,source_sha256,folder_name,main_path) VALUES(#{attemptId},#{namespaceKey},#{sequence},#{projectName},#{sourceSha256},#{folderName},#{mainPath})") int insertBundle(Bundle value);
}
