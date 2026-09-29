package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowSnapshotWorkMapper {
    record Input(String attemptId,String sourceAttemptId,String sourceSha256,String inputJson,String sha256,String createdAt){ }
    record Page(String attemptId,String sha256,int startOffset,int endOffset,int totalLength,String createdAt){ }
    record Receipt(String attemptId,String version,String path,String blob,int startLine,int endLine,String content,String createdAt){ }
    @Insert("INSERT INTO workflow_snapshot_work_input VALUES(#{attemptId},#{sourceAttemptId},#{sourceSha256},#{inputJson},#{sha256},#{createdAt})") int insert(Input input);
    @Select("SELECT * FROM workflow_snapshot_work_input WHERE attempt_id=#{attempt}") Optional<Input> find(String attempt);
    @Insert("INSERT OR IGNORE INTO workflow_snapshot_work_page VALUES(#{attemptId},#{sha256},#{startOffset},#{endOffset},#{totalLength},#{createdAt})") int page(Page page);
    @Select("SELECT * FROM workflow_snapshot_work_page WHERE attempt_id=#{attempt} ORDER BY start_offset,end_offset") List<Page> pages(String attempt);
    @Insert("INSERT OR IGNORE INTO workflow_snapshot_work_context VALUES(#{attempt},#{sha},#{created})") int context(String attempt,String sha,String created);
    @Insert("INSERT OR IGNORE INTO workflow_snapshot_work_receipt VALUES(#{attemptId},#{version},#{path},#{blob},#{startLine},#{endLine},#{content},#{createdAt})") int receipt(Receipt receipt);
    @Select("SELECT * FROM workflow_snapshot_work_receipt WHERE attempt_id=#{attempt} AND version=#{version} AND path=#{path} AND blob=#{blob} AND start_line=#{start} AND end_line=#{end}")
    Optional<Receipt> receiptAt(String attempt,String version,String path,String blob,int start,int end);
}
