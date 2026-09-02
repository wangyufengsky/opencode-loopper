package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskJudgeApprovalMapper {
    record Approval(String cycleId, String taskId, String reviewBatchId, long taskVersion,
                    long cycleVersion, String approvedAt) { }

    @Select("SELECT * FROM task_judge_approval WHERE cycle_id=#{cycleId}")
    Optional<Approval> find(String cycleId);

    @Insert("""
            INSERT INTO task_judge_approval(cycle_id,task_id,review_batch_id,task_version,cycle_version,approved_at)
            VALUES(#{cycleId},#{taskId},#{reviewBatchId},#{taskVersion},#{cycleVersion},#{approvedAt})
            """)
    int insert(Approval approval);

    @Select("""
            SELECT a.task_id FROM task_judge_approval a JOIN task t ON t.id=a.task_id
            WHERE t.state='AWAITING_DECISION' AND EXISTS (
              SELECT 1 FROM workspace_lease l WHERE l.holder_task_id=t.id AND l.state<>'RELEASED')
            """)
    List<String> pendingHandoffs();

    @Select("""
            SELECT EXISTS(SELECT 1 FROM ai_candidate_internal_launch l WHERE l.task_id=#{taskId}
              AND (l.state NOT IN ('COMPLETED','FAILED_STOPPED','CANCELLED','STALE')
                OR (l.external_session_id IS NOT NULL AND (l.termination_proof IS NULL OR l.proof_at IS NULL))
                OR EXISTS (SELECT 1 FROM ai_candidate_internal_launch_cleanup_remote r
                  WHERE r.launch_id=l.id AND r.state<>'STOPPED')))
            """)
    boolean hasUnstoppedCandidates(String taskId);
}
