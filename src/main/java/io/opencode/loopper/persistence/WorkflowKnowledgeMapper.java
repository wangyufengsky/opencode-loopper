package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Immutable source authorization follows workflow ownership, never a model-supplied project. */
@Mapper
public interface WorkflowKnowledgeMapper {
    record Binding(String ownerType, String ownerId, String projectId, String sourcesJson, String frozenAt) { }
    @Select("SELECT * FROM workflow_knowledge_binding WHERE owner_type=#{type} AND owner_id=#{id}")
    Binding binding(String type, String id);
    @Insert("INSERT INTO workflow_knowledge_binding VALUES(#{ownerType},#{ownerId},#{projectId},#{sourcesJson},#{frozenAt})")
    int insert(Binding row);
    @Select("""
        SELECT project_id FROM (
          SELECT project_id FROM task WHERE #{type}='TASK' AND id=#{id}
          UNION ALL SELECT project_id FROM designer_session WHERE #{type}='DESIGNER_SESSION' AND id=#{id}
          UNION ALL SELECT project_id FROM loop_draft WHERE #{type}='LOOP_DRAFT' AND id=#{id}
          UNION ALL SELECT project_id FROM document_template_run WHERE #{type}='DOCUMENT_TEMPLATE_RUN' AND id=#{id}
          UNION ALL SELECT project_id FROM source_template_run WHERE #{type}='SOURCE_TEMPLATE_RUN' AND id=#{id}
          UNION ALL SELECT project_id FROM project_convention_draft WHERE #{type}='PROJECT_CONVENTION_DRAFT' AND id=#{id}
        ) LIMIT 1
        """)
    String project(String type, String id);
    @Select("SELECT * FROM knowledge_source WHERE project_id=#{project} AND state='READY' ORDER BY created_at,id LIMIT 101")
    List<KnowledgeRows.Source> sources(String project);
    @Select("SELECT id FROM ai_candidate_submission_run WHERE external_session_id=#{session} AND state='OPEN' ORDER BY created_at DESC LIMIT 1")
    String candidate(String session);
    @Select("SELECT project_id FROM project_convention_draft WHERE id=#{owner} AND external_session_id=#{session} AND state='RUNNING'")
    String convention(String owner, String session);
    @Select("SELECT result_json FROM assist_call WHERE id=#{id} AND external_session_id=#{session} AND state='SUCCEEDED' AND tool_name LIKE '%knowledge%'")
    String evidence(String session, String id);
    record Evidence(String id, String toolName, String createdAt) { }
    @Select("SELECT id,tool_name,created_at FROM assist_call WHERE external_session_id=#{session} AND state='SUCCEEDED' AND tool_name LIKE '%knowledge%' AND tool_name NOT IN ('list_knowledge_evidence','read_knowledge_evidence') AND (#{before}='' OR created_at < #{before} OR (created_at=#{before} AND id < #{id})) ORDER BY created_at DESC,id DESC LIMIT 51")
    List<Evidence> evidencePage(String session, String before, String id);
    @Select("SELECT source_json FROM workflow_knowledge_git WHERE external_session_id=#{session}")
    String git(String session);
    @Insert("INSERT OR IGNORE INTO workflow_knowledge_git VALUES(#{session},#{source})")
    int freezeGit(String session, String source);
}
