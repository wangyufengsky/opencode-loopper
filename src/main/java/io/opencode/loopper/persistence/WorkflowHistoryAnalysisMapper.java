package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowHistoryAnalysisMapper {
    record Input(String attemptId,String sourceAttemptId,String sourceSha256,String inputJson,String sha256,String createdAt){ }
    record Read(String attemptId,String sha256,int startOffset,int endOffset,int totalLength,String createdAt){ }
    @Insert("INSERT INTO workflow_history_analysis_input VALUES(#{attemptId},#{sourceAttemptId},#{sourceSha256},#{inputJson},#{sha256},#{createdAt})")
    int insert(Input input);
    @Select("SELECT * FROM workflow_history_analysis_input WHERE attempt_id=#{attempt}") Optional<Input> find(String attempt);
    @Insert("INSERT OR IGNORE INTO workflow_history_analysis_read VALUES(#{attemptId},#{sha256},#{startOffset},#{endOffset},#{totalLength},#{createdAt})")
    int read(Read read);
    @Select("SELECT * FROM workflow_history_analysis_read WHERE attempt_id=#{attempt} ORDER BY start_offset,end_offset") List<Read> reads(String attempt);
}
