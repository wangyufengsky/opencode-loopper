package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowWritebackMapper {
    record Row(String id,String requirementId,String projectId,String requestKey,String requestSha256,String intentJson,
               String intentSha256,String state,long version,String preparedAt,String createdAt,String updatedAt) { }
    record Receipt(String writebackId,String targetSha256,long leaseVersion,String confirmedAt) { }
    @Select("SELECT * FROM workflow_writeback WHERE requirement_id=#{requirement}") Optional<Row> find(String requirement);
    @Select("SELECT * FROM workflow_writeback WHERE request_key=#{key}") Optional<Row> request(String key);
    @Insert("""
        INSERT INTO workflow_writeback(id,requirement_id,project_id,request_key,request_sha256,intent_json,intent_sha256,state,version,prepared_at,created_at,updated_at)
        VALUES(#{id},#{requirementId},#{projectId},#{requestKey},#{requestSha256},#{intentJson},#{intentSha256},#{state},#{version},#{preparedAt},#{createdAt},#{updatedAt})
        """) int insert(Row row);
    @Update("""
        UPDATE workflow_writeback SET state=#{state},version=version+1,prepared_at=#{prepared},updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND state=#{previous}
        """) int transition(String id,long version,String previous,String state,String prepared,String now);
    @Insert("INSERT INTO workflow_writeback_receipt(writeback_id,target_sha256,lease_version,confirmed_at) VALUES(#{writebackId},#{targetSha256},#{leaseVersion},#{confirmedAt})")
    int insertReceipt(Receipt receipt);
    @Select("SELECT * FROM workflow_writeback_receipt WHERE writeback_id=#{id}") Optional<Receipt> receipt(String id);
    @Select("""
        SELECT w.requirement_id FROM workflow_writeback w JOIN workflow_writeback_queue q ON q.writeback_id=w.id
        WHERE w.state IN ('CONFIRMED','APPLYING') AND q.state='ADMITTED' AND w.requirement_id>#{cursor} ORDER BY w.requirement_id LIMIT #{limit}
        """) List<String> pending(String cursor,int limit);
    @Select("SELECT reason_code FROM state_transition_event WHERE machine_type='WORKFLOW_WRITEBACK' AND entity_id=#{id} AND to_state='BLOCKED' ORDER BY sequence DESC LIMIT 1")
    Optional<String> failure(String id);
}
