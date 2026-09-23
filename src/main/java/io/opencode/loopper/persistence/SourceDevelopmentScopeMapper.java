package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceDevelopmentScopeMapper {
    @Select("SELECT * FROM source_development_scope WHERE external_session_id=#{session}")
    Optional<Scope> scope(String session);
    @Insert("INSERT OR IGNORE INTO source_development_scope VALUES(#{externalSessionId},#{runId},#{ownerJson},#{manifestSha256},#{createdAt})")
    int bind(Scope scope);
    @Insert("""
        INSERT OR IGNORE INTO source_development_read VALUES(#{externalSessionId},#{path},#{sha256},#{startLine},#{endLine},#{totalLines},#{createdAt})
        """)
    int read(Read read);
    @Select("SELECT * FROM source_development_read WHERE external_session_id=#{session} AND path=#{path} ORDER BY start_line,end_line")
    List<Read> reads(String session, String path);
    @Select("""
        SELECT p.* FROM task_package_plan_revision p JOIN source_development_plan b ON b.plan_revision_id=p.id
        WHERE p.external_session_id=#{session}
        """)
    Optional<TaskPackagePlanRevisionRow> plan(String session);
    record Scope(String externalSessionId, String runId, String ownerJson, String manifestSha256, String createdAt) { }
    record Read(String externalSessionId, String path, String sha256, int startLine, int endLine, int totalLines, String createdAt) { }
}
