package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowReviewMapper {
    record Snapshot(String nodeRunId,String requirementId,String projectId,String branchId,String inputJson,String inputSha256,
                    String manifestJson,String manifestSha256,String createdAt) { }
    @Select("SELECT * FROM workflow_review_source WHERE node_run_id=#{id}") Optional<Snapshot> find(String id);
    @Insert("""
        INSERT INTO workflow_review_source(node_run_id,requirement_id,project_id,branch_id,input_json,input_sha256,created_at)
        VALUES(#{nodeRunId},#{requirementId},#{projectId},#{branchId},#{inputJson},#{inputSha256},#{createdAt})
        """) int insert(Snapshot row);
    @Update("""
        UPDATE workflow_review_source SET manifest_json=#{json},manifest_sha256=#{sha}
        WHERE node_run_id=#{id} AND manifest_json IS NULL
        """) int manifest(String id,String json,String sha);
}
