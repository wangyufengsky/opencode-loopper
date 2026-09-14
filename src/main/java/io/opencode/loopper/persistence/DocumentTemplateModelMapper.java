package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentTemplateModelMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_model_run WHERE id=#{id}")
    Optional<DocumentTemplateModelRow> find(String id);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_template_model_run WHERE run_id=#{runId} AND candidate_kind=#{kind}
          AND ordinal=#{ordinal} ORDER BY generation DESC,attempt DESC LIMIT 1
        """)
    Optional<DocumentTemplateModelRow> latest(@Param("runId") String runId, @Param("kind") String kind,
                                            @Param("ordinal") int ordinal);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_template_model_run WHERE run_id=#{runId} AND candidate_kind=#{kind}
          AND ordinal=#{ordinal} AND generation=#{generation} ORDER BY attempt DESC LIMIT 1
        """)
    Optional<DocumentTemplateModelRow> exact(@Param("runId") String runId, @Param("kind") String kind,
            @Param("ordinal") int ordinal, @Param("generation") int generation);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT id,ordinal,state,output_sha256 AS sha256,coalesce(json_array_length(output_json,'$.items'),json_array_length(output_json,'$.entries')) AS items,
          json_array_length(output_json,'$.findings') AS findings FROM document_template_model_run
        WHERE run_id=#{runId} AND candidate_kind IN ('REQUIREMENT_CODE_ASSESSMENT_V1','DOCUMENT_CODE_ASSESSMENT_V2') AND generation=#{generation}
          AND ordinal>#{after} AND attempt=(SELECT max(latest.attempt) FROM document_template_model_run latest
            WHERE latest.run_id=document_template_model_run.run_id AND latest.candidate_kind=document_template_model_run.candidate_kind
              AND latest.ordinal=document_template_model_run.ordinal AND latest.generation=document_template_model_run.generation)
          ORDER BY ordinal LIMIT #{limit}
        """)
    List<AssessmentSummary> assessmentSummaries(@Param("runId") String runId, @Param("generation") int generation,
            @Param("after") int after, @Param("limit") int limit);
    record AssessmentSummary(String id, int ordinal, String state, String sha256, Integer items, Integer findings) { }
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_template_model_run WHERE run_id=#{runId} AND candidate_kind=#{kind}
          AND generation=#{generation} ORDER BY ordinal LIMIT 100
        """)
    List<DocumentTemplateModelRow> batches(@Param("runId") String runId, @Param("kind") String kind,
                                          @Param("generation") int generation);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_template_model_run WHERE run_id=#{runId}
          AND state NOT IN ('VALIDATED','STOPPED','FAILED') ORDER BY created_at,id LIMIT 100
        """)
    List<DocumentTemplateModelRow> active(String runId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_template_model_run model WHERE run_id=#{runId} AND state IN ('STOPPED','FAILED')
          AND attempt=(SELECT max(other.attempt) FROM document_template_model_run other WHERE other.run_id=model.run_id
            AND other.candidate_kind=model.candidate_kind AND other.ordinal=model.ordinal AND other.generation=model.generation)
          AND generation=(SELECT max(other.generation) FROM document_template_model_run other WHERE other.run_id=model.run_id
            AND other.candidate_kind=model.candidate_kind AND other.ordinal=model.ordinal)
        ORDER BY created_at,id LIMIT 100
        """)
    List<DocumentTemplateModelRow> stoppedLatest(String runId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT CAST(coalesce(sum(max(0,(julianday(CASE WHEN state IN ('VALIDATED','STOPPED','FAILED')
          THEN updated_at ELSE #{now} END)-julianday(created_at))*86400000)),0) AS INTEGER)
        FROM document_template_model_run WHERE run_id=#{runId}
        """)
    long elapsedMillis(@Param("runId") String runId, @Param("now") String now);
    @Insert("""
        INSERT INTO document_template_model_run(id,run_id,candidate_kind,ordinal,generation,state,
            input_json,input_sha256,created_at,updated_at,attempt)
        VALUES(#{id},#{runId},#{candidateKind},#{ordinal},#{generation},#{state},
            #{inputJson},#{inputSha256},#{createdAt},#{updatedAt},#{attempt})
        """)
    int insert(DocumentTemplateModelRow row);
    @Update("""
        UPDATE document_template_model_run SET state=#{state},updated_at=#{now},version=version+1,
            error_code=#{code} WHERE id=#{id} AND version=#{version}
        """)
    int transition(@Param("id") String id, @Param("version") long version, @Param("state") String state,
                   @Param("code") String code, @Param("now") String now);
    @Update("""
        UPDATE document_template_model_run SET creation_plan_json=#{plan},prompt_json=#{prompt},
          prompt_sha256=#{hash},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND creation_plan_json IS NULL
        """)
    int prepare(@Param("id") String id, @Param("version") long version, @Param("plan") String plan,
                @Param("prompt") String prompt, @Param("hash") String hash, @Param("now") String now);
    @Update("""
        UPDATE document_template_model_run SET external_session_id=#{session},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND external_session_id IS NULL
        """)
    int attach(@Param("id") String id, @Param("version") long version,
               @Param("session") String session, @Param("now") String now);
    @Update("""
        UPDATE document_template_model_run SET output_json=#{output},output_sha256=#{hash},updated_at=#{now}
        WHERE id=#{id} AND output_json IS NULL AND state IN ('DISPATCHING','RUNNING')
        """)
    int accept(@Param("id") String id, @Param("output") String output,
               @Param("hash") String hash, @Param("now") String now);
}
