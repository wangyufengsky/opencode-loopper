package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Automation rule/run persistence; lifecycle mutations remain separate from details. */
public interface AutomationMapper {
    @Insert("INSERT INTO automation_rule(id,name,project_id,template_version_id,trigger_type,state,approval_mode,trigger_config_json,webhook_token_hash,last_observed_head,created_at,updated_at,version) VALUES(#{id},#{name},#{projectId},#{templateVersionId},#{triggerType},#{state},#{approvalMode},#{triggerConfigJson},#{webhookTokenHash},#{lastObservedHead},#{createdAt},#{updatedAt},#{version})")
    int insertAutomationRule(AutomationRuleRow row);
    @Select("SELECT * FROM automation_rule WHERE id=#{id}") Optional<AutomationRuleRow> findAutomationRule(String id);
    @Select("SELECT * FROM automation_rule ORDER BY updated_at DESC") List<AutomationRuleRow> listAutomationRules();
    @Select("SELECT * FROM automation_rule WHERE state='ENABLED' ORDER BY updated_at") List<AutomationRuleRow> enabledAutomationRules();
    @Update("UPDATE automation_rule SET name=#{name},template_version_id=#{templateVersionId},trigger_type=#{triggerType},state=#{state},approval_mode=#{approvalMode},trigger_config_json=#{triggerConfigJson},webhook_token_hash=#{webhookTokenHash},last_observed_head=#{lastObservedHead},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateAutomationRule(AutomationRuleRow row);
    @Update("UPDATE automation_rule SET name=#{name},template_version_id=#{templateVersionId},trigger_type=#{triggerType},approval_mode=#{approvalMode},trigger_config_json=#{triggerConfigJson},webhook_token_hash=#{webhookTokenHash},last_observed_head=#{lastObservedHead},updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateAutomationRuleDetails(AutomationRuleRow row);
    @Insert("INSERT INTO automation_run(id,rule_id,trigger_type,idempotency_key,state,draft_id,task_id,evidence_json,detected_at,started_at,ended_at,version) VALUES(#{id},#{ruleId},#{triggerType},#{idempotencyKey},#{state},#{draftId},#{taskId},#{evidenceJson},#{detectedAt},#{startedAt},#{endedAt},#{version})")
    int insertAutomationRun(AutomationRunRow row);
    @Select("SELECT * FROM automation_run WHERE id=#{id}") Optional<AutomationRunRow> findAutomationRun(String id);
    @Select("SELECT * FROM automation_run WHERE rule_id=#{ruleId} ORDER BY detected_at DESC") List<AutomationRunRow> listAutomationRuns(String ruleId);
    @Update("UPDATE automation_run SET state=#{state},draft_id=#{draftId},task_id=#{taskId},evidence_json=#{evidenceJson},started_at=#{startedAt},ended_at=#{endedAt},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateAutomationRun(AutomationRunRow row);

    @Select("SELECT * FROM automation_run WHERE idempotency_key=#{key}")
    Optional<AutomationRunRow> findAutomationRunByKey(String key);

    @Select("""
            SELECT * FROM automation_run WHERE rule_id=#{ruleId} AND task_id IS NOT NULL
              AND state IN ('DETECTED','REVIEW_REQUIRED','QUEUED','RUNNING') ORDER BY detected_at,id
            """)
    List<AutomationRunRow> automationRunsForReconciliation(String ruleId);
}
