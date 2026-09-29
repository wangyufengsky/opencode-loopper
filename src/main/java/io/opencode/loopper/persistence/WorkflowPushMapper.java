package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface WorkflowPushMapper {
    record Push(String id,String requirementId,String projectId,String publicationId,String requestKey,String requestSha256,String inputJson,String inputSha256,String state,long version,String activeAttemptId,String reasonCode,String createdAt,String updatedAt) { }
    record Attempt(String id,String pushId,int ordinal,String requestJson,String requestSha256,String registrationJson,String resultJson,long version,String createdAt) { }
    @Select("SELECT * FROM workflow_push WHERE requirement_id=#{id}") Optional<Push> find(String id);
    @Select("SELECT * FROM workflow_push WHERE request_key=#{key}") Optional<Push> request(String key);
    @Select("SELECT * FROM workflow_push_attempt WHERE id=#{id}") Optional<Attempt> attempt(String id);
    @Select("SELECT requirement_id FROM workflow_push WHERE state IN ('PREPARING','RUNNING') AND requirement_id>#{after} ORDER BY requirement_id LIMIT #{limit}") List<String> pending(String after,int limit);
    @Insert("""
        INSERT INTO workflow_push(id,requirement_id,project_id,publication_id,request_key,request_sha256,input_json,input_sha256,state,version,active_attempt_id,created_at,updated_at)
        VALUES(#{id},#{requirementId},#{projectId},#{publicationId},#{requestKey},#{requestSha256},#{inputJson},#{inputSha256},#{state},#{version},#{activeAttemptId},#{createdAt},#{updatedAt})
        """) int insert(Push row);
    @Insert("INSERT INTO workflow_push_attempt(id,push_id,ordinal,version,created_at) VALUES(#{id},#{pushId},#{ordinal},#{version},#{createdAt})") int insertAttempt(Attempt row);
    @Update("UPDATE workflow_push SET state=#{next},version=version+1,active_attempt_id=#{attempt},reason_code=#{reason},updated_at=#{now} WHERE id=#{id} AND version=#{version} AND state=#{previous}")
    int transition(String id,long version,String previous,String next,String attempt,String reason,String now);
    @Update("UPDATE workflow_push_attempt SET request_json=#{json},request_sha256=#{sha},version=version+1 WHERE id=#{id} AND version=#{version} AND request_json IS NULL") int prepare(String id,long version,String json,String sha);
    @Update("UPDATE workflow_push_attempt SET registration_json=#{json},version=version+1 WHERE id=#{id} AND version=#{version} AND registration_json IS NULL") int register(String id,long version,String json);
    @Update("UPDATE workflow_push_attempt SET result_json=#{json},version=version+1 WHERE id=#{id} AND version=#{version} AND result_json IS NULL") int result(String id,long version,String json);
}
