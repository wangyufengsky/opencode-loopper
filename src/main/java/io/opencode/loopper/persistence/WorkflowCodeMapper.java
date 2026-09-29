package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowCodeMapper {
    record Snapshot(String id, String attemptId, String projectId, String requirementId, String repository,
            String rootFingerprint, String projectPrefix, String baseTree, String resultTree, String inputsSha256, String createdAt,String objectRepository) { }
    record Manifest(String snapshotId, String contentJson, String sha256, String createdAt) { }
    @Select("SELECT * FROM workflow_code_snapshot WHERE id=#{id}") Optional<Snapshot> snapshot(String id);
    @Select("SELECT * FROM workflow_code_snapshot WHERE attempt_id=#{id}") Optional<Snapshot> forAttempt(String id);
    @Select("SELECT * FROM workflow_code_manifest WHERE snapshot_id=#{id}") Optional<Manifest> manifest(String id);
    @Insert("""
        INSERT INTO workflow_code_snapshot(id,attempt_id,project_id,requirement_id,repository,root_fingerprint,
            project_prefix,base_tree,result_tree,inputs_sha256,created_at,object_repository)
        VALUES(#{id},#{attemptId},#{projectId},#{requirementId},#{repository},#{rootFingerprint},
            #{projectPrefix},#{baseTree},#{resultTree},#{inputsSha256},#{createdAt},#{objectRepository})
        """) int insert(Snapshot row);
    @Insert("""
        INSERT INTO workflow_code_manifest(snapshot_id,content_json,sha256,created_at)
        VALUES(#{snapshotId},#{contentJson},#{sha256},#{createdAt})
        """) int publish(Manifest row);
}
