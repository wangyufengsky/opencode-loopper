package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentArtifactMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT id,name,kind,sha256,length(CAST(content AS BLOB)) AS bytes,created_at FROM document_template_artifact
        WHERE run_id=#{runId} AND requirement_revision=#{revision} AND name>#{after} ORDER BY name LIMIT #{limit}
        """)
    List<Summary> list(@Param("runId") String runId, @Param("revision") int revision,
                       @Param("after") String after, @Param("limit") int limit);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_artifact WHERE run_id=#{runId} AND id=#{id}")
    Optional<Artifact> find(@Param("runId") String runId, @Param("id") String id);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_artifact WHERE run_id=#{runId} AND requirement_revision=#{revision} AND name=#{name}")
    Optional<Artifact> named(@Param("runId") String runId, @Param("revision") int revision, @Param("name") String name);
    @Insert("""
        INSERT INTO document_template_artifact(id,run_id,requirement_revision,name,kind,content,sha256,created_at)
        VALUES(#{id},#{runId},#{requirementRevision},#{name},#{kind},#{content},#{sha256},#{createdAt})
        """)
    int insert(Artifact row);
    record Artifact(String id, String runId, int requirementRevision, String name, String kind, String content, String sha256, String createdAt) { }
    record Summary(String id, String name, String kind, String sha256, long bytes, String createdAt) { }
}
