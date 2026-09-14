package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentDevelopmentMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_development_scope WHERE external_session_id=#{session}")
    Optional<Scope> scope(String session);
    @Insert("""
        INSERT OR IGNORE INTO document_development_scope(external_session_id,run_id,requirement_revision,
          manifest_sha256,owner_json,files_json,created_at)
        VALUES(#{externalSessionId},#{runId},#{requirementRevision},#{manifestSha256},#{ownerJson},#{filesJson},#{createdAt})
        """)
    int bind(Scope scope);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_development_design WHERE requirement_revision_id=#{revision}")
    Optional<Design> design(String revision);
    @Insert("""
        INSERT INTO document_development_design(requirement_revision_id,run_id,document_revision,manifest_sha256,created_at)
        VALUES(#{requirementRevisionId},#{runId},#{documentRevision},#{manifestSha256},#{createdAt})
        """)
    int insertDesign(Design design);
    @Update("""
        UPDATE document_template_run SET designer_id=#{designer},updated_at=#{now},version=version+1
        WHERE id=#{run} AND version=#{version} AND state='DESIGNING' AND designer_id IS NULL
        """)
    int linkDesigner(@Param("run") String run, @Param("version") long version,
                     @Param("designer") String designer, @Param("now") String now);
    @Update("""
        UPDATE document_template_run SET task_id=#{task},updated_at=#{now},version=version+1
        WHERE id=#{run} AND version=#{version} AND state='DESIGNING' AND task_id IS NULL
          AND designer_id=#{designer}
          AND EXISTS(SELECT 1 FROM designer_session d JOIN task t ON t.project_id=d.project_id
            WHERE d.id=#{designer} AND t.id=#{task} AND (t.loop_draft_id=d.loop_draft_id OR d.task_id=t.id))
        """)
    int linkTask(@Param("run") String run, @Param("version") long version, @Param("designer") String designer,
                 @Param("task") String task, @Param("now") String now);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_development_promotion WHERE run_id=#{run} ORDER BY created_at DESC,source_revision_id DESC LIMIT 1")
    Optional<Promotion> promotion(String run);
    @Insert("""
        INSERT INTO document_development_promotion(source_revision_id,run_id,designer_id,target_revision_id,profile_json,created_at)
        VALUES(#{sourceRevisionId},#{runId},#{designerId},#{targetRevisionId},#{profileJson},#{createdAt})
        """)
    int insertPromotion(Promotion promotion);
    record Promotion(String sourceRevisionId, String runId, String designerId, String targetRevisionId,
                     String profileJson, String createdAt) { }
    record Design(String requirementRevisionId, String runId, int documentRevision, String manifestSha256, String createdAt) { }
    record Scope(String externalSessionId, String runId, int requirementRevision, String manifestSha256,
                 String ownerJson, String filesJson, String createdAt) { }
}
