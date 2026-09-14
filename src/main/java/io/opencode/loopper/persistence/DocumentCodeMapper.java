package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentCodeMapper {
    @Insert("""
        INSERT INTO document_code_file(run_id,path,blob_sha,mode,size_bytes,limitation)
        VALUES(#{runId},#{path},#{blobSha},#{mode},#{sizeBytes},#{limitation})
        """)
    int insertFile(File row);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_code_file WHERE run_id=#{runId} AND path=#{path}")
    Optional<File> file(@Param("runId") String runId, @Param("path") String path);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_code_file WHERE run_id=#{runId} AND path>#{after}
          AND instr(lower(path),lower(#{query}))>0 ORDER BY path LIMIT #{limit}
        """)
    List<File> files(@Param("runId") String runId, @Param("after") String after,
                     @Param("query") String query, @Param("limit") int limit);
    @Insert("""
        INSERT OR IGNORE INTO document_code_read(model_id,path,blob_sha,start_line,end_line,content,sha256,created_at)
        VALUES(#{modelId},#{path},#{blobSha},#{startLine},#{endLine},#{content},#{sha256},#{createdAt})
        """)
    int insertRead(Read row);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_code_read WHERE model_id=#{modelId} AND path=#{path}
          AND start_line<=#{start} AND end_line>=#{end} ORDER BY start_line DESC LIMIT 1
        """)
    Optional<Read> evidence(@Param("modelId") String modelId, @Param("path") String path,
                            @Param("start") int start, @Param("end") int end);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT count(*) FROM document_code_read WHERE model_id=#{modelId}")
    int readCount(String modelId);
    record File(String runId, String path, String blobSha, String mode, long sizeBytes, String limitation) { }
    record Read(String modelId, String path, String blobSha, int startLine, int endLine,
                String content, String sha256, String createdAt) { }
}
