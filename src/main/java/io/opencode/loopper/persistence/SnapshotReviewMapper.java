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
    record AcceptedBatch(String id, String purpose, String title) { }
    @Select("SELECT id,purpose,json_extract(input_json,'$.snapshot.objective') AS title FROM template_task_batch WHERE task_id=#{taskId} AND state='VALIDATED' AND purpose LIKE 'SNAPSHOT_%' ORDER BY created_at,id LIMIT #{limit} OFFSET #{offset}")
    List<AcceptedBatch> accepted(String taskId, int offset, int limit);
    @Delete("DELETE FROM snapshot_review_read WHERE task_id=#{taskId}") int deleteReads(String taskId);
    @Delete("DELETE FROM snapshot_review_file WHERE task_id=#{taskId}") int deleteFiles(String taskId);
    @Delete("DELETE FROM snapshot_review_plan_revision WHERE task_id=#{taskId}") int deletePlans(String taskId);
    @Delete("DELETE FROM snapshot_review_run WHERE task_id=#{taskId}") int deleteRun(String taskId);
}
