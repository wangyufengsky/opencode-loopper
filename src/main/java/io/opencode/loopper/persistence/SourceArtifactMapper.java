package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceArtifactMapper {
    @Select("SELECT * FROM source_template_artifact WHERE run_id=#{run} ORDER BY name")
    List<Artifact> all(String run);
    @Select("SELECT * FROM source_template_artifact WHERE run_id=#{run} AND id=#{id}")
    Optional<Artifact> find(String run, String id);
    @Select("SELECT * FROM source_template_artifact WHERE run_id=#{run} AND name=#{name}")
    Optional<Artifact> named(String run, String name);
    @Select("""
        SELECT id,name,kind,sha256,length(CAST(content AS BLOB)) AS size_bytes FROM source_template_artifact
        WHERE run_id=#{run} AND name>#{after} ORDER BY name LIMIT #{limit}
        """)
    List<Metadata> list(String run, String after, int limit);
    @Insert("""
        INSERT INTO source_template_artifact(id,run_id,name,kind,content,sha256,created_at)
        VALUES(#{id},#{runId},#{name},#{kind},#{content},#{sha256},#{createdAt})
        """)
    int insert(Artifact value);
    record Artifact(String id, String runId, String name, String kind, String content, String sha256, String createdAt) { }
    record Metadata(String id, String name, String kind, String sha256, long sizeBytes) { }
}
