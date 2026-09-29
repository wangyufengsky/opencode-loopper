package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowPlanMapper {
    @Select("SELECT * FROM workflow_requirement WHERE id=#{id}")
    Optional<WorkflowRows.Requirement> find(String id);
    @Select("""
        SELECT id,project_id,title,state,head_revision,version,created_at,updated_at FROM workflow_requirement
        WHERE (#{project} IS NULL OR project_id=#{project})
        AND (#{afterTime} IS NULL OR created_at < #{afterTime} OR (created_at=#{afterTime} AND id < #{afterId}))
        ORDER BY created_at DESC,id DESC LIMIT #{limit}
        """)
    List<WorkflowRows.RequirementSummary> page(String project, String afterTime, String afterId, int limit);
    @Select("SELECT requirement_id AS owner_id,revision,definition_json,sha256,created_at FROM workflow_plan_revision WHERE requirement_id=#{id} AND revision=#{revision}")
    Optional<WorkflowRows.Revision> revision(String id, int revision);
    @Insert("""
        INSERT INTO workflow_requirement(id,project_id,title,objective,state,head_revision,version,source_template_id,
        source_revision,layout_json,layout_version,created_at,updated_at)
        VALUES(#{id},#{projectId},#{title},#{objective},#{state},#{headRevision},#{version},#{sourceTemplateId},
        #{sourceRevision},#{layoutJson},#{layoutVersion},#{createdAt},#{updatedAt})
        """)
    int insert(WorkflowRows.Requirement row);
    @Insert("""
        INSERT INTO workflow_plan_revision(requirement_id,revision,definition_json,sha256,source,base_revision,created_at)
        VALUES(#{row.ownerId},#{row.revision},#{row.definitionJson},#{row.sha256},#{source},#{baseRevision},#{row.createdAt})
        """)
    int insertRevision(WorkflowRows.Revision row, String source, Integer baseRevision);
    @Update("""
        UPDATE workflow_requirement SET head_revision=#{revision},version=version+1,updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND head_revision=#{revision}-1 AND state='PLANNING'
        """)
    int revise(String id, long version, int revision, String now);
    @Update("""
        UPDATE workflow_requirement SET head_revision=#{revision},version=version+1,updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND head_revision=#{revision}-1 AND state IN ('RUNNING','PAUSED','STALLED')
        """) int reviseActive(String id,long version,int revision,String now);
    @Update("""
        UPDATE workflow_requirement SET state=#{state},version=version+1,updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND state=#{previous}
        """)
    int transition(String id, long version, String previous, String state, String now);
    @Update("""
        UPDATE workflow_requirement SET layout_json=#{body},layout_version=layout_version+1
        WHERE id=#{id} AND layout_version=#{version} AND head_revision=#{revision}
        """)
    int layout(String id, int revision, long version, String body);
    @Update("UPDATE workflow_requirement SET version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int touch(String id, long version, String now);
}
