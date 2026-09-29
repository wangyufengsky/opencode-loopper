package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface WorkflowWritebackQueueMapper {
    record Row(String writebackId,String projectId,String canonicalRoot,String rootFingerprint,long position,
               String state,String enqueuedAt,String admittedAt,String finishedAt,long version) { }
    @Select("SELECT * FROM workflow_writeback_queue WHERE writeback_id=#{id}") Optional<Row> findWritebackQueue(String id);
    @Select("SELECT * FROM workflow_writeback_queue WHERE canonical_root=#{root} AND state='QUEUED' ORDER BY position LIMIT 1")
    Optional<Row> nextWriteback(String root);
    @Select("SELECT count(*)>0 FROM workflow_writeback WHERE id=#{id} AND state='CONFIRMED'") boolean writebackReady(String id);
    @Insert("""
        INSERT INTO workflow_writeback_queue(writeback_id,project_id,canonical_root,root_fingerprint,position,state,enqueued_at,admitted_at,finished_at,version)
        VALUES(#{writebackId},#{projectId},#{canonicalRoot},#{rootFingerprint},#{position},#{state},#{enqueuedAt},#{admittedAt},#{finishedAt},#{version})
        """) int insertWritebackQueue(Row row);
    @Update("""
        UPDATE workflow_writeback_queue SET state=#{state},admitted_at=#{admitted},finished_at=#{finished},version=version+1
        WHERE writeback_id=#{id} AND version=#{version} AND state=#{previous}
        """) int transitionWritebackQueue(String id,long version,String previous,String state,String admitted,String finished);
}
