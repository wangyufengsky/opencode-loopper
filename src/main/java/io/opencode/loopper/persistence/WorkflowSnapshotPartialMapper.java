package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Fixed-query observation of current bindings; unfinished accepted candidates never count. */
@Mapper
public interface WorkflowSnapshotPartialMapper {
    record Result(String attemptId,String title,String module,String contentJson,String sha256){ }
    String FROM="""
        FROM workflow_plan_node p JOIN workflow_node_run n ON n.id=p.node_run_id
        JOIN workflow_node_attempt a ON a.id=n.latest_attempt_id
        JOIN workflow_node_delivery d ON d.attempt_id=a.id
        JOIN workflow_attempt_stop s ON s.attempt_id=a.id
        JOIN workflow_snapshot_work_input i ON i.attempt_id=a.id
        WHERE p.requirement_id=#{id} AND p.plan_revision=#{revision}
          AND n.requirement_id=#{id} AND n.state='SUCCEEDED' AND a.state='SUCCEEDED'
          AND a.adapter_key='model.readonly.v1'
          AND json_extract(n.definition_json,'$.kind')='WORK'
          AND json_extract(n.definition_json,'$.moduleVersion')=1
          AND json_extract(n.definition_json,'$.moduleId') IN ('snapshot.analyze','snapshot.review')
          AND i.source_attempt_id=#{source} AND i.source_sha256=#{sha}
        """;
    @Select("SELECT coalesce(sum(length(CAST(d.content_json AS BLOB))),0) "+FROM)
    long bytes(String id,int revision,String source,String sha);
    @Select("SELECT a.id AS attempt_id,json_extract(n.definition_json,'$.title') AS title,json_extract(n.definition_json,'$.moduleId') AS module,d.content_json,d.sha256 "+FROM+" ORDER BY n.node_key,a.id LIMIT 257")
    List<Result> results(String id,int revision,String source,String sha);
}
