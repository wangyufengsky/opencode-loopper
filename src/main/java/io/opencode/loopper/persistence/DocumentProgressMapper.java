package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentProgressMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
        SELECT coalesce(m.attempts,0) AS attempts,coalesce(m.validated,0) AS validated,coalesce(m.active,0) AS active,
          coalesce(m.stopped,0) AS stopped,
          (SELECT count(*) FROM document_requirement r WHERE r.run_id=d.id AND r.revision=d.requirement_revision) AS requirements,
          (SELECT count(*) FROM document_template_artifact a WHERE a.run_id=d.id AND a.requirement_revision=d.requirement_revision) AS reports,
          d.version+coalesce(m.revision,0) AS revision
        FROM document_template_run d LEFT JOIN (
          SELECT run_id,count(*) AS attempts,sum(state='VALIDATED') AS validated,
            sum(state NOT IN ('VALIDATED','STOPPED','FAILED')) AS active,sum(state IN ('STOPPED','FAILED')) AS stopped,
            sum(version)+sum(output_json IS NOT NULL) AS revision FROM document_template_model_run WHERE run_id=#{id} GROUP BY run_id
        ) m ON m.run_id=d.id WHERE d.id=#{id}
        """)
    Progress progress(String id);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT t.state FROM document_template_run d JOIN task t ON t.id=d.task_id WHERE d.id=#{id}")
    String taskState(String id);
    record Progress(int attempts, int validated, int active, int stopped, int requirements, int reports, long revision) { }
}
