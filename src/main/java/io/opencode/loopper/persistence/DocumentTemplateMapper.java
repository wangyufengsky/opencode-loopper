package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentTemplateMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_run WHERE id=#{id}")
    Optional<DocumentTemplateRunRow> find(String id);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_run WHERE request_key=#{key}")
    Optional<DocumentTemplateRunRow> findRequest(String key);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_run WHERE task_id=#{taskId}")
    Optional<DocumentTemplateRunRow> findTask(String taskId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_run WHERE designer_id=#{designerId}")
    Optional<DocumentTemplateRunRow> findDesigner(String designerId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT count(*) FROM document_template_upload_ready WHERE run_id=#{id}")
    boolean uploadReady(String id);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT EXISTS(SELECT 1 FROM document_requirement_supplement WHERE run_id=#{id} AND upload_ready=0 AND applied_at IS NULL)")
    boolean supplementalUploadPending(String id);
    @Insert("""
        INSERT OR IGNORE INTO document_template_upload_ready(run_id,completed_at)
        SELECT id,#{now} FROM document_template_run WHERE id=#{id}
          AND (state='PREPARING' OR (state='WAITING_INPUT' AND resume_state='PREPARING'))
        """)
    int markUploadReady(@Param("id") String id, @Param("now") String now);
    @Insert("""
        INSERT INTO document_template_run(id,request_key,request_sha256,project_id,template_id,template_version,
          title,state,branch_json,contract_json,created_at,updated_at)
        VALUES(#{id},#{requestKey},#{requestSha256},#{projectId},#{templateId},#{templateVersion},
          #{title},#{state},#{branchJson},#{contractJson},#{createdAt},#{updatedAt})
        """)
    int insert(DocumentTemplateRunRow row);
    @Update("""
        UPDATE document_template_run SET state=#{state},resume_state=#{resumeState},
          waiting_reason_code=#{code},waiting_message=#{message},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version}
        """)
    int transition(@Param("id") String id, @Param("version") long version, @Param("state") String state,
                   @Param("resumeState") String resumeState, @Param("code") String code,
                   @Param("message") String message, @Param("now") String now);
    @Update("""
        UPDATE document_template_run SET snapshot_json=#{snapshot},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND snapshot_json IS NULL
        """)
    int bindSnapshot(@Param("id") String id, @Param("version") long version,
                     @Param("snapshot") String snapshot, @Param("now") String now);
    @Update("""
        UPDATE document_template_run SET snapshot_json=#{snapshot},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND snapshot_json=#{previous}
        """)
    int finishSnapshot(@Param("id") String id, @Param("version") long version, @Param("previous") String previous,
                       @Param("snapshot") String snapshot, @Param("now") String now);
    @Update("""
        UPDATE document_template_run SET archived=#{archived},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version}
        """)
    int archive(@Param("id") String id, @Param("version") long version, @Param("archived") int archived, @Param("now") String now);
    @Update("""
        UPDATE document_template_run SET waiting_reason_code=#{code},waiting_message=#{message},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version}
        """)
    int touch(@Param("id") String id, @Param("version") long version, @Param("code") String code,
              @Param("message") String message, @Param("now") String now);
    @Insert("""
        INSERT INTO document_template_file(id,run_id,ordinal,filename,format,size_bytes,sha256,
          representation_sha256,parser_version,relative_path,section_count,limitations_json)
        VALUES(#{id},#{runId},#{ordinal},#{filename},#{format},#{sizeBytes},#{sha256},
          #{representationSha256},#{parserVersion},#{relativePath},#{sectionCount},#{limitationsJson})
        """)
    int insertFile(DocumentTemplateFileRow row);
    @Insert("""
        INSERT INTO document_template_section(file_id,ordinal,title,content,sha256)
        VALUES(#{fileId},#{ordinal},#{title},#{content},#{sha256})
        """)
    int insertSection(Section row);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_file WHERE run_id=#{runId} ORDER BY ordinal")
    List<DocumentTemplateFileRow> files(String runId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT f.* FROM document_template_file f WHERE f.run_id=#{runId} AND f.id IN (
          SELECT json_extract(s.value,'$.fileId') FROM document_requirement_batch b
          JOIN document_template_model_run m ON m.id=b.extraction_model_id,json_each(m.input_json,'$.sections') s
          WHERE b.run_id=#{runId} AND b.round=#{revision}
          UNION SELECT json_extract(s.value,'$.fileId') FROM document_requirement r,json_each(r.sources_json) s
          WHERE r.run_id=#{runId} AND r.revision=#{revision}) ORDER BY f.ordinal
        """)
    List<DocumentTemplateFileRow> revisionFiles(@Param("runId") String runId, @Param("revision") int revision);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_file WHERE run_id=#{runId} AND id=#{fileId}")
    Optional<DocumentTemplateFileRow> file(@Param("runId") String runId, @Param("fileId") String fileId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT file_id,ordinal,title,length(content) AS characters,sha256 FROM document_template_section
        WHERE file_id=#{fileId} AND ordinal>=#{offset} ORDER BY ordinal LIMIT #{limit}
        """)
    List<SectionSummary> sections(@Param("fileId") String fileId, @Param("offset") int offset, @Param("limit") int limit);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_section WHERE file_id=#{fileId} AND ordinal=#{ordinal}")
    Optional<Section> section(@Param("fileId") String fileId, @Param("ordinal") int ordinal);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT id FROM document_template_run WHERE state NOT IN ('COMPLETED','CANCELLED','WAITING_INPUT')
        ORDER BY updated_at,id LIMIT 100
        """)
    List<String> activeIds();
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT id FROM document_template_run WHERE (state NOT IN ('COMPLETED','CANCELLED','WAITING_INPUT')
          OR (state='WAITING_INPUT' AND waiting_reason_code='DOCUMENT_DEVELOPMENT_WAIT')) AND id>#{after} ORDER BY id LIMIT 100
        """)
    List<String> activeAfter(String after);
    record Section(String fileId, int ordinal, String title, String content, String sha256) { }
    record SectionSummary(String fileId, int ordinal, String title, int characters, String sha256) { }
}
