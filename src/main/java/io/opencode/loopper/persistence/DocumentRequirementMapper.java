package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentRequirementMapper extends DocumentSourceMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_requirement_batch WHERE run_id=#{runId} AND ordinal=#{ordinal} AND round=#{round}")
    Optional<Batch> batch(@Param("runId") String runId, @Param("ordinal") int ordinal, @Param("round") int round);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_requirement_batch WHERE run_id=#{runId} AND round=#{round}
          AND ordinal>#{after} ORDER BY ordinal LIMIT 100
        """)
    List<Batch> batches(@Param("runId") String runId, @Param("round") int round, @Param("after") int after);
    @Insert("""
        INSERT INTO document_requirement_batch(run_id,ordinal,round,extraction_model_id,review_model_id,
          candidate_json,candidate_sha256,created_at)
        VALUES(#{runId},#{ordinal},#{round},#{extractionModelId},#{reviewModelId},#{candidateJson},#{candidateSha256},#{createdAt})
        """)
    int insertBatch(Batch row);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_requirement_revision WHERE run_id=#{runId} AND revision=#{revision}")
    Optional<Revision> revision(@Param("runId") String runId, @Param("revision") int revision);
    @Insert("""
        INSERT INTO document_requirement_revision(run_id,revision,manifest_sha256,source_json,created_at)
        VALUES(#{runId},#{revision},#{manifestSha256},#{sourceJson},#{createdAt})
        """)
    int insertRevision(Revision row);
    @Insert("""
        INSERT INTO document_requirement(run_id,revision,requirement_key,ordinal,title,group_name,kind,statement,sources_json,acceptance_json,issues_json)
        VALUES(#{runId},#{revision},#{requirementKey},#{ordinal},#{title},#{groupName},#{kind},#{statement},#{sourcesJson},#{acceptanceJson},#{issuesJson})
        """)
    int insert(Requirement row);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM document_requirement WHERE run_id=#{runId} AND revision=#{revision} AND ordinal>#{after}
        ORDER BY ordinal LIMIT #{limit}
        """)
    List<Requirement> page(@Param("runId") String runId, @Param("revision") int revision,
                           @Param("after") int after, @Param("limit") int limit);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT count(*) FROM document_requirement WHERE run_id=#{runId} AND revision=#{revision}")
    int count(@Param("runId") String runId, @Param("revision") int revision);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT r.requirement_key,r.ordinal,r.title,r.group_name,r.kind,json_array_length(r.issues_json) AS issue_count,
          json_extract(a.assessment_json,'$.conclusion') AS conclusion
        FROM document_requirement r LEFT JOIN document_requirement_assessment a
          ON a.run_id=r.run_id AND a.revision=r.revision AND a.requirement_key=r.requirement_key
        WHERE r.run_id=#{runId} AND r.revision=#{revision} AND r.ordinal>#{after}
          AND (#{issuesOnly}=0 OR json_array_length(r.issues_json)>0)
        ORDER BY r.ordinal LIMIT #{limit}
        """)
    List<Summary> summaries(@Param("runId") String runId, @Param("revision") int revision,
            @Param("after") int after, @Param("limit") int limit, @Param("issuesOnly") boolean issuesOnly);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_requirement WHERE run_id=#{runId} AND revision=#{revision} AND requirement_key=#{key}")
    Optional<Requirement> item(@Param("runId") String runId, @Param("revision") int revision, @Param("key") String key);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT assessment_json FROM document_requirement_assessment WHERE run_id=#{runId} AND revision=#{revision} AND requirement_key=#{key}")
    Optional<String> assessment(@Param("runId") String runId, @Param("revision") int revision, @Param("key") String key);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT count(*) FROM document_requirement WHERE run_id=#{runId} AND revision=#{revision}
          AND ordinal>=#{first} AND ordinal<#{last}
        """)
    int batchCount(@Param("runId") String runId, @Param("revision") int revision,
                   @Param("first") int first, @Param("last") int last);
    @Update("""
        UPDATE document_template_run SET requirement_revision=#{revision},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND requirement_revision=#{previous}
        """)
    int bindRevision(@Param("id") String id, @Param("version") long version, @Param("revision") int revision,
                     @Param("previous") int previous, @Param("now") String now);
    record Batch(String runId, int ordinal, int round, String extractionModelId, String reviewModelId,
                 String candidateJson, String candidateSha256, String createdAt) { }
    record Revision(String runId, int revision, String manifestSha256, String sourceJson, String createdAt) { }
    record Requirement(String runId, int revision, String requirementKey, int ordinal, String title, String groupName,
                       String kind, String statement, String sourcesJson, String acceptanceJson, String issuesJson) { }
    record Summary(String requirementKey, int ordinal, String title, String groupName, String kind, int issueCount, String conclusion) { }
}
