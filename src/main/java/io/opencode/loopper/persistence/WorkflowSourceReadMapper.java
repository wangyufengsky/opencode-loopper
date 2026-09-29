package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowSourceReadMapper {
    record SourceRead(String attemptId,String inputName,String path,String sha256,int startLine,int endLine,int totalLines,String content,String createdAt){ }
    record InputRead(String attemptId,String inputName,String sha256,int startOffset,int endOffset,int totalLength,String createdAt){ }
    @Insert("""
        INSERT OR IGNORE INTO workflow_source_read(attempt_id,input_name,path,sha256,start_line,end_line,total_lines,content,created_at)
        VALUES(#{attemptId},#{inputName},#{path},#{sha256},#{startLine},#{endLine},#{totalLines},#{content},#{createdAt})
        """) int source(SourceRead row);
    @Select("SELECT * FROM workflow_source_read WHERE attempt_id=#{attempt} AND input_name=#{input} AND path=#{path} ORDER BY start_line,end_line")
    List<SourceRead> sources(String attempt,String input,String path);
    @Insert("""
        INSERT OR IGNORE INTO workflow_design_input_read(attempt_id,input_name,sha256,start_offset,end_offset,total_length,created_at)
        VALUES(#{attemptId},#{inputName},#{sha256},#{startOffset},#{endOffset},#{totalLength},#{createdAt})
        """) int input(InputRead row);
    @Select("SELECT * FROM workflow_design_input_read WHERE attempt_id=#{attempt} AND input_name=#{input} ORDER BY start_offset,end_offset")
    List<InputRead> inputs(String attempt,String input);
}
