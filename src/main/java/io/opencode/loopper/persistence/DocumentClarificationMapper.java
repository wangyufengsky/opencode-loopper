package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentClarificationMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_requirement_clarification WHERE run_id=#{run} ORDER BY revision DESC LIMIT 1")
    Optional<Revision> latest(String run);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_requirement_clarification WHERE run_id=#{run} AND request_key=#{key}")
    Optional<Revision> request(@Param("run") String run, @Param("key") String key);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_requirement_clarification WHERE run_id=#{run} AND revision=#{revision}")
    Optional<Revision> revision(@Param("run") String run, @Param("revision") int revision);
    @Insert("""
        INSERT INTO document_requirement_clarification(run_id,revision,previous_revision,request_key,request_sha256,answers_json,created_at)
        VALUES(#{runId},#{revision},#{previousRevision},#{requestKey},#{requestSha256},#{answersJson},#{createdAt})
        """)
    int insert(Revision revision);
    record Revision(String runId, int revision, int previousRevision, String requestKey,
                    String requestSha256, String answersJson, String createdAt) { }
}
