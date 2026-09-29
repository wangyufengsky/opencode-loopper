package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowTestScopeMapper {
    record Baseline(String attemptId,String inputsSha256,String filesJson,String sha256,String createdAt){ }
    record Result(String attemptId,String checkpointTree,String filesJson,String filesSha256,String reportJson,String reportSha256,boolean passed,String createdAt){ }
    @Select("SELECT * FROM workflow_test_baseline WHERE attempt_id=#{id}") Optional<Baseline> baseline(String id);
    @Insert("INSERT INTO workflow_test_baseline(attempt_id,inputs_sha256,files_json,sha256,created_at) VALUES(#{attemptId},#{inputsSha256},#{filesJson},#{sha256},#{createdAt})") int insertBaseline(Baseline row);
    @Select("SELECT * FROM workflow_test_scope WHERE attempt_id=#{id}") Optional<Result> result(String id);
    @Insert("INSERT INTO workflow_test_scope(attempt_id,checkpoint_tree,files_json,files_sha256,report_json,report_sha256,passed,created_at) VALUES(#{attemptId},#{checkpointTree},#{filesJson},#{filesSha256},#{reportJson},#{reportSha256},#{passed},#{createdAt})") int insertResult(Result row);
}
