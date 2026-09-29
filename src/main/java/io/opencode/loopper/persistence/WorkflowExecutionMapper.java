package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;
import io.opencode.loopper.persistence.WorkflowExecutionRows.*;

@Mapper
public interface WorkflowExecutionMapper {
    @Select("SELECT id FROM workflow_node_attempt WHERE adapter_key='system.source.test-profile.v1' AND state='RUNNING' AND id>#{after} ORDER BY id LIMIT #{limit}")
    List<String> activeTestProfiles(String after,int limit);
    @Select("SELECT id FROM workflow_node_attempt WHERE adapter_key IN ('system.source.design-plan.v1','system.source.test-plan.v1','system.document.review-plan.v1','system.history.plan.v1','system.snapshot.plan.v1') AND state='RUNNING' AND id>#{after} ORDER BY id LIMIT #{limit}")
    List<String> activeSourcePlans(String after,int limit);
    @Select("SELECT id FROM workflow_node_attempt WHERE adapter_key='system.verify.files.v1' AND state='RUNNING' AND id>#{after} ORDER BY id LIMIT #{limit}")
    List<String> activeVerifications(String after,int limit);
    @Select("SELECT n.* FROM workflow_plan_node p JOIN workflow_node_run n ON n.id=p.node_run_id WHERE p.requirement_id=#{id} AND p.plan_revision=#{revision}")
    List<Node> planNodes(String id,int revision);
    @Select("SELECT node_key,max(attempt_count) AS attempts FROM workflow_node_run WHERE requirement_id=#{id} GROUP BY node_key")
    List<AttemptCount> attemptCounts(String id);
    record AttemptCount(String nodeKey,int attempts) { }
    @Select("SELECT * FROM workflow_node_run WHERE id=#{id}") Optional<Node> node(String id);
    @Select("""
        SELECT n.* FROM workflow_plan_node p JOIN workflow_node_run n ON n.id=p.node_run_id
        WHERE p.requirement_id=#{requirement} AND p.plan_revision=#{revision} AND p.node_key=#{key}
        """) Optional<Node> nodeInPlan(String requirement, int revision, String key);
    @Select("""
        SELECT n.id,n.node_key,n.state,n.attempt_count,n.latest_attempt_id,n.version,d.outcome
        FROM workflow_plan_node p JOIN workflow_node_run n ON n.id=p.node_run_id
        LEFT JOIN workflow_node_delivery d ON d.attempt_id=n.latest_attempt_id
        WHERE p.requirement_id=#{requirement} AND p.plan_revision=#{revision} ORDER BY n.node_key
        """) List<Summary> summaries(String requirement, int revision);
    @Insert("""
        INSERT INTO workflow_node_run(id,requirement_id,node_key,definition_json,definition_sha256,state,attempt_count,
            latest_attempt_id,version,created_at,updated_at)
        VALUES(#{id},#{requirementId},#{nodeKey},#{definitionJson},#{definitionSha256},#{state},#{attemptCount},
            #{latestAttemptId},#{version},#{createdAt},#{updatedAt})
        """) int insertNode(Node row);
    @Insert("""
        INSERT INTO workflow_plan_node(requirement_id,plan_revision,node_key,node_run_id)
        VALUES(#{requirement},#{revision},#{key},#{node})
        """) int bind(String requirement, int revision, String key, String node);
    @Update("""
        UPDATE workflow_node_run SET state='ACTIVE',latest_attempt_id=#{attempt},attempt_count=attempt_count+1,
            version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND state=#{state}
        """) int activate(String id, long version, String state, String attempt, String now);
    @Update("""
        UPDATE workflow_node_run SET state=#{next},version=version+1,updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND state=#{state}
        """) int transitionNode(String id, long version, String state, String next, String now);
    @Select("SELECT * FROM workflow_node_attempt WHERE id=#{id}") Optional<Attempt> attempt(String id);
    @Insert("""
        INSERT INTO workflow_node_attempt(id,node_run_id,ordinal,plan_revision,state,inputs_json,inputs_sha256,
            role_snapshot_json,adapter_key,external_session_id,version,created_at,updated_at)
        VALUES(#{id},#{nodeRunId},#{ordinal},#{planRevision},#{state},#{inputsJson},#{inputsSha256},#{roleSnapshotJson},
            #{adapterKey},#{externalSessionId},#{version},#{createdAt},#{updatedAt})
        """) int insertAttempt(Attempt row);
    @Update("""
        UPDATE workflow_node_attempt SET state=#{next},version=version+1,updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND state=#{state}
        """) int transitionAttempt(String id, long version, String state, String next, String now);
    @Update("""
        UPDATE workflow_node_attempt SET external_session_id=#{session},version=version+1,updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND state='PREPARING' AND external_session_id IS NULL
        """) int attach(String id, long version, String session, String now);
    @Select("SELECT * FROM workflow_node_delivery WHERE attempt_id=#{id}") Optional<Delivery> delivery(String id);
    @Select("""
        <script>SELECT * FROM workflow_node_delivery WHERE attempt_id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
        """) List<Delivery> deliveries(Collection<String> ids);
    @Insert("""
        INSERT INTO workflow_node_delivery(attempt_id,content_json,sha256,outcome,created_at)
        VALUES(#{attemptId},#{contentJson},#{sha256},#{outcome},#{createdAt})
        """) int insertDelivery(Delivery row);
    @Select("""
        <script>SELECT d.attempt_id,a.node_run_id,n.requirement_id,d.content_json,d.sha256
        FROM workflow_node_delivery d JOIN workflow_node_attempt a ON a.id=d.attempt_id
        JOIN workflow_node_run n ON n.id=a.node_run_id JOIN workflow_attempt_stop s ON s.attempt_id=a.id
        WHERE a.state='SUCCEEDED' AND d.attempt_id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
        """) List<InputDelivery> inputDeliveries(Collection<String> ids);
    record InputDelivery(String attemptId, String nodeRunId, String requirementId, String contentJson, String sha256) { }
    @Select("SELECT * FROM workflow_attempt_stop WHERE attempt_id=#{id}") Optional<Stop> stop(String id);
    @Insert("""
        INSERT INTO workflow_attempt_stop(attempt_id,kind,external_session_id,evidence_json,created_at)
        VALUES(#{attemptId},#{kind},#{externalSessionId},#{evidenceJson},#{createdAt})
        """) int insertStop(Stop row);
    @Select("SELECT * FROM workflow_input_snapshot WHERE requirement_id=#{id} AND plan_revision=#{revision}")
    Optional<PublicInputs> inputs(String id, int revision);
    @Insert("""
        INSERT INTO workflow_input_snapshot(requirement_id,plan_revision,content_json,sha256,created_at)
        VALUES(#{requirementId},#{planRevision},#{contentJson},#{sha256},#{createdAt})
        """) int insertInputs(PublicInputs row);
}
