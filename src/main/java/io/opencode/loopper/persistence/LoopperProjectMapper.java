package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Domain-focused persistence contract composed by {@link LoopperMapper}. */
public interface LoopperProjectMapper {
    @Insert("INSERT INTO project(id,name,root_path,description,created_at,updated_at,managed,version) VALUES(#{id},#{name},#{rootPath},#{description},#{createdAt},#{updatedAt},#{managed},#{version})")
    int insertProject(ProjectRow row);
    @Select("SELECT * FROM project WHERE id=#{id}") Optional<ProjectRow> findProject(String id);
    @Select("SELECT * FROM project WHERE root_path=#{rootPath}") Optional<ProjectRow> findProjectByRoot(String rootPath);
    @Select("SELECT * FROM project WHERE managed=1 ORDER BY created_at DESC") List<ProjectRow> listProjects();
    @Select("SELECT COUNT(*) FROM task WHERE project_id=#{projectId}") int countTasksForProject(String projectId);
    @Select("""
            SELECT COUNT(*) FROM designer_session s
            JOIN loop_draft d ON d.id=s.loop_draft_id
            WHERE s.project_id=#{projectId}
              AND d.status<>'CONFIRMED'
              AND NOT EXISTS (
                SELECT 1 FROM designer_session_archive archive
                WHERE archive.designer_session_id=s.id
              )
              AND s.id=(
                SELECT latest.id FROM designer_session latest
                WHERE latest.loop_draft_id=s.loop_draft_id
                ORDER BY latest.created_at DESC, latest.id DESC
                LIMIT 1
              )
            """)
    int countOpenDesignerSessionsForProject(String projectId);
    @Update("UPDATE project SET name=#{name}, description=#{description}, updated_at=#{updatedAt}, managed=#{managed}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateProject(ProjectRow row);
    @Update("UPDATE project SET managed=0, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND managed=1")
    int unmanageProject(@Param("id") String id, @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO project_stack_profile(
              id,project_id,analysis_state,manifest_fingerprint,technology_families_json,
              technologies_json,evidence_json,files_scanned,component_count,error_code,error_detail,
              analyzed_at,created_at)
            VALUES(#{id},#{projectId},#{analysisState},#{manifestFingerprint},#{technologyFamiliesJson},
              #{technologiesJson},#{evidenceJson},#{filesScanned},#{componentCount},#{errorCode},#{errorDetail},
              #{analyzedAt},#{createdAt})
            """)
    int insertProjectStackProfile(ProjectStackProfileRow row);
    @Insert("""
            INSERT INTO project_stack_component(
              profile_id,component_key,relative_root,technology_families_json,technologies_json,
              build_tools_json,test_frameworks_json,manifest_sources_json,evidence_json)
            VALUES(#{profileId},#{componentKey},#{relativeRoot},#{technologyFamiliesJson},#{technologiesJson},
              #{buildToolsJson},#{testFrameworksJson},#{manifestSourcesJson},#{evidenceJson})
            """)
    int insertProjectStackComponent(ProjectStackComponentRow row);
    @Select("SELECT * FROM project_stack_profile WHERE id=#{id}")
    Optional<ProjectStackProfileRow> findProjectStackProfile(String id);
    @Select("""
            SELECT * FROM project_stack_profile WHERE project_id=#{projectId}
            ORDER BY analyzed_at DESC,id DESC LIMIT 1
            """)
    Optional<ProjectStackProfileRow> findCurrentProjectStackProfile(String projectId);
    @Select("SELECT * FROM project_stack_component WHERE profile_id=#{profileId} ORDER BY relative_root,component_key")
    List<ProjectStackComponentRow> listProjectStackComponents(String profileId);

    @Insert("""
            INSERT INTO project_convention_draft(
              id,project_id,state,external_session_id,external_session_state,
              source_exists,source_sha256,source_content,proposed_content,normalization_notice,error_message,
              created_at,updated_at,version,project_stack_profile_id,stack_fingerprint)
            VALUES(#{id},#{projectId},#{state},#{externalSessionId},#{externalSessionState},
              #{sourceExists},#{sourceSha256},#{sourceContent},#{proposedContent},#{normalizationNotice},#{errorMessage},
              #{createdAt},#{updatedAt},#{version},#{projectStackProfileId},#{stackFingerprint})
            """)
    int insertProjectConventionDraft(ProjectConventionDraftRow row);
    @Select("SELECT * FROM project_convention_draft WHERE id=#{id}")
    Optional<ProjectConventionDraftRow> findProjectConventionDraft(String id);
    @Select("""
            SELECT * FROM project_convention_draft
            WHERE project_id=#{projectId} AND state IN ('RUNNING','APPLYING')
            ORDER BY created_at DESC LIMIT 1
            """)
    Optional<ProjectConventionDraftRow> activeProjectConventionDraft(String projectId);
    @Select("""
            SELECT * FROM project_convention_draft
            WHERE (state='RUNNING' AND external_session_id IS NOT NULL) OR state='APPLYING'
            ORDER BY updated_at
            """)
    List<ProjectConventionDraftRow> activeProjectConventionDrafts();
    @Update("""
            UPDATE project_convention_draft SET
              state=#{state}, external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState},
              proposed_content=#{proposedContent}, normalization_notice=#{normalizationNotice},
              error_message=#{errorMessage}, updated_at=#{updatedAt}, version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateProjectConventionDraft(ProjectConventionDraftRow row);
    @Update("""
            UPDATE project_convention_draft SET
              external_session_id=#{externalSessionId}, external_session_state=#{externalSessionState},
              proposed_content=#{proposedContent}, normalization_notice=#{normalizationNotice},
              error_message=#{errorMessage}, updated_at=#{updatedAt}, version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateProjectConventionProjection(ProjectConventionDraftRow row);

    @Insert("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at,version) VALUES(#{id},#{projectId},#{goal},#{specJson},#{status},#{createdAt},#{updatedAt},#{version})")
    int insertDraft(LoopDraftRow row);
    @Select("SELECT * FROM loop_draft WHERE id=#{id}") Optional<LoopDraftRow> findDraft(String id);
    @Update("UPDATE loop_draft SET goal=#{goal}, spec_json=#{specJson}, status=#{status}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDraft(LoopDraftRow row);
    @Update("UPDATE loop_draft SET goal=#{goal}, spec_json=#{specJson}, updated_at=#{updatedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateDraftContent(LoopDraftRow row);
    @Delete("DELETE FROM loop_draft WHERE id=#{id}")
    int deleteDraft(String id);

    @Delete("DELETE FROM loop_spec_compilation WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteLoopSpecCompilationsByDraft(String draftId);
    @Delete("DELETE FROM design_discussion_revision WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignDiscussionRevisionsByDraft(String draftId);
    @Delete("DELETE FROM design_work_package WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignWorkPackagesByDraft(String draftId);
    @Delete("DELETE FROM task_decomposition WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteTaskDecompositionsByDraft(String draftId);
    @Delete("DELETE FROM design_requirement_revision WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignRequirementRevisionsByDraft(String draftId);
    @Delete("DELETE FROM designer_message WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignerMessagesByDraft(String draftId);
    @Delete("DELETE FROM interaction WHERE designer_session_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignerInteractionsByDraft(String draftId);
    @Update("UPDATE automation_run SET draft_id=NULL WHERE draft_id=#{draftId}")
    int detachAutomationRunsFromDraft(String draftId);


}
