package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowTemplateMapper {
    @Select("SELECT * FROM workflow_template WHERE id=#{id}")
    Optional<WorkflowRows.Template> find(String id);
    @Select("""
        SELECT id,title,description,builtin,head_revision,version,created_at,updated_at FROM workflow_template
        WHERE archived=0 AND (#{kind}='ALL' OR (#{kind}='BUILTIN' AND builtin=1) OR (#{kind}='CUSTOM' AND builtin=0))
        AND (#{query}='' OR instr(lower(title),lower(#{query}))>0 OR instr(lower(description),lower(#{query}))>0)
        AND (#{afterTime} IS NULL OR created_at < #{afterTime} OR (created_at=#{afterTime} AND id < #{afterId}))
        ORDER BY created_at DESC,id DESC LIMIT #{limit}
        """)
    List<WorkflowRows.TemplateSummary> page(String query, String kind, String afterTime, String afterId, int limit);
    @Select("SELECT template_id AS owner_id,revision,definition_json,sha256,created_at FROM workflow_template_revision WHERE template_id=#{id} AND revision=#{revision}")
    Optional<WorkflowRows.Revision> revision(String id, int revision);
    @Insert("""
        INSERT INTO workflow_template(id,title,description,builtin,archived,head_revision,version,layout_json,layout_version,
        source_template_id,source_revision,created_at,updated_at)
        VALUES(#{id},#{title},#{description},#{builtin},#{archived},#{headRevision},#{version},#{layoutJson},#{layoutVersion},
        #{sourceTemplateId},#{sourceRevision},#{createdAt},#{updatedAt})
        """)
    int insert(WorkflowRows.Template row);
    @Insert("""
        INSERT INTO workflow_template_revision(template_id,revision,definition_json,sha256,created_at)
        VALUES(#{ownerId},#{revision},#{definitionJson},#{sha256},#{createdAt})
        """)
    int insertRevision(WorkflowRows.Revision row);
    @Update("""
        UPDATE workflow_template SET title=#{title},description=#{description},head_revision=#{revision},version=version+1,updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND head_revision=#{revision}-1 AND builtin=0 AND archived=0
        """)
    int revise(String id, long version, int revision, String title, String description, String now);
    @Update("""
        UPDATE workflow_template SET title=#{title},description=#{description},head_revision=#{revision},version=version+1,
        layout_json=#{layout},layout_version=layout_version+1,updated_at=#{now}
        WHERE id=#{id} AND version=#{version} AND head_revision=#{revision}-1 AND builtin=1 AND archived=0
        """)
    int publishBuiltin(String id,long version,int revision,String title,String description,String layout,String now);
    @Update("UPDATE workflow_template SET archived=1,version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND builtin=0 AND archived=0")
    int archive(String id, long version, String now);
    @Update("""
        UPDATE workflow_template SET layout_json=#{body},layout_version=layout_version+1
        WHERE id=#{id} AND layout_version=#{version} AND head_revision=#{revision} AND builtin=0 AND archived=0
        """)
    int layout(String id, int revision, long version, String body);
}
