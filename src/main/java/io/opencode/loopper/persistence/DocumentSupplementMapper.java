package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentSupplementMapper {
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    @Select("SELECT EXISTS(SELECT 1 FROM document_template_run WHERE id=#{run} AND version=#{version} AND state='DESIGNING' AND task_id IS NULL)")
    boolean designInputCurrent(@Param("run") String run,@Param("version") long version);
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    @Select("SELECT * FROM document_supplement_design WHERE run_id=#{run} AND request_key=#{key}")
    Optional<Design> design(@Param("run") String run,@Param("key") String key);
    @Insert("INSERT INTO document_supplement_design VALUES(#{runId},#{requestKey},#{designerId},#{sourceRevisionId},#{targetRevisionId},#{profileJson},#{designerVersion},#{discussionRevision},#{createdAt})")
    int insertDesign(Design row);
    record Design(String runId,String requestKey,String designerId,String sourceRevisionId,String targetRevisionId,
                  String profileJson,long designerVersion,int discussionRevision,String createdAt) { }
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    @Select("SELECT * FROM document_requirement_supplement WHERE run_id=#{run} AND applied_at IS NULL")
    Optional<Supplement> pending(String run);
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    @Select("SELECT * FROM document_requirement_supplement WHERE run_id=#{run} AND request_key=#{key}")
    Optional<Supplement> request(@Param("run") String run,@Param("key") String key);
    @Insert("""
        INSERT INTO document_requirement_supplement(run_id,request_key,request_sha256,target_revision,base_run_version,base_task_version,
          base_package_id,base_package_version,first_file_ordinal,file_count,plan_revision_floor,created_at)
        VALUES(#{runId},#{requestKey},#{requestSha256},#{targetRevision},#{baseRunVersion},#{baseTaskVersion},#{basePackageId},
          #{basePackageVersion},#{firstFileOrdinal},#{fileCount},#{planRevisionFloor},#{createdAt})
        """)
    int insert(Supplement row);
    @Update("UPDATE document_requirement_supplement SET upload_ready=1 WHERE run_id=#{run} AND request_key=#{key} AND upload_ready=0 AND applied_at IS NULL")
    int ready(@Param("run") String run,@Param("key") String key);
    @Update("UPDATE document_requirement_supplement SET plan_id=#{plan} WHERE run_id=#{run} AND request_key=#{key} AND plan_id IS NULL AND applied_at IS NULL")
    int plan(@Param("run") String run,@Param("key") String key,@Param("plan") String plan);
    @Update("""
        UPDATE document_requirement_supplement SET plan_id=NULL WHERE run_id=#{run} AND request_key=#{key}
          AND plan_id=#{plan} AND applied_at IS NULL
          AND EXISTS(SELECT 1 FROM task_package_plan_revision p WHERE p.id=#{plan} AND p.state='FAILED'
            AND p.external_session_state IN ('NOT_CREATED','ABORT_ACKNOWLEDGED','REMOTE_COMPLETED','ALREADY_ABSENT'))
        """)
    int retryPlan(@Param("run") String run,@Param("key") String key,@Param("plan") String plan);
    @Update("UPDATE document_requirement_supplement SET applied_at=#{now} WHERE run_id=#{run} AND request_key=#{key} AND applied_at IS NULL")
    int applied(@Param("run") String run,@Param("key") String key,@Param("now") String now);
    record Supplement(String runId,String requestKey,String requestSha256,int targetRevision,long baseRunVersion,long baseTaskVersion,
                      String basePackageId,long basePackageVersion,int firstFileOrdinal,int fileCount,int planRevisionFloor,
                      String planId,int uploadReady,String createdAt,String appliedAt) { }
}
