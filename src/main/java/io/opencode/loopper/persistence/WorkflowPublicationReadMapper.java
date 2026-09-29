package io.opencode.loopper.persistence;

import io.opencode.loopper.workflow.WorkflowPublicationPreview.Source;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Bounded metadata projection. Neither delivery bodies nor file contents leave the source listing. */
@Mapper
public interface WorkflowPublicationReadMapper {
    String SOURCES="""
        SELECT n.node_key,json_extract(n.definition_json,'$.title') AS node_title,a.id AS attempt_id,a.ordinal,a.state AS attempt_state,
            o.key AS output_name,coalesce((SELECT json_extract(v.value,'$.title') FROM json_each(n.definition_json,'$.outputs') v
                WHERE json_extract(v.value,'$.name')=o.key LIMIT 1),o.key) AS output_title,
            d.created_at,json_array_length(m.content_json,'$.changes') AS changed_files,json_array_length(m.content_json,'$.files') AS total_files
        FROM workflow_requirement r JOIN workflow_plan_node p ON p.requirement_id=r.id AND p.plan_revision=r.head_revision
        JOIN workflow_node_run n ON n.id=p.node_run_id JOIN workflow_node_attempt a ON a.id=n.latest_attempt_id
        JOIN workflow_attempt_stop s ON s.attempt_id=a.id JOIN workflow_node_delivery d ON d.attempt_id=a.id
        JOIN json_each(d.content_json,'$.outputs') o
        JOIN workflow_code_snapshot c ON c.attempt_id=a.id AND c.requirement_id=r.id AND c.project_id=r.project_id
        JOIN workflow_code_manifest m ON m.snapshot_id=c.id
        WHERE r.id=#{id} AND r.head_revision=#{revision} AND a.state IN ('SUCCEEDED','FAILED') AND a.adapter_key='model.write.v1'
          AND json_extract(o.value,'$.kind')='CODE' AND json_extract(o.value,'$.content.version')=1
          AND json_extract(o.value,'$.content.snapshotId')=c.id AND json_extract(o.value,'$.content.sha256')=m.sha256
        """;
    @Select(SOURCES+"""
         AND (#{time} IS NULL OR d.created_at<#{time} OR (d.created_at=#{time} AND a.id||':'||o.key<#{key}))
         ORDER BY d.created_at DESC,a.id||':'||o.key DESC LIMIT #{limit}
        """) List<Source> page(String id,int revision,String time,String key,int limit);
    @Select(SOURCES+" AND n.node_key=#{node} AND a.id=#{attempt} AND o.key=#{output}") Optional<Source> find(String id,int revision,String node,String attempt,String output);
}
