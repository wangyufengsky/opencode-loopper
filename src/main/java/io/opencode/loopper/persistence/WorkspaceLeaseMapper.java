package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Canonical writer ownership and legacy FIFO rows, shared with workflow node queues. */
public interface WorkspaceLeaseMapper extends WorkflowWriterMapper, WorkflowWritebackQueueMapper {
    @Select("SELECT * FROM workspace_lease WHERE canonical_root=#{canonicalRoot}")
    Optional<WorkspaceLeaseRow> findWorkspaceLease(String canonicalRoot);
    @Select("SELECT * FROM workspace_lease WHERE state IN ('HELD','RELEASE_PENDING') ORDER BY heartbeat_at")
    List<WorkspaceLeaseRow> blockingWorkspaceLeases();
    @Select("""
            SELECT lease.* FROM workspace_lease lease LEFT JOIN task holder ON holder.id=lease.holder_task_id
            JOIN task requester ON requester.id=#{taskId}
            WHERE lease.state IN ('HELD','RELEASE_PENDING')
              AND (holder.execution_mode != 'TEMPLATE_REPORT' OR lease.holder_workflow_attempt_id IS NOT NULL OR lease.holder_writeback_id IS NOT NULL)
              AND requester.execution_mode != 'TEMPLATE_REPORT'
            """)
    List<WorkspaceLeaseRow> blockingSourceWorkspaceLeases(String taskId);
    @Select("SELECT * FROM workspace_lease WHERE holder_task_id=#{taskId} AND state IN ('HELD','RELEASE_PENDING') LIMIT 1")
    Optional<WorkspaceLeaseRow> findActiveWorkspaceLeaseByHolder(String taskId);
    @Select("""
            SELECT lease.* FROM workspace_lease lease
            WHERE lease.state IN ('HELD','RELEASE_PENDING')
              AND EXISTS (
                SELECT 1 FROM task_queue queued
                WHERE queued.canonical_root=lease.canonical_root AND queued.state='QUEUED'
                UNION ALL SELECT 1 FROM workflow_writer_queue queued
                WHERE queued.canonical_root=lease.canonical_root AND queued.state='QUEUED'
                UNION ALL SELECT 1 FROM workflow_writeback_queue queued
                WHERE queued.canonical_root=lease.canonical_root AND queued.state='QUEUED'
              )
            ORDER BY lease.heartbeat_at
            """)
    List<WorkspaceLeaseRow> blockingWorkspaceLeasesWithQueuedWaiter();
    @Insert("""
            INSERT INTO workspace_lease(canonical_root,root_fingerprint,mode,holder_task_id,writer_session_id,state,
              acquired_at,heartbeat_at,released_at,release_reason,version,holder_workflow_attempt_id,holder_writeback_id)
            VALUES(#{canonicalRoot},#{rootFingerprint},#{mode},#{holderTaskId},#{writerSessionId},#{state},
              #{acquiredAt},#{heartbeatAt},#{releasedAt},#{releaseReason},#{version},#{holderWorkflowAttemptId},#{holderWritebackId})
            """)
    int insertWorkspaceLease(WorkspaceLeaseRow row);
    @Update("""
            UPDATE workspace_lease SET root_fingerprint=#{rootFingerprint},mode=#{mode},holder_task_id=#{holderTaskId},
              writer_session_id=#{writerSessionId},state=#{state},acquired_at=#{acquiredAt},heartbeat_at=#{heartbeatAt},
              released_at=#{releasedAt},release_reason=#{releaseReason},holder_workflow_attempt_id=#{holderWorkflowAttemptId},holder_writeback_id=#{holderWritebackId},version=version+1
            WHERE canonical_root=#{canonicalRoot} AND version=#{version}
            """)
    int updateWorkspaceLease(WorkspaceLeaseRow row);
    @Update("""
            UPDATE workspace_lease SET root_fingerprint=#{rootFingerprint},mode=#{mode},holder_task_id=#{holderTaskId},
              writer_session_id=#{writerSessionId},acquired_at=#{acquiredAt},heartbeat_at=#{heartbeatAt},
              released_at=#{releasedAt},release_reason=#{releaseReason},holder_workflow_attempt_id=#{holderWorkflowAttemptId},holder_writeback_id=#{holderWritebackId},version=version+1
            WHERE canonical_root=#{canonicalRoot} AND version=#{version}
            """)
    int updateWorkspaceLeaseDetails(WorkspaceLeaseRow row);

    @Select("""
            SELECT COALESCE(MAX(position),0)+1 FROM (
              SELECT position FROM task_queue WHERE canonical_root=#{canonicalRoot}
              UNION ALL SELECT position FROM workflow_writer_queue WHERE canonical_root=#{canonicalRoot}
              UNION ALL SELECT position FROM workflow_writeback_queue WHERE canonical_root=#{canonicalRoot})
            """)
    long nextQueuePosition(String canonicalRoot);
    @Insert("""
            INSERT INTO task_queue(task_id,canonical_root,root_fingerprint,position,source,state,enqueued_at,
              admitted_at,finished_at,version)
            VALUES(#{taskId},#{canonicalRoot},#{rootFingerprint},#{position},#{source},#{state},#{enqueuedAt},
              #{admittedAt},#{finishedAt},#{version})
            """)
    int insertTaskQueue(TaskQueueRow row);
    @Select("SELECT * FROM task_queue WHERE task_id=#{taskId}") Optional<TaskQueueRow> findTaskQueue(String taskId);
    @Select("SELECT * FROM task_queue WHERE canonical_root=#{canonicalRoot} ORDER BY position")
    List<TaskQueueRow> listTaskQueue(String canonicalRoot);
    @Select("SELECT * FROM task_queue WHERE canonical_root=#{canonicalRoot} AND state='QUEUED' ORDER BY position LIMIT 1")
    Optional<TaskQueueRow> nextQueuedTask(String canonicalRoot);
    @Update("""
            UPDATE task_queue SET state=#{state},admitted_at=#{admittedAt},finished_at=#{finishedAt},version=version+1
            WHERE task_id=#{taskId} AND version=#{version}
            """)
    int updateTaskQueue(TaskQueueRow row);
    @Update("""
            UPDATE task_queue SET root_fingerprint=#{rootFingerprint},position=#{position},source=#{source},state=#{state},
              enqueued_at=#{enqueuedAt},admitted_at=#{admittedAt},finished_at=#{finishedAt},version=version+1
            WHERE task_id=#{taskId} AND version=#{version}
            """)
    int requeueTask(TaskQueueRow row);

}
