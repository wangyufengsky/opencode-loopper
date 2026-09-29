package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowSnapshotReportMapper {
    record Bundle(String attemptId,String namespaceKey,long sequence,String projectName,String sourceSha256,String folderName,String mainPath){ }
    @Select("SELECT * FROM workflow_snapshot_report_bundle WHERE attempt_id=#{id}") Optional<Bundle> bundle(String id);
    @Insert("INSERT INTO workflow_snapshot_report_bundle(attempt_id,namespace_key,sequence,project_name,source_sha256,folder_name,main_path) VALUES(#{attemptId},#{namespaceKey},#{sequence},#{projectName},#{sourceSha256},#{folderName},#{mainPath})") int insert(Bundle bundle);
}
