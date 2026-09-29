package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowDocumentReadMapper {
    record Read(String attemptId,String inputName,String path,String contentSha,int startLine,int endLine,int totalLines,String content,String createdAt){ }
    record Input(String attemptId,String inputName,String sha256,int startOffset,int endOffset,int totalLength,String createdAt){ }
    @Insert("""
        INSERT OR IGNORE INTO workflow_document_read(attempt_id,input_name,path,content_sha,start_line,end_line,total_lines,content,created_at)
        VALUES(#{attemptId},#{inputName},#{path},#{contentSha},#{startLine},#{endLine},#{totalLines},#{content},#{createdAt})
        """) int read(Read row);
    @Select("SELECT * FROM workflow_document_read WHERE attempt_id=#{attempt} AND input_name=#{input} AND path=#{path} ORDER BY start_line,end_line")
    List<Read> reads(String attempt,String input,String path);
    @Select("""
        SELECT * FROM workflow_document_read WHERE attempt_id=#{attempt} AND input_name='code' AND path=#{path}
        AND start_line<=#{start} AND end_line>=#{end} ORDER BY start_line DESC LIMIT 1
        """) Optional<Read> evidence(String attempt,String path,int start,int end);
    @Insert("""
        INSERT OR IGNORE INTO workflow_document_input_read(attempt_id,input_name,sha256,start_offset,end_offset,total_length,created_at)
        VALUES(#{attemptId},#{inputName},#{sha256},#{startOffset},#{endOffset},#{totalLength},#{createdAt})
        """) int input(Input row);
    @Select("SELECT * FROM workflow_document_input_read WHERE attempt_id=#{attempt} AND input_name=#{input} ORDER BY start_offset,end_offset")
    List<Input> inputs(String attempt,String input);
}
