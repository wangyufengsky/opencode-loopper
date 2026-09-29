package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowDirectoryMapper {
    record Preparation(String attemptId, String requirementId, String projectId, String canonicalRoot,
                       String rootFingerprint, String objectRepository, String inputsSha256, String manifestJson,
                       String manifestSha256, String baseTree, String sourceCommit, String createdAt) { }
    record Result(String attemptId,String manifestJson,String manifestSha256,String createdAt) { }
    record Apply(String id,String attemptId,String phase,String beforeJson,String beforeSha256,String afterJson,String afterSha256,String createdAt) { }
    @Select("SELECT * FROM workflow_directory_preparation WHERE attempt_id=#{id}") Optional<Preparation> find(String id);
    @Insert("""
        INSERT INTO workflow_directory_preparation(attempt_id,requirement_id,project_id,canonical_root,root_fingerprint,
            object_repository,inputs_sha256,created_at)
        VALUES(#{attemptId},#{requirementId},#{projectId},#{canonicalRoot},#{rootFingerprint},#{objectRepository},#{inputsSha256},#{createdAt})
        """) int insert(Preparation row);
    @Update("""
        UPDATE workflow_directory_preparation SET manifest_json=#{body},manifest_sha256=#{sha}
        WHERE attempt_id=#{id} AND manifest_json IS NULL
        """) int manifest(String id, String body, String sha);
    @Update("""
        UPDATE workflow_directory_preparation SET base_tree=#{tree},source_commit=#{commit}
        WHERE attempt_id=#{id} AND manifest_sha256=#{manifestSha} AND base_tree IS NULL
        """) int ready(String id, String manifestSha, String tree, String commit);
    @Select("SELECT * FROM workflow_directory_result WHERE attempt_id=#{id}") Optional<Result> result(String id);
    @Insert("INSERT INTO workflow_directory_result VALUES(#{attemptId},#{manifestJson},#{manifestSha256},#{createdAt})") int insertResult(Result row);
    @Select("SELECT * FROM workflow_directory_apply WHERE attempt_id=#{id} AND phase=#{phase}") Optional<Apply> apply(String id,String phase);
    @Select("SELECT * FROM workflow_directory_apply WHERE attempt_id=#{id} ORDER BY created_at,id") java.util.List<Apply> applies(String id);
    @Insert("INSERT INTO workflow_directory_apply VALUES(#{id},#{attemptId},#{phase},#{beforeJson},#{beforeSha256},#{afterJson},#{afterSha256},#{createdAt})") int insertApply(Apply row);
}
