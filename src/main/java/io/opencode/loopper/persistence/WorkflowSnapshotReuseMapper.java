package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowSnapshotReuseMapper {
    record Context(String attemptId,String fingerprint,String inputSha256,String createdAt){ }
    record Receipt(String attemptId,String sourceAttemptId,String sourceRequirementId,String sourceTitle,String sourceNodeTitle,
                   String fingerprint,String sourceDeliverySha256,String claimsJson,String claimsSha256,String createdAt){ }
    record Source(String attemptId,String requirementId,String title,String nodeTitle,String inputJson,String inputSha256,String contentJson,String sha256){ }
    @Select("SELECT * FROM workflow_snapshot_reuse_context WHERE attempt_id=#{id}") Optional<Context> context(String id);
    @Insert("INSERT INTO workflow_snapshot_reuse_context VALUES(#{attemptId},#{fingerprint},#{inputSha256},#{createdAt})") int insertContext(Context row);
    @Select("SELECT * FROM workflow_snapshot_reuse WHERE attempt_id=#{id}") Optional<Receipt> receipt(String id);
    @Insert("INSERT INTO workflow_snapshot_reuse VALUES(#{attemptId},#{sourceAttemptId},#{sourceRequirementId},#{sourceTitle},#{sourceNodeTitle},#{fingerprint},#{sourceDeliverySha256},#{claimsJson},#{claimsSha256},#{createdAt})") int reuse(Receipt row);
    String SOURCE="""
        SELECT a.id AS attempt_id,r.id AS requirement_id,r.title,json_extract(n.definition_json,'$.title') AS node_title,
               i.input_json,i.sha256 AS input_sha256,d.content_json,d.sha256
        FROM workflow_snapshot_reuse_context c JOIN workflow_node_attempt a ON a.id=c.attempt_id
        JOIN workflow_node_run n ON n.id=a.node_run_id JOIN workflow_requirement r ON r.id=n.requirement_id
        JOIN workflow_plan_node p ON p.requirement_id=r.id AND p.plan_revision=r.head_revision AND p.node_run_id=n.id
        JOIN workflow_model_launch m ON m.attempt_id=a.id JOIN workflow_attempt_stop s ON s.attempt_id=a.id
        JOIN workflow_snapshot_work_input i ON i.attempt_id=a.id JOIN workflow_node_delivery d ON d.attempt_id=a.id
        WHERE c.fingerprint=#{fingerprint} AND c.input_sha256=i.sha256 AND r.id<>#{requirement}
          AND a.id=n.latest_attempt_id AND a.state='SUCCEEDED' AND n.state='SUCCEEDED' AND m.state='SUCCEEDED'
          AND d.outcome='NO_FINDINGS'
          AND json_array_length(d.content_json,'$.outputs.analysis.content.claims.limitations')=0
          AND NOT EXISTS(SELECT 1 FROM json_each(d.content_json,'$.outputs.analysis.content.claims.coverage') cv
              WHERE json_array_length(cv.value,'$.evidence')<>0 OR json_array_length(cv.value,'$.limitations')<>0)
          AND a.external_session_id IS NOT NULL AND s.external_session_id=a.external_session_id AND s.kind='SESSION_TERMINAL'
          AND a.adapter_key='model.readonly.v1' AND json_extract(n.definition_json,'$.kind')='WORK'
          AND json_extract(n.definition_json,'$.moduleId')='snapshot.analyze' AND json_extract(n.definition_json,'$.moduleVersion')=1
        """;
    @Select(SOURCE+" ORDER BY d.created_at DESC,a.id DESC LIMIT 1") Optional<Source> source(String requirement,String fingerprint);
    @Select(SOURCE+" AND a.id=#{id}") Optional<Source> sourceById(String requirement,String fingerprint,String id);
    @Select("SELECT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id WHERE n.requirement_id=#{id} AND a.state IN ('FAILED','CANCELLED'))") boolean priorFailure(String id);
}
