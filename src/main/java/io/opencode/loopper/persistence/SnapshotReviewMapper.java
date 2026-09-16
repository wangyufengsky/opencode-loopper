package io.opencode.loopper.persistence;

import io.opencode.loopper.template.SnapshotReview;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SnapshotReviewMapper {
    record Run(String taskId, String mode, String sourceSha, String snapshotJson, String snapshotSha256,
               String planJson, int planRevision, long version) { }
    @Insert("INSERT INTO snapshot_review_run(task_id, mode) VALUES(#{taskId}, #{mode})")
    int insert(String taskId, String mode);
    @Select("SELECT * FROM snapshot_review_run WHERE task_id=#{taskId}") Optional<Run> find(String taskId);
    @Update("UPDATE snapshot_review_run SET source_sha=#{sha},version=version+1 WHERE task_id=#{taskId} AND source_sha IS NULL AND version=#{version}")
    int source(String taskId, long version, String sha);
    @Update("UPDATE snapshot_review_run SET snapshot_json=#{snapshot},snapshot_sha256=#{hash},version=version+1 WHERE task_id=#{taskId} AND snapshot_json IS NULL AND version=#{version}")
    int snapshot(String taskId, long version, String snapshot, String hash);
    @Update("UPDATE snapshot_review_run SET plan_json=#{plan},plan_revision=plan_revision+1,version=version+1 WHERE task_id=#{taskId} AND version=#{version}")
    int plan(String taskId, long version, String plan);
    @Insert("INSERT INTO snapshot_review_plan_revision(task_id,revision,plan_json,reason) VALUES(#{taskId},#{revision},#{plan},#{reason})")
    int revision(String taskId, int revision, String plan, String reason);
    @Insert("INSERT INTO snapshot_review_file(task_id,source_version,path,blob,mode,bytes,limitation) VALUES(#{taskId},#{file.version},#{file.path},#{file.blob},#{file.mode},#{file.bytes},#{file.limitation})")
    int file(String taskId, SnapshotReview.File file);
    @Select("SELECT source_version AS version,path,blob,mode,bytes,limitation FROM snapshot_review_file WHERE task_id=#{taskId} AND source_version=#{version} AND path=#{path}")
    Optional<SnapshotReview.File> fileAt(String taskId, String version, String path);
    @Select("SELECT source_version AS version,path,blob,mode,bytes,limitation FROM snapshot_review_file WHERE task_id=#{taskId} AND source_version=#{version} AND path > #{after} ORDER BY path LIMIT #{limit}")
    List<SnapshotReview.File> files(String taskId, String version, String after, int limit);
    @Insert("INSERT OR IGNORE INTO snapshot_review_read(batch_id,task_id,source_version,path,blob,start_line,end_line,content) VALUES(#{batchId},#{taskId},#{ref.version},#{ref.path},#{ref.blob},#{ref.startLine},#{ref.endLine},#{content})")
    int receipt(String batchId, String taskId, SnapshotReview.Reference ref, String content);
    @Select("SELECT content FROM snapshot_review_read WHERE batch_id=#{batchId} AND source_version=#{ref.version} AND path=#{ref.path} AND blob=#{ref.blob} AND start_line=#{ref.startLine} AND end_line=#{ref.endLine}")
    Optional<String> receiptContent(String batchId, SnapshotReview.Reference ref);
    @Select("SELECT COUNT(*) FROM snapshot_review_read WHERE batch_id=#{batchId}") int readCount(String batchId);
    @Insert("INSERT OR IGNORE INTO snapshot_review_context_request(batch_id,request_sha256) SELECT #{batchId},#{digest} WHERE (SELECT COUNT(*) FROM snapshot_review_context_request WHERE batch_id=#{batchId}) < 12")
    int contextRequest(String batchId, String digest);
    @Select("SELECT COUNT(*) FROM snapshot_review_context_request WHERE batch_id=#{batchId} AND request_sha256=#{digest}")
    int contextRequestExists(String batchId, String digest);
    @Delete("DELETE FROM snapshot_review_context_request WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{taskId})")
    int deleteContextRequests(String taskId);
    @Select("SELECT COALESCE(SUM(length(input_json)+COALESCE(length(output_json),0)),0) FROM template_task_batch WHERE task_id=#{taskId}")
    long reportSize(String taskId);
    @Select("""
            SELECT b.* FROM template_task_batch b JOIN attempt a ON a.id=b.attempt_id
            WHERE b.task_id=#{taskId} AND b.purpose LIKE 'SNAPSHOT_%'
            AND NOT EXISTS (SELECT 1 FROM attempt newer WHERE newer.stage_id=a.stage_id AND newer.ordinal>a.ordinal)
            AND NOT EXISTS (SELECT 1 FROM template_task_batch newer WHERE newer.attempt_id=b.attempt_id
                AND newer.purpose=b.purpose AND newer.ordinal=b.ordinal AND newer.generation>b.generation)
            ORDER BY b.created_at,b.id
            """)
    List<TemplateTaskBatchRow> currentBatches(String taskId);
    @Insert("INSERT OR IGNORE INTO snapshot_review_reusable(batch_id,fingerprint,output_sha256) VALUES(#{id},#{fingerprint},#{hash})")
    int reusable(String id, String fingerprint, String hash);
    record Reusable(String id, String taskId, String inputJson, String outputJson, String outputSha256) { }
    @Select("""
            SELECT b.id,b.task_id,b.input_json,b.output_json,c.output_sha256 FROM snapshot_review_reusable c
            JOIN template_task_batch b ON b.id=c.batch_id JOIN execution_session s ON s.id=b.session_id JOIN attempt a ON a.id=b.attempt_id
            WHERE c.fingerprint=#{fingerprint} AND b.task_id<>#{taskId} AND b.state='VALIDATED' AND s.state='COMPLETED'
            AND NOT EXISTS (SELECT 1 FROM attempt newer WHERE newer.stage_id=a.stage_id AND newer.ordinal>a.ordinal)
            ORDER BY b.updated_at DESC,b.id DESC LIMIT 1
            """)
    Optional<Reusable> reusableResult(String taskId, String fingerprint);
    @Insert("INSERT INTO snapshot_review_reuse(batch_id,source_batch_id,source_task_id,fingerprint,output_sha256) VALUES(#{id},#{sourceId},#{sourceTask},#{fingerprint},#{hash})")
    int reuse(String id, String sourceId, String sourceTask, String fingerprint, String hash);
    record Reuse(String batchId, String sourceBatchId, String sourceTaskId, String fingerprint, String outputSha256) { }
    @Select("SELECT * FROM snapshot_review_reuse WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{taskId})")
    List<Reuse> reuses(String taskId);
    @Delete("DELETE FROM snapshot_review_reusable WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{taskId})")
    int deleteReusable(String taskId);
    @Delete("DELETE FROM snapshot_review_reuse WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{taskId})")
    int deleteReuses(String taskId);
    record AcceptedBatch(String id, String purpose, String title) { }
    @Select("SELECT id,purpose,json_extract(input_json,'$.snapshot.objective') AS title FROM template_task_batch WHERE task_id=#{taskId} AND state='VALIDATED' AND purpose LIKE 'SNAPSHOT_%' ORDER BY created_at,id LIMIT #{limit} OFFSET #{offset}")
    List<AcceptedBatch> accepted(String taskId, int offset, int limit);
    @Delete("DELETE FROM snapshot_review_read WHERE task_id=#{taskId}") int deleteReads(String taskId);
    @Delete("DELETE FROM snapshot_review_file WHERE task_id=#{taskId}") int deleteFiles(String taskId);
    @Delete("DELETE FROM snapshot_review_plan_revision WHERE task_id=#{taskId}") int deletePlans(String taskId);
    @Delete("DELETE FROM snapshot_review_run WHERE task_id=#{taskId}") int deleteRun(String taskId);
}
