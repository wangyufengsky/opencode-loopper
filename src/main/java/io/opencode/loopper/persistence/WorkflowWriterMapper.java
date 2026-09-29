package io.opencode.loopper.persistence;

import java.util.Optional;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface WorkflowWriterMapper {
    @Select("""
        SELECT lease.* FROM workspace_lease lease LEFT JOIN task t ON t.id=lease.holder_task_id
        WHERE lease.state IN ('HELD','RELEASE_PENDING') AND COALESCE(t.execution_mode,'')<>'TEMPLATE_REPORT'
        """)
    List<WorkspaceLeaseRow> blockingSourceWriters();
    @Select("""
        SELECT count(*) FROM (
          SELECT position FROM task_queue WHERE canonical_root=#{root} AND state='QUEUED' AND position<=#{position}
          UNION ALL SELECT position FROM workflow_writer_queue WHERE canonical_root=#{root} AND state='QUEUED' AND position<=#{position}
          UNION ALL SELECT position FROM workflow_writeback_queue WHERE canonical_root=#{root} AND state='QUEUED' AND position<=#{position})
        """)
    long writerQueuePosition(String root,long position);
    @Select("SELECT * FROM workflow_writer_queue WHERE attempt_id=#{id}")
    Optional<WorkflowWriterQueueRow> findWorkflowWriter(String id);
    @Select("SELECT * FROM workflow_writer_queue WHERE canonical_root=#{root} AND state='QUEUED' ORDER BY position LIMIT 1")
    Optional<WorkflowWriterQueueRow> nextWorkflowWriter(String root);
    @Select("""
        SELECT count(*)>0 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
        JOIN workflow_requirement r ON r.id=n.requirement_id JOIN workflow_plan_node p ON p.requirement_id=r.id
          AND p.plan_revision=r.head_revision AND p.node_run_id=n.id
        WHERE a.id=#{id} AND a.state='PREPARING' AND a.adapter_key='model.write.v1'
          AND n.state='ACTIVE' AND n.latest_attempt_id=a.id AND r.state IN ('RUNNING','PAUSED','STALLED')
          AND NOT EXISTS(SELECT 1 FROM workflow_attempt_stop s WHERE s.attempt_id=a.id)
        """)
    boolean workflowWriterReady(String id);
    @Insert("""
        INSERT INTO workflow_writer_queue(attempt_id,requirement_id,project_id,canonical_root,root_fingerprint,position,
          state,enqueued_at,admitted_at,finished_at,version)
        VALUES(#{attemptId},#{requirementId},#{projectId},#{canonicalRoot},#{rootFingerprint},#{position},
          #{state},#{enqueuedAt},#{admittedAt},#{finishedAt},#{version})
        """)
    int insertWorkflowWriter(WorkflowWriterQueueRow row);
    @Update("""
        UPDATE workflow_writer_queue SET state=#{state},admitted_at=#{admitted},finished_at=#{finished},version=version+1
        WHERE attempt_id=#{id} AND version=#{version} AND state=#{previous}
        """)
    int transitionWorkflowWriter(String id,long version,String previous,String state,String admitted,String finished);
}
