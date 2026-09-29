package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowNativeTestMapper {
    record Evidence(String attemptId,String requestSha256,String resultJson,String resultSha256,String reportJson,String reportSha256,String createdAt){ }
    @Select("SELECT * FROM workflow_native_test_evidence WHERE attempt_id=#{id}") Optional<Evidence> find(String id);
    @Insert("INSERT INTO workflow_native_test_evidence(attempt_id,request_sha256,result_json,result_sha256,report_json,report_sha256,created_at) VALUES(#{attemptId},#{requestSha256},#{resultJson},#{resultSha256},#{reportJson},#{reportSha256},#{createdAt})") int insert(Evidence row);
}
