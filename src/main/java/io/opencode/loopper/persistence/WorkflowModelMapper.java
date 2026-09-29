package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowModelMapper {
    record Launch(String attemptId, String requirementId, String state, String directory, String modelJson,
                  String creationPlanJson, String promptJson, String promptSha256, boolean suspended,
                  String lastErrorCode, long version, String createdAt, String updatedAt) { }
    @Select("SELECT * FROM workflow_model_launch WHERE attempt_id=#{id}") Optional<Launch> find(String id);
    @Select("SELECT EXISTS(SELECT 1 FROM workflow_model_launch WHERE requirement_id=#{id} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED') AND (suspended=1 OR state='STOPPING'))")
    boolean unresolved(String id);
    @Select("""
        SELECT l.* FROM workflow_model_launch l JOIN workflow_node_attempt a ON a.id=l.attempt_id
        WHERE a.external_session_id=#{session}
        """) Optional<Launch> session(String session);
    @Select("""
        SELECT attempt_id FROM workflow_model_launch
        WHERE attempt_id>#{after} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
            AND (suspended=0 OR state='STOPPING') ORDER BY attempt_id LIMIT #{limit}
        """) List<String> active(String after, int limit);
    @Insert("""
        INSERT INTO workflow_model_launch(attempt_id,requirement_id,state,directory,model_json,suspended,version,created_at,updated_at)
        VALUES(#{attemptId},#{requirementId},#{state},#{directory},#{modelJson},0,0,#{createdAt},#{updatedAt})
        """) int insert(Launch row);
    @Update("""
        UPDATE workflow_model_launch SET creation_plan_json=#{plan},prompt_json=#{prompt},prompt_sha256=#{sha},
            version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state='PREPARING' AND creation_plan_json IS NULL
        """) int prepare(String id, long version, String plan, String prompt, String sha, String now);
    @Update("""
        UPDATE workflow_model_launch SET state=#{next},version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state=#{state}
        """) int transition(String id, long version, String state, String next, String now);
    @Update("""
        UPDATE workflow_model_launch SET suspended=#{suspended},last_error_code=#{code},version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
        """) int suspend(String id, long version, boolean suspended, String code, String now);
}
