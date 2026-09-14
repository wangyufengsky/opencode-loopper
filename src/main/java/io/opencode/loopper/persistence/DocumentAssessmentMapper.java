package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentAssessmentMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_assessment_progress WHERE run_id=#{runId}")
    Optional<Progress> progress(String runId);
    @Insert("INSERT OR IGNORE INTO document_assessment_progress(run_id) VALUES(#{runId})")
    int createProgress(String runId);
    @Update("UPDATE document_assessment_progress SET round=round+1,version=version+1 WHERE run_id=#{runId} AND version=#{version}")
    int advanceRound(Progress row);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_assessment_batch WHERE run_id=#{runId} AND revision=#{revision} AND ordinal=#{ordinal}")
    Optional<Batch> batch(@Param("runId") String runId, @Param("revision") int revision, @Param("ordinal") int ordinal);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_assessment_batch WHERE run_id=#{runId} AND revision=#{revision}
          AND ordinal>#{after} ORDER BY ordinal LIMIT 100
        """)
    List<Batch> batches(@Param("runId") String runId, @Param("revision") int revision, @Param("after") int after);
    @Insert("""
        INSERT INTO document_assessment_batch(run_id,revision,ordinal,round,model_id,review_model_id,candidate_json,candidate_sha256,created_at)
        VALUES(#{runId},#{revision},#{ordinal},#{round},#{modelId},#{reviewModelId},#{candidateJson},#{candidateSha256},#{createdAt})
        """)
    int insertBatch(Batch row);
    @Insert("""
        INSERT INTO document_requirement_assessment(run_id,revision,requirement_key,assessment_json,evidence_sha256)
        VALUES(#{runId},#{revision},#{key},#{body},#{hash})
        """)
    int insertItem(@Param("runId") String runId, @Param("revision") int revision, @Param("key") String key,
                   @Param("body") String body, @Param("hash") String hash);
    record Progress(String runId, int round, long version) { }
    record Batch(String runId, int revision, int ordinal, int round, String modelId, String reviewModelId,
                 String candidateJson, String candidateSha256, String createdAt) { }
}
