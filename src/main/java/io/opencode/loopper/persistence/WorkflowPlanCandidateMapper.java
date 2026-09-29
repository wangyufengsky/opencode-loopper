package io.opencode.loopper.persistence;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface WorkflowPlanCandidateMapper {
    record Candidate(String id,String requirementId,String attemptId,String outputName,int baseRevision,String graphJson,String sha256,
                     String state,long version,Integer appliedRevision,String decisionReason,String createdAt,String updatedAt) { }
    record Summary(String id,String attemptId,String nodeKey,String sourceTitle,int baseRevision,String state,long version,Integer appliedRevision,
                   String sourceState,boolean stale,String createdAt) { }
    @Select("SELECT * FROM workflow_plan_candidate WHERE requirement_id=#{id} AND id=#{candidate}") Optional<Candidate> find(String id,String candidate);
    @Select("SELECT * FROM workflow_plan_candidate WHERE attempt_id=#{attempt} AND output_name=#{output}") Optional<Candidate> byOutput(String attempt,String output);
    @Select("SELECT EXISTS(SELECT 1 FROM workflow_plan_candidate WHERE requirement_id=#{id} AND state='PENDING')") boolean pending(String id);
    @Select("""
        <script>SELECT c.id,c.attempt_id,n.node_key,json_extract(n.definition_json,'$.title') AS source_title,c.base_revision,c.state,c.version,c.applied_revision,
        a.state AS source_state,c.base_revision!=r.head_revision AS stale,c.created_at
        FROM workflow_plan_candidate c JOIN workflow_node_attempt a ON a.id=c.attempt_id JOIN workflow_node_run n ON n.id=a.node_run_id
        JOIN workflow_requirement r ON r.id=c.requirement_id WHERE c.requirement_id=#{id} AND (#{state} IS NULL OR c.state=#{state})
        AND (#{after} IS NULL OR c.created_at &lt; #{after} OR (c.created_at=#{after} AND c.id &lt; #{afterId})) ORDER BY c.created_at DESC,c.id DESC LIMIT #{limit}</script>
        """) List<Summary> page(String id,String state,String after,String afterId,int limit);
    @Insert("""
        INSERT INTO workflow_plan_candidate(id,requirement_id,attempt_id,output_name,base_revision,graph_json,sha256,state,version,created_at,updated_at)
        VALUES(#{id},#{requirementId},#{attemptId},#{outputName},#{baseRevision},#{graphJson},#{sha256},#{state},#{version},#{createdAt},#{updatedAt})
        """) int insert(Candidate row);
    @Update("""
        UPDATE workflow_plan_candidate SET state=#{state},version=version+1,applied_revision=#{revision},decision_reason=#{reason},updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND state='PENDING'
        """) int decide(String id,long version,String state,Integer revision,String reason,String now);
}
