package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Source provenance joins an actual immutable source manifest, never a fabricated uploaded document. */
public interface SourceDevelopmentContextMapper {
    @Select("SELECT * FROM source_template_file WHERE run_id=#{run} AND target=1 AND exclusion IS NULL ORDER BY ordinal")
    java.util.List<SourceTemplateMapper.File> sourceDevelopmentFiles(String run);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT * FROM source_template_run WHERE designer_id=#{designer} AND template_id='UNIT_TEST_DEVELOPMENT'
        """)
    Optional<SourceTemplateRunRow> sourceDevelopmentDesigner(String designer);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM source_template_run WHERE task_id=#{task} AND template_id='UNIT_TEST_DEVELOPMENT'")
    Optional<SourceTemplateRunRow> sourceDevelopmentTask(String task);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT b.requirement_revision_id AS owner_id,b.run_id,b.manifest_sha256,b.created_at
        FROM source_development_design b JOIN source_template_run r ON r.id=b.run_id
        JOIN design_requirement_revision d ON d.id=b.requirement_revision_id AND d.designer_session_id=r.designer_id
        WHERE d.id=#{revision} AND d.designer_session_id=#{designer} AND json_extract(r.snapshot_json,'$.ready')=1
          AND b.manifest_sha256=json_extract(r.snapshot_json,'$.manifestSha256')
        """)
    Optional<SourceBinding> sourceDevelopmentDesign(String revision, String designer);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT b.requirement_revision_id AS owner_id,b.run_id,b.manifest_sha256,b.created_at
        FROM source_development_design b JOIN source_template_run r ON r.id=b.run_id
        JOIN design_work_package w ON w.requirement_revision_id=b.requirement_revision_id AND w.designer_session_id=r.designer_id
        WHERE w.id=#{workPackage} AND w.designer_session_id=#{designer} AND json_extract(r.snapshot_json,'$.ready')=1
          AND b.manifest_sha256=json_extract(r.snapshot_json,'$.manifestSha256')
        """)
    Optional<SourceBinding> sourceDevelopmentPackage(String workPackage, String designer);
    @Insert("INSERT INTO source_development_design VALUES(#{ownerId},#{runId},#{manifestSha256},#{createdAt})")
    int insertSourceDevelopmentDesign(SourceBinding row);
    @Insert("""
        INSERT INTO source_development_task(task_id,run_id,manifest_sha256,created_at)
        SELECT t.id,r.id,json_extract(r.snapshot_json,'$.manifestSha256'),t.created_at
        FROM task t JOIN source_template_run r ON r.task_id=t.id WHERE t.id=#{task}
          AND r.template_id='UNIT_TEST_DEVELOPMENT' AND json_extract(r.snapshot_json,'$.ready')=1
        ON CONFLICT(task_id) DO NOTHING
        """)
    int freezeSourceDevelopmentTask(String task);
    @Insert("""
        INSERT INTO source_development_plan(plan_revision_id,run_id,manifest_sha256,created_at)
        SELECT p.id,r.id,b.manifest_sha256,p.created_at FROM task_package_plan_revision p
        JOIN source_development_task b ON b.task_id=p.task_id JOIN source_template_run r ON r.id=b.run_id
        WHERE p.id=#{plan} AND b.manifest_sha256=json_extract(r.snapshot_json,'$.manifestSha256')
        ON CONFLICT(plan_revision_id) DO NOTHING
        """)
    int freezeSourceDevelopmentPlan(String plan);
    @Select("""
        SELECT b.plan_revision_id AS owner_id,b.run_id,b.manifest_sha256,b.created_at
        FROM source_development_plan b JOIN source_template_run r ON r.id=b.run_id
        WHERE b.plan_revision_id=#{plan} AND b.manifest_sha256=json_extract(r.snapshot_json,'$.manifestSha256')
        """)
    Optional<SourceBinding> sourceDevelopmentPlan(String plan);
    @Select("SELECT * FROM source_test_profile WHERE run_id=#{run}")
    Optional<TestProfile> sourceTestProfile(String run);
    @Insert("INSERT INTO source_test_profile VALUES(#{runId},#{profileJson},#{sha256},#{createdAt})")
    int insertSourceTestProfile(TestProfile row);
    @Select("SELECT run_id,snapshot_json AS profile_json,sha256,created_at FROM source_test_baseline WHERE run_id=#{run}")
    Optional<TestProfile> sourceTestBaseline(String run);
    @Insert("INSERT INTO source_test_baseline VALUES(#{runId},#{profileJson},#{sha256},#{createdAt})")
    int insertSourceTestBaseline(TestProfile row);
    @Update("""
        UPDATE source_template_run SET designer_id=#{designer},version=version+1,updated_at=#{now}
        WHERE id=#{run} AND version=#{version} AND state='DESIGNING' AND designer_id IS NULL
        """)
    int linkSourceDesigner(String run, long version, String designer, String now);
    @Update("""
        UPDATE source_template_run SET task_id=#{task},version=version+1,updated_at=#{now}
        WHERE id=#{run} AND version=#{version} AND state='DESIGNING' AND designer_id=#{designer} AND task_id IS NULL
        """)
    int linkSourceTask(String run, long version, String designer, String task, String now);
    record SourceBinding(String ownerId, String runId, String manifestSha256, String createdAt) { }
    record TestProfile(String runId, String profileJson, String sha256, String createdAt) { }
}
