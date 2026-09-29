package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowControlMapper {
    record Control(String requirementId,int planRevision,String mode,String targetKey,String modelJson,
                   String manualRetryNode,int manualRetryOrdinal,String state,String reasonCode,long version,String createdAt,String updatedAt) { }
    record Checkpoint(String attemptId,String requirementId,String nodeKey,String createdAt,String acknowledgedAt) { }
    @Select("SELECT * FROM workflow_run_control WHERE requirement_id=#{id}") Optional<Control> find(String id);
    @Select("""
        SELECT c.requirement_id FROM workflow_run_control c JOIN workflow_requirement r ON r.id=c.requirement_id
        WHERE c.state='ACTIVE' AND r.state IN ('PENDING_START','RUNNING','PAUSED','STALLED') AND c.requirement_id>#{after}
        ORDER BY c.requirement_id LIMIT #{limit}
        """) List<String> active(String after,int limit);
    @Insert("""
        INSERT INTO workflow_run_control(requirement_id,plan_revision,mode,target_key,model_json,manual_retry_node,manual_retry_ordinal,
        state,reason_code,version,created_at,updated_at)
        VALUES(#{requirementId},#{planRevision},#{mode},#{targetKey},#{modelJson},#{manualRetryNode},#{manualRetryOrdinal},
        #{state},#{reasonCode},#{version},#{createdAt},#{updatedAt})
        """) int insert(Control row);
    @Update("""
        UPDATE workflow_run_control SET plan_revision=#{row.planRevision},mode=#{row.mode},target_key=#{row.targetKey},
        model_json=#{row.modelJson},manual_retry_node=#{row.manualRetryNode},manual_retry_ordinal=#{row.manualRetryOrdinal},
        version=version+1,updated_at=#{row.updatedAt} WHERE requirement_id=#{row.requirementId} AND version=#{row.version} AND state<>'DONE'
        """) int configure(@Param("row") Control row);
    @Update("""
        UPDATE workflow_run_control SET state=#{state},reason_code=#{reason},version=version+1,updated_at=#{now}
        WHERE requirement_id=#{id} AND version=#{version} AND state=#{previous}
        """) int transition(String id,long version,String previous,String state,String reason,String now);
    @Select("SELECT * FROM workflow_node_checkpoint WHERE requirement_id=#{id} AND acknowledged_at IS NULL ORDER BY created_at,attempt_id")
    List<Checkpoint> pending(String id);
    @Select("SELECT * FROM workflow_node_checkpoint WHERE attempt_id=#{attempt}") Optional<Checkpoint> checkpoint(String attempt);
    @Insert("INSERT INTO workflow_node_checkpoint(attempt_id,requirement_id,node_key,created_at) VALUES(#{attempt},#{id},#{key},#{now})")
    int insertCheckpoint(String attempt,String id,String key,String now);
    @Update("UPDATE workflow_node_checkpoint SET acknowledged_at=#{now} WHERE requirement_id=#{id} AND attempt_id=#{attempt} AND acknowledged_at IS NULL")
    int acknowledge(String id,String attempt,String now);
}
