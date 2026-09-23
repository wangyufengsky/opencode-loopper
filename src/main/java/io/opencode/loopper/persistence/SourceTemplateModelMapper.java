package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceTemplateModelMapper {
    @Select("""
        SELECT m.candidate_kind,m.state,count(*) AS count FROM source_template_model m
        WHERE m.run_id=#{run} AND m.attempt=(SELECT max(n.attempt) FROM source_template_model n
          WHERE n.run_id=m.run_id AND n.candidate_kind=m.candidate_kind AND n.ordinal=m.ordinal AND n.generation=m.generation)
        GROUP BY m.candidate_kind,m.state
        """)
    List<Count> counts(String run);
    @Select("""
        SELECT m.id,m.candidate_kind,m.ordinal,m.generation,m.attempt,m.state,m.error_code,m.version,m.created_at,m.updated_at,
          CASE WHEN r.state='WAITING_INPUT' AND m.generation=p.generation AND m.state IN ('FAILED','STOPPED')
            AND ((r.resume_state='WRITING' AND m.candidate_kind='SOURCE_DETAILED_DESIGN_V1')
              OR (r.resume_state='REVIEWING' AND m.candidate_kind='SOURCE_DESIGN_REVIEW_V1'))
            AND (m.attempt+1<json_extract(r.contract_json,'$.maxStageAttempts') OR (m.state='STOPPED' AND m.output_json IS NOT NULL))
            THEN 1 ELSE 0 END AS retryable
        FROM source_template_model m JOIN source_template_run r ON r.id=m.run_id
          JOIN source_template_design_progress p ON p.run_id=r.id WHERE m.run_id=#{run}
          AND (m.created_at>#{time} OR (m.created_at=#{time} AND m.id>#{id}))
          AND m.attempt=(SELECT max(n.attempt) FROM source_template_model n WHERE n.run_id=m.run_id
            AND n.candidate_kind=m.candidate_kind AND n.ordinal=m.ordinal AND n.generation=m.generation)
        ORDER BY m.created_at,m.id LIMIT #{limit}
        """)
    List<Metadata> metadata(String run, String time, String id, int limit);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM source_template_model WHERE id=#{id}")
    Optional<SourceTemplateModelRow> find(String id);
    @Select("""
        SELECT * FROM source_template_model WHERE run_id=#{run} AND candidate_kind=#{kind}
          AND ordinal=#{ordinal} AND generation=#{generation} ORDER BY attempt DESC LIMIT 1
        """)
    Optional<SourceTemplateModelRow> exact(String run, String kind, int ordinal, int generation);
    @Select("""
        SELECT m.* FROM source_template_model m WHERE run_id=#{run} AND candidate_kind=#{kind} AND generation=#{generation}
          AND attempt=(SELECT max(n.attempt) FROM source_template_model n WHERE n.run_id=m.run_id
            AND n.candidate_kind=m.candidate_kind AND n.ordinal=m.ordinal AND n.generation=m.generation)
        ORDER BY ordinal
        """)
    List<SourceTemplateModelRow> current(String run, String kind, int generation);
    @Select("SELECT * FROM source_template_model WHERE run_id=#{run} AND state NOT IN ('VALIDATED','FAILED','STOPPED') ORDER BY created_at,id LIMIT 100")
    List<SourceTemplateModelRow> active(String run);
    @Insert("""
        INSERT INTO source_template_model(id,run_id,candidate_kind,ordinal,generation,attempt,state,input_json,input_sha256,created_at,updated_at)
        VALUES(#{id},#{runId},#{candidateKind},#{ordinal},#{generation},#{attempt},#{state},#{inputJson},#{inputSha256},#{createdAt},#{updatedAt})
        """)
    int insert(SourceTemplateModelRow row);
    @Update("""
        UPDATE source_template_model SET state=#{state},error_code=#{code},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version}
        """)
    int transition(String id, long version, String state, String code, String now);
    @Update("""
        UPDATE source_template_model SET creation_plan_json=#{plan},prompt_json=#{prompt},prompt_sha256=#{hash},started_at=#{now},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND creation_plan_json IS NULL
        """)
    int prepare(String id, long version, String plan, String prompt, String hash, String now);
    @Update("""
        UPDATE source_template_model SET external_session_id=#{session},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND external_session_id IS NULL
        """)
    int attach(String id, long version, String session, String now);
    @Update("""
        UPDATE source_template_model SET output_json=#{output},output_sha256=#{hash},accepted_at=#{now},updated_at=#{now}
        WHERE id=#{id} AND output_json IS NULL AND state IN ('DISPATCHING','RUNNING')
        """)
    int accept(String id, String output, String hash, String now);
    @Update("""
        UPDATE source_template_model SET output_json=#{output},output_sha256=#{hash},accepted_at=#{accepted},updated_at=#{now}
        WHERE id=#{id} AND state='PREPARED' AND output_json IS NULL
        """)
    int reuse(String id, String output, String hash, String accepted, String now);
    @Insert("""
        INSERT OR IGNORE INTO source_template_result_read(model_id,result_id,sha256,part,created_at)
        VALUES(#{model},#{result},#{hash},#{part},#{now})
        """)
    int resultRead(String model, String result, String hash, int part, String now);
    @Select("""
        SELECT count(*) FROM source_template_result_read
        WHERE model_id=#{model} AND result_id=#{result} AND sha256=#{hash}
        """)
    int resultReads(String model, String result, String hash);
    @Insert("""
        INSERT OR IGNORE INTO source_template_read(model_id,path,sha256,start_line,end_line,total_lines,content,created_at)
        VALUES(#{modelId},#{path},#{sha256},#{startLine},#{endLine},#{totalLines},#{content},#{createdAt})
        """)
    int readEvidence(Read row);
    @Select("SELECT * FROM source_template_read WHERE model_id=#{model} AND path=#{path} ORDER BY start_line,end_line")
    List<Read> reads(String model, String path);
    @Select("SELECT * FROM source_template_design_progress WHERE run_id=#{run}")
    Optional<Progress> progress(String run);
    @Select("""
        SELECT COALESCE(sum(max(0,(julianday(CASE WHEN state IN ('VALIDATED','FAILED','STOPPED') THEN updated_at ELSE #{now} END)
            - julianday(started_at))*86400000)),0) FROM source_template_model WHERE run_id=#{run} AND started_at IS NOT NULL
        """)
    long elapsedMillis(String run, String now);
    @Insert("INSERT INTO source_template_design_progress VALUES(#{runId},#{generation},#{planJson},#{planSha256},#{createdAt})")
    int insertProgress(Progress row);
    @Update("UPDATE source_template_design_progress SET generation=#{next} WHERE run_id=#{run} AND generation=#{previous}")
    int nextRound(String run, int previous, int next);
    record Read(String modelId, String path, String sha256, int startLine, int endLine, int totalLines, String content, String createdAt) { }
    record Progress(String runId, int generation, String planJson, String planSha256, String createdAt) { }
    record Count(String candidateKind, String state, long count) { }
    record Metadata(String id, String candidateKind, int ordinal, int generation, int attempt, String state,
                    String errorCode, long version, String createdAt, String updatedAt, boolean retryable) { }
}
