package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentDevelopmentEvidenceMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_development_evidence WHERE run_id=#{run}")
    Optional<Evidence> find(String run);
    @Insert("""
        INSERT INTO document_development_evidence(run_id,requirement_revision,task_id,cycle_id,content_json,sha256,created_at)
        VALUES(#{runId},#{requirementRevision},#{taskId},#{cycleId},#{contentJson},#{sha256},#{createdAt})
        """)
    int insert(Evidence evidence);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT a.* FROM package_design_candidate_accepted_result a
        JOIN loop_spec_compilation c ON c.id=a.settled_compilation_id
        JOIN design_work_package p ON p.id=a.design_work_package_id
        WHERE p.id=#{workPackage} AND c.design_revision=#{revision} AND c.state='COMPLETED'
        ORDER BY a.created_at DESC,a.candidate_run_id DESC LIMIT 1
        """)
    Optional<PackageDesignAcceptedResultRow> accepted(@Param("workPackage") String workPackage, @Param("revision") int revision);
    record Evidence(String runId, int requirementRevision, String taskId, String cycleId,
                    String contentJson, String sha256, String createdAt) { }
}
