package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** Finish scope includes all historical attempts, not only nodes in the latest canvas. */
@Mapper
public interface WorkflowFinishMapper {
    record Intent(String requirementId,String targetState,String reason,int planRevision,long requestedVersion,
                  String requestedAt,String finalizedAt) { }
    record Pending(long attempts,long resources) {
        public boolean empty(){return attempts==0 && resources==0;}
    }
    @Select("SELECT * FROM workflow_finish_intent WHERE requirement_id=#{id}") Optional<Intent> find(String id);
    @Select("SELECT requirement_id FROM workflow_finish_intent WHERE finalized_at IS NULL AND requirement_id>#{after} ORDER BY requirement_id LIMIT #{limit}")
    List<String> pending(String after,int limit);
    @Select("""
        SELECT a.id FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
        WHERE n.requirement_id=#{id} AND a.state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
        AND a.id>#{after} ORDER BY a.id LIMIT #{limit}
        """) List<String> attempts(String id,String after,int limit);
    @Select("""
        SELECT
          (SELECT count(*) FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
           WHERE n.requirement_id=#{id} AND (a.state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
             OR NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id))) AS attempts,
          (SELECT count(*) FROM workflow_model_launch WHERE requirement_id=#{id} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
          +(SELECT count(*) FROM workflow_command_run WHERE requirement_id=#{id} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
          +(SELECT count(*) FROM workflow_writer_queue WHERE requirement_id=#{id} AND state IN ('QUEUED','ADMITTED'))
          +(SELECT count(*) FROM workflow_workspace WHERE requirement_id=#{id} AND state<>'RELEASED')
          +(SELECT count(*) FROM workspace_lease l JOIN workflow_node_attempt a ON a.id=l.holder_workflow_attempt_id
            JOIN workflow_node_run n ON n.id=a.node_run_id WHERE n.requirement_id=#{id} AND l.state IN ('HELD','RELEASE_PENDING')) AS resources
        """) Pending remaining(String id);
    @Insert("""
        INSERT INTO workflow_finish_intent(requirement_id,target_state,reason,plan_revision,requested_version,requested_at)
        VALUES(#{requirementId},#{targetState},#{reason},#{planRevision},#{requestedVersion},#{requestedAt})
        """) int insert(Intent row);
    @Update("UPDATE workflow_finish_intent SET finalized_at=#{now} WHERE requirement_id=#{id} AND finalized_at IS NULL")
    int finalizeIntent(String id,String now);
}
