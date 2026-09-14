package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Immutable source authority, separate from any model interpretation or assessment. */
public interface DocumentSourceMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_basis_revision WHERE run_id=#{run} AND revision=#{revision}")
    Optional<Basis> basis(@Param("run") String run, @Param("revision") int revision);
    @Insert("""
        INSERT INTO document_basis_revision(run_id,revision,source_kind,manifest_sha256,source_json,created_at)
        VALUES(#{runId},#{revision},#{sourceKind},#{manifestSha256},#{sourceJson},#{createdAt})
        """)
    int insertBasis(Basis basis);
    @Update("""
        UPDATE document_template_run SET source_revision=#{next},version=version+1,updated_at=#{now}
        WHERE id=#{run} AND version=#{version} AND template_version='2' AND source_revision=#{previous}
        """)
    int bindSource(@Param("run") String run, @Param("version") long version, @Param("previous") int previous,
                   @Param("next") int next, @Param("now") String now);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT f.* FROM document_template_file f JOIN document_basis_revision b ON b.run_id=f.run_id
        WHERE b.run_id=#{run} AND b.revision=#{revision} AND b.source_kind='DOCUMENT_SOURCE'
          AND f.id IN (SELECT json_extract(value,'$.id') FROM json_each(b.source_json,'$.files')) ORDER BY f.ordinal
        """)
    List<DocumentTemplateFileRow> sourceFiles(@Param("run") String run, @Param("revision") int revision);
    @Insert("""
        INSERT OR IGNORE INTO document_source_read(session_id,run_id,source_revision,file_id,section,sha256)
        VALUES(#{session},#{run},#{revision},#{file},#{section},#{sha})
        """)
    int recordSourceRead(@Param("session") String session, @Param("run") String run, @Param("revision") int revision,
                         @Param("file") String file, @Param("section") int section, @Param("sha") String sha);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT count(*) FROM document_source_read WHERE session_id=#{session} AND run_id=#{run}
          AND source_revision=#{revision} AND file_id=#{file}
        """)
    int sourceReadCount(@Param("session") String session, @Param("run") String run, @Param("revision") int revision,
                        @Param("file") String file);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT s.* FROM document_template_section s JOIN document_template_file f ON f.id=s.file_id
        JOIN document_basis_revision b ON b.run_id=f.run_id
        WHERE b.run_id=#{run} AND b.revision=#{revision} AND b.source_kind='DOCUMENT_SOURCE'
          AND f.id=#{file} AND f.id IN(SELECT json_extract(value,'$.id') FROM json_each(b.source_json,'$.files'))
          AND s.ordinal>=#{offset} ORDER BY s.ordinal LIMIT #{limit}
        """)
    List<DocumentTemplateMapper.Section> sourceSections(@Param("run") String run, @Param("revision") int revision,
            @Param("file") String file, @Param("offset") int offset, @Param("limit") int limit);
    @Select("""
        SELECT EXISTS(SELECT 1 FROM document_source_read WHERE session_id=#{session} AND run_id=#{run}
          AND source_revision=#{revision} AND file_id=#{file} AND section=#{section} AND sha256=#{sha})
        """)
    boolean sourceRead(@Param("session") String session, @Param("run") String run, @Param("revision") int revision,
            @Param("file") String file, @Param("section") int section, @Param("sha") String sha);
    record Basis(String runId, int revision, String sourceKind, String manifestSha256, String sourceJson, String createdAt) { }
}
