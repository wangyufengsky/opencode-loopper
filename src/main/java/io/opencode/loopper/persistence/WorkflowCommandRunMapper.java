package io.opencode.loopper.persistence;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface WorkflowCommandRunMapper {
    record Run(String attemptId,String requirementId,String projectId,String state,String requestJson,String requestSha256,
               String registrationJson,String resultJson,String resultSha256,boolean suspended,String lastErrorCode,long version,String createdAt,String updatedAt) { }
    @Select("SELECT * FROM workflow_command_run WHERE attempt_id=#{id}") Optional<Run> find(String id);
    @Select("SELECT EXISTS(SELECT 1 FROM workflow_command_run WHERE requirement_id=#{id} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED') AND (suspended=1 OR state='STOPPING'))") boolean unresolved(String id);
    @Select("SELECT attempt_id FROM workflow_command_run WHERE attempt_id>#{after} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED') AND (suspended=0 OR state='STOPPING') ORDER BY attempt_id LIMIT #{limit}") List<String> active(String after,int limit);
    @Insert("""
        INSERT INTO workflow_command_run(attempt_id,requirement_id,project_id,state,created_at,updated_at)
        VALUES(#{attemptId},#{requirementId},#{projectId},#{state},#{createdAt},#{updatedAt})
        """) int insert(Run row);
    @Update("""
        UPDATE workflow_command_run SET request_json=#{json},request_sha256=#{sha},version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state='PREPARING' AND request_json IS NULL
        """) int prepare(String id,long version,String json,String sha,String now);
    @Update("""
        UPDATE workflow_command_run SET registration_json=#{json},version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state IN ('READY','STOPPING') AND registration_json IS NULL
        """) int register(String id,long version,String json,String now);
    @Update("""
        UPDATE workflow_command_run SET result_json=#{json},result_sha256=#{sha},version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state IN ('READY','RUNNING','STOPPING') AND result_json IS NULL
        """) int result(String id,long version,String json,String sha,String now);
    @Update("""
        UPDATE workflow_command_run SET state=#{next},version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state=#{state}
        """) int transition(String id,long version,String state,String next,String now);
    @Update("""
        UPDATE workflow_command_run SET suspended=#{suspended},last_error_code=#{code},version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
        """) int suspend(String id,long version,boolean suspended,String code,String now);
    @Insert("""
        INSERT INTO workflow_attempt_stop(attempt_id,kind,external_session_id,evidence_json,created_at)
        VALUES(#{id},#{kind},NULL,#{evidence},#{now})
        """) int stopProof(String id,String kind,String evidence,String now);
}
