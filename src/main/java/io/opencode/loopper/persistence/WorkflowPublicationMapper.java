package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowPublicationMapper {
    record Row(String id,String requirementId,String projectId,String requestKey,String requestSha256,
               String intentJson,String intentSha256,String state,long version,String commitSha,String createdAt,String updatedAt) { }
    @Select("SELECT * FROM workflow_publication WHERE requirement_id=#{id}") Optional<Row> find(String id);
    @Select("SELECT * FROM workflow_publication WHERE request_key=#{key}") Optional<Row> request(String key);
    @Select("SELECT reason_code FROM state_transition_event WHERE machine_type='WORKFLOW_PUBLICATION' AND entity_id=#{id} AND to_state='BLOCKED' ORDER BY sequence DESC LIMIT 1")
    Optional<String> failure(String id);
    @Select("SELECT requirement_id FROM workflow_publication WHERE state='CONFIRMED' AND requirement_id>#{after} ORDER BY requirement_id LIMIT #{limit}")
    List<String> pending(String after,int limit);
    @Insert("""
        INSERT INTO workflow_publication(id,requirement_id,project_id,request_key,request_sha256,intent_json,intent_sha256,
            state,version,created_at,updated_at)
        VALUES(#{id},#{requirementId},#{projectId},#{requestKey},#{requestSha256},#{intentJson},#{intentSha256},
            #{state},#{version},#{createdAt},#{updatedAt})
        """) int insert(Row row);
    @Update("""
        UPDATE workflow_publication SET state=#{next},version=version+1,commit_sha=#{commit},updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND state=#{previous}
        """) int transition(String id,long version,String previous,String next,String commit,String now);
}
