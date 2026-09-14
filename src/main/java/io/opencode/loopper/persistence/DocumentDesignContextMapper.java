package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Frozen design provenance; queries never infer document identity from model-supplied text. */
public interface DocumentDesignContextMapper extends DesignerTimeoutMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT r.requirement_key FROM document_template_run d JOIN document_requirement r
          ON r.run_id=d.id AND r.revision=d.requirement_revision
        WHERE d.task_id=#{task} AND d.template_id='REQUIREMENT_DEVELOPMENT' ORDER BY r.ordinal LIMIT 4097
        """)
    List<String> documentTaskRequirementRefs(String task);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT state FROM document_template_run WHERE task_id=#{task} AND template_id='REQUIREMENT_DEVELOPMENT'")
    Optional<String> documentTaskState(String task);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT EXISTS(SELECT 1 FROM document_template_run WHERE designer_id=#{designer} AND template_id='REQUIREMENT_DEVELOPMENT')")
    boolean documentDesigner(String designer);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT contract_json FROM document_template_run WHERE designer_id=#{designer} AND template_id='REQUIREMENT_DEVELOPMENT'")
    Optional<String> documentDesignerContract(String designer);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT b.* FROM document_development_design b JOIN design_requirement_revision d
          ON d.id=b.requirement_revision_id JOIN document_requirement_revision r
          ON r.run_id=b.run_id AND r.revision=b.document_revision AND r.manifest_sha256=b.manifest_sha256
        WHERE b.requirement_revision_id=#{revision} AND d.designer_session_id=#{designer}
        """)
    Optional<DocumentDevelopmentMapper.Design> documentDesign(@Param("revision") String revision, @Param("designer") String designer);
    @Insert("""
        INSERT INTO document_development_plan_source(plan_revision_id,run_id,document_revision,manifest_sha256,created_at)
        SELECT p.id,d.id,d.requirement_revision,r.manifest_sha256,p.created_at
        FROM task_package_plan_revision p JOIN document_template_run d ON d.task_id=p.task_id
        JOIN document_requirement_revision r ON r.run_id=d.id AND r.revision=d.requirement_revision
        WHERE p.id=#{planId} AND d.template_id='REQUIREMENT_DEVELOPMENT'
        ON CONFLICT(plan_revision_id) DO NOTHING
        """)
    int freezeDocumentPlanSource(String planId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT p.requirement_revision_id,s.run_id,s.document_revision,s.manifest_sha256,s.created_at
        FROM document_development_plan_source s JOIN task_package_plan_revision p ON p.id=s.plan_revision_id
        JOIN document_requirement_revision r ON r.run_id=s.run_id AND r.revision=s.document_revision
          AND r.manifest_sha256=s.manifest_sha256 WHERE s.plan_revision_id=#{planId}
        """)
    Optional<DocumentDevelopmentMapper.Design> documentPlanSource(String planId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT NOT EXISTS(SELECT 1 FROM task_package_plan_revision p JOIN document_template_run d ON d.task_id=p.task_id
          LEFT JOIN document_development_plan_source s ON s.plan_revision_id=p.id
          LEFT JOIN document_development_design b ON b.requirement_revision_id=p.requirement_revision_id
          WHERE p.id=#{planId} AND d.template_id='REQUIREMENT_DEVELOPMENT'
            AND (coalesce(s.document_revision,b.document_revision,-1)<>d.requirement_revision))
        """)
    boolean documentPlanSourceCurrent(String planId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT coalesce(ps.document_revision,b.document_revision)
        FROM stage s JOIN task_package_run pr ON pr.id=s.package_run_id
        JOIN design_work_package w ON w.id=pr.design_work_package_id
        JOIN document_development_design b ON b.requirement_revision_id=w.requirement_revision_id
        LEFT JOIN document_development_plan_source ps ON ps.plan_revision_id=pr.plan_revision_id AND ps.run_id=b.run_id
        WHERE s.id=#{stage}
        """)
    Integer documentStageRevision(String stage);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT s.document_revision FROM document_development_task_source s
        JOIN document_requirement_revision r ON r.run_id=s.run_id AND r.revision=s.document_revision
          AND r.manifest_sha256=s.manifest_sha256 WHERE s.task_id=#{task}
        """)
    Integer documentTaskRevision(String task);
    @Insert("""
        INSERT INTO document_development_task_source(task_id,run_id,document_revision,manifest_sha256,created_at)
        SELECT t.id,d.id,b.document_revision,b.manifest_sha256,t.created_at
        FROM task t JOIN document_template_run d ON d.task_id=t.id
        JOIN designer_session s ON s.id=d.designer_id
        JOIN design_requirement_revision r ON r.designer_session_id=s.id AND r.revision=s.current_requirement_revision
        JOIN document_development_design b ON b.requirement_revision_id=r.id AND b.run_id=d.id
        WHERE t.id=#{task} ON CONFLICT(task_id) DO NOTHING
        """)
    int freezeDocumentTaskSource(String task);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT coalesce(ps.document_revision,b.document_revision)
        FROM designer_session d JOIN design_requirement_revision r
          ON r.designer_session_id=d.id AND r.revision=d.current_requirement_revision
        JOIN document_development_design b ON b.requirement_revision_id=r.id
        LEFT JOIN design_work_package w ON w.requirement_revision_id=r.id AND w.package_id=d.active_work_package_id
          AND w.superseded_at IS NULL
        LEFT JOIN task_package_run pr ON pr.design_work_package_id=w.id
        LEFT JOIN document_development_plan_source ps ON ps.plan_revision_id=pr.plan_revision_id AND ps.run_id=b.run_id
        WHERE d.id=#{designer} ORDER BY w.plan_revision DESC LIMIT 1
        """)
    Integer documentDesignerRevision(String designer);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT s.document_revision FROM document_development_plan_source s
        JOIN task_package_plan_revision p ON p.id=s.plan_revision_id WHERE p.external_session_id=#{session}
        """)
    Integer documentPlanSessionRevision(String session);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT p.* FROM task_package_plan_revision p JOIN document_development_plan_source s ON s.plan_revision_id=p.id WHERE p.external_session_id=#{session}")
    Optional<TaskPackagePlanRevisionRow> documentPlanSession(String session);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT b.requirement_revision_id,b.run_id,coalesce(s.document_revision,b.document_revision) AS document_revision,
          r.manifest_sha256,b.created_at
        FROM document_development_design b JOIN design_work_package w ON w.requirement_revision_id=b.requirement_revision_id
        LEFT JOIN task_package_run pr ON pr.design_work_package_id=w.id
        LEFT JOIN document_development_plan_source s ON s.plan_revision_id=pr.plan_revision_id AND s.run_id=b.run_id
        JOIN document_requirement_revision r ON r.run_id=b.run_id AND r.revision=coalesce(s.document_revision,b.document_revision)
        WHERE w.id=#{workPackage} AND w.designer_session_id=#{designer}
          AND r.manifest_sha256=coalesce(s.manifest_sha256,b.manifest_sha256)
        """)
    Optional<DocumentDevelopmentMapper.Design> documentPackageDesign(@Param("workPackage") String workPackage, @Param("designer") String designer);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT r.* FROM document_development_design b
        JOIN design_work_package w ON w.requirement_revision_id=b.requirement_revision_id
        LEFT JOIN task_package_run pr ON pr.design_work_package_id=w.id
        LEFT JOIN document_development_plan_source s ON s.plan_revision_id=pr.plan_revision_id AND s.run_id=b.run_id
        JOIN document_requirement r ON b.run_id=r.run_id AND r.revision=coalesce(s.document_revision,b.document_revision)
        JOIN task_decomposition d ON d.id=w.decomposition_id
        WHERE b.requirement_revision_id=#{revision} AND w.id=#{workPackage}
          AND (r.requirement_key IN(SELECT value FROM json_each(w.requirement_refs_json))
            OR r.requirement_key IN(SELECT refs.value FROM json_each(d.plan_json,'$.globalConstraints') g,
              json_each(g.value,'$.requirementRefs') refs))
        ORDER BY r.ordinal LIMIT 4097
        """)
    List<DocumentRequirementMapper.Requirement> documentPackageRequirements(@Param("revision") String revision,
                                                                          @Param("workPackage") String workPackage);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT NOT EXISTS(SELECT 1 FROM design_work_package later JOIN design_work_package current
          ON later.requirement_revision_id=current.requirement_revision_id AND later.ordinal>current.ordinal
          AND later.plan_revision=current.plan_revision
          WHERE current.id=#{workPackage})
        """)
    boolean documentLastPackage(String workPackage);
}
