package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** UI summaries never select frozen prompts, input bodies or delivery bodies. */
@Mapper
public interface WorkflowRunReadMapper {
    record Attempt(String id,int ordinal,int planRevision,String state,long version,String createdAt,String updatedAt,
                   String roleName,Integer roleRevisionNumber,boolean deliveryAccepted,boolean stopConfirmed,
                   String modelState,Long modelVersion,String commandState,Long commandVersion,boolean suspended,String errorCode,String queueState,String workspaceState) { }
    String SELECT="""
        SELECT a.id,a.ordinal,a.plan_revision,a.state,a.version,a.created_at,a.updated_at,
        json_extract(rr.manifest_json,'$.displayName') AS role_name,rr.revision_number AS role_revision_number,
        EXISTS(SELECT 1 FROM workflow_node_delivery d WHERE d.attempt_id=a.id) AS delivery_accepted,
        EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id) AS stop_confirmed,
        m.state AS model_state,m.version AS model_version,c.state AS command_state,c.version AS command_version,
        coalesce(m.suspended,c.suspended,0) AS suspended,coalesce(m.last_error_code,c.last_error_code) AS error_code,
        q.state AS queue_state,w.state AS workspace_state
        FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
        LEFT JOIN role_revision rr ON rr.revision_id=json_extract(a.role_snapshot_json,'$.revisionId')
        LEFT JOIN workflow_model_launch m ON m.attempt_id=a.id
        LEFT JOIN workflow_command_run c ON c.attempt_id=a.id
        LEFT JOIN workflow_writer_queue q ON q.attempt_id=a.id
        LEFT JOIN workflow_workspace w ON w.attempt_id=a.id
        WHERE n.requirement_id=#{id} AND n.node_key=#{key}
        """;
    @Select("<script>"+SELECT+" AND (#{after} IS NULL OR a.created_at &lt; #{after} OR (a.created_at=#{after} AND a.id &lt; #{afterId})) ORDER BY a.created_at DESC,a.id DESC LIMIT #{limit}</script>")
    List<Attempt> page(String id,String key,String after,String afterId,int limit);
    @Select(SELECT+" AND a.id=#{attempt}") Optional<Attempt> find(String id,String key,String attempt);
    @Select("SELECT n.* FROM workflow_node_run n JOIN workflow_node_attempt a ON a.node_run_id=n.id WHERE n.requirement_id=#{id} AND n.node_key=#{key} AND a.id=#{attempt}")
    Optional<WorkflowExecutionRows.Node> definition(String id,String key,String attempt);
}
