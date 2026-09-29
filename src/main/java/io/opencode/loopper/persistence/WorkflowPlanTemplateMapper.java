package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** Bounded source metadata only; task input values and deliveries never enter template exports. */
@Mapper
public interface WorkflowPlanTemplateMapper {
    record Source(String templateId, String requirementId, int planRevision, String planSha256,
                  String templateSha256, String mode, String createdAt) { }
    record AppliedPlanner(String nodeKey, String definitionJson, String definitionSha256) { }
    @Select("""
        SELECT a.plan_revision FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
        WHERE n.requirement_id=#{id} ORDER BY a.created_at,a.id LIMIT 1
        """) Optional<Integer> firstExecutionRevision(String id);
    @Select("""
        SELECT n.node_key,n.definition_json,n.definition_sha256 FROM workflow_plan_node p
        JOIN workflow_node_run n ON n.id=p.node_run_id
        WHERE p.requirement_id=#{id} AND p.plan_revision=#{revision} AND EXISTS(
          SELECT 1 FROM workflow_plan_candidate c JOIN workflow_node_attempt a ON a.id=c.attempt_id
          WHERE c.requirement_id=#{id} AND a.node_run_id=n.id AND c.state='APPLIED' AND c.applied_revision<=#{revision}
          AND a.adapter_key IN ('system.source.design-plan.v1','system.source.test-plan.v1','system.document.review-plan.v1','system.history.plan.v1','system.snapshot.plan.v1'))
        ORDER BY n.node_key LIMIT 256
        """) List<AppliedPlanner> appliedPlanners(String id, int revision);
    @Insert("""
        INSERT INTO workflow_template_plan_source(template_id,requirement_id,plan_revision,plan_sha256,template_sha256,mode,created_at)
        VALUES(#{templateId},#{requirementId},#{planRevision},#{planSha256},#{templateSha256},#{mode},#{createdAt})
        """) int insert(Source value);
    @Select("SELECT * FROM workflow_template_plan_source WHERE template_id=#{id}") Optional<Source> source(String id);
}
