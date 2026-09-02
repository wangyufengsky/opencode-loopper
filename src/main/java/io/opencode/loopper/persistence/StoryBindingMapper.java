package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface StoryBindingMapper {
    @Insert("INSERT INTO story_binding(id,system_code,story_code,next_session_ordinal,created_at) VALUES(#{id},#{systemCode},#{storyCode},#{nextSessionOrdinal},#{createdAt})")
    int insertStoryBinding(StoryBindingRow row);

    @Insert("INSERT INTO designer_story_binding(designer_session_id,binding_id) VALUES(#{designerSessionId},#{bindingId})")
    int bindDesignerStory(@Param("designerSessionId") String designerSessionId,
                          @Param("bindingId") String bindingId);

    @Insert("""
            INSERT OR IGNORE INTO task_story_binding(task_id,binding_id)
            SELECT #{taskId},link.binding_id FROM designer_session session
            JOIN designer_story_binding link ON link.designer_session_id=session.id
            WHERE session.loop_draft_id=#{draftId}
            ORDER BY session.created_at DESC,session.id DESC LIMIT 1
            """)
    int inheritStoryBindingForTask(@Param("taskId") String taskId, @Param("draftId") String draftId);

    @Insert("""
            INSERT OR IGNORE INTO task_story_binding(task_id,binding_id)
            SELECT #{childTaskId},binding_id FROM task_story_binding WHERE task_id=#{parentTaskId}
            """)
    int inheritRecoveryStoryBinding(@Param("childTaskId") String childTaskId,
                                     @Param("parentTaskId") String parentTaskId);

    @Select("""
            SELECT binding.* FROM story_binding binding
            JOIN designer_story_binding link ON link.binding_id=binding.id
            WHERE link.designer_session_id=#{sessionId}
            """)
    Optional<StoryBindingRow> findDesignerStoryBinding(String sessionId);

    @Select("""
            SELECT binding.* FROM story_binding binding
            JOIN task_story_binding link ON link.binding_id=binding.id
            WHERE link.task_id=#{taskId}
            """)
    Optional<StoryBindingRow> findTaskStoryBinding(String taskId);

    @Select("""
            WITH scoped AS (
              SELECT usage.designer_session_id,usage.task_id,
                CASE
                  WHEN EXISTS(SELECT 1 FROM task_profile_router_run r WHERE r.external_session_id=usage.external_session_id) THEN 'ROUTER'
                  WHEN EXISTS(SELECT 1 FROM task_decomposition r WHERE r.external_session_id=usage.external_session_id) THEN 'DECOMPOSER'
                  WHEN EXISTS(SELECT 1 FROM loop_spec_compilation r WHERE r.external_session_id=usage.external_session_id) THEN 'COMPILER'
                  WHEN EXISTS(SELECT 1 FROM analysis_report r WHERE r.external_session_id=usage.external_session_id) THEN 'REVIEWER'
                  WHEN EXISTS(SELECT 1 FROM judge_run r WHERE r.external_session_id=usage.external_session_id) THEN 'JUDGE'
                  WHEN EXISTS(SELECT 1 FROM execution_session r WHERE r.external_session_id=usage.external_session_id) THEN 'IMPLEMENTATION'
                  WHEN EXISTS(SELECT 1 FROM design_work_package r WHERE r.designer_external_session_id=usage.external_session_id) THEN 'PACKAGE_DESIGNER'
                  ELSE 'REQUIREMENT_DESIGNER'
                END AS role
              FROM model_token_usage usage WHERE usage.external_session_id=#{externalSessionId}
              UNION ALL
              SELECT run.designer_session_id,run.task_id,run.candidate_kind
              FROM ai_candidate_submission_run run
              WHERE run.external_session_id=#{externalSessionId}
                AND NOT EXISTS(SELECT 1 FROM model_token_usage usage WHERE usage.external_session_id=#{externalSessionId})
              UNION ALL
              SELECT run.designer_session_id,run.task_id,run.candidate_kind
              FROM ai_candidate_internal_launch launch
              JOIN ai_candidate_submission_run run ON run.id=launch.candidate_run_id
              WHERE launch.external_session_id=#{externalSessionId}
                AND NOT EXISTS(SELECT 1 FROM model_token_usage usage WHERE usage.external_session_id=#{externalSessionId})
                AND NOT EXISTS(SELECT 1 FROM ai_candidate_submission_run run2 WHERE run2.external_session_id=#{externalSessionId})
              UNION ALL
              SELECT NULL,revision.task_id,'ROLLING_PACKAGE_PLAN_V1'
              FROM task_package_plan_revision revision WHERE revision.external_session_id=#{externalSessionId}
                AND NOT EXISTS(SELECT 1 FROM ai_candidate_submission_run run WHERE run.external_session_id=#{externalSessionId})
            )
            SELECT binding.id AS binding_id,binding.system_code,binding.story_code,
              scoped.designer_session_id,scoped.task_id,scoped.role,
              CASE WHEN scoped.role IN ('REQUIREMENT_DESIGNER','PACKAGE_DESIGNER','ROLLING_PACKAGE_PLAN_V1') THEN 1 ELSE 0 END AS reusable
            FROM scoped
            LEFT JOIN designer_story_binding designer_link ON designer_link.designer_session_id=scoped.designer_session_id
            LEFT JOIN task_story_binding task_link ON task_link.task_id=scoped.task_id
            JOIN story_binding binding ON binding.id=COALESCE(designer_link.binding_id,task_link.binding_id)
            LIMIT 1
            """)
    Optional<StoryAccountingOwnerRow> findStoryAccountingOwner(String externalSessionId);

    @Update("UPDATE story_binding SET next_session_ordinal=next_session_ordinal+1 WHERE id=#{bindingId}")
    int incrementStorySessionOrdinal(String bindingId);
    @Select("SELECT next_session_ordinal FROM story_binding WHERE id=#{bindingId}")
    int currentStorySessionOrdinal(String bindingId);

    @Insert("""
            INSERT INTO story_accounting_session(id,binding_id,designer_session_id,task_id,
              external_session_id,runtime_generation_id,worktree_path,role,ordinal,bind_operation,owner_observed,state,plugin_run_id,
              created_at,updated_at)
            VALUES(#{id},#{bindingId},#{designerSessionId},#{taskId},#{externalSessionId},
              #{runtimeGenerationId},#{worktreePath},#{role},#{ordinal},#{bindOperation},#{ownerObserved},#{state},#{pluginRunId},
              #{createdAt},#{updatedAt})
            """)
    int insertStoryAccountingSession(StoryAccountingSessionRow row);

    @Select("SELECT * FROM story_accounting_session WHERE external_session_id=#{externalSessionId}")
    Optional<StoryAccountingSessionRow> findStoryAccountingSession(String externalSessionId);
    @Update("UPDATE story_accounting_session SET owner_observed=1 WHERE external_session_id=#{externalSessionId}")
    int markStoryAccountingOwnerObserved(String externalSessionId);
    @Update("UPDATE story_accounting_session SET state=#{state},plugin_run_id=#{pluginRunId},updated_at=#{updatedAt} WHERE id=#{id}")
    int updateStoryAccountingSession(StoryAccountingSessionRow row);

    @Insert("""
            INSERT INTO story_accounting_call(id,accounting_session_id,phase,message_id,operation,
              arguments_text,state,plugin_run_id,result_text,error_code,error_detail,notification_emitted,
              started_at,finished_at)
            VALUES(#{id},#{accountingSessionId},#{phase},#{messageId},#{operation},#{argumentsText},
              #{state},#{pluginRunId},#{resultText},#{errorCode},#{errorDetail},#{notificationEmitted},
              #{startedAt},#{finishedAt})
            """)
    int insertStoryAccountingCall(StoryAccountingCallRow row);

    @Select("SELECT * FROM story_accounting_call WHERE accounting_session_id=#{sessionId} AND phase=#{phase}")
    Optional<StoryAccountingCallRow> findStoryAccountingCall(@Param("sessionId") String sessionId,
                                                              @Param("phase") String phase);
    @Update("""
            UPDATE story_accounting_call SET state=#{state},plugin_run_id=#{pluginRunId},result_text=#{resultText},
              error_code=#{errorCode},error_detail=#{errorDetail},notification_emitted=#{notificationEmitted},
              finished_at=#{finishedAt} WHERE id=#{id} AND state='PREPARED'
            """)
    int finishStoryAccountingCall(StoryAccountingCallRow row);

    @Select("SELECT message_id FROM story_accounting_call WHERE accounting_session_id IN (SELECT id FROM story_accounting_session WHERE external_session_id=#{externalSessionId})")
    List<String> listStoryAccountingMessageIds(String externalSessionId);

    @Select("SELECT EXISTS(SELECT 1 FROM story_accounting_active_remote WHERE external_session_id=#{externalSessionId})")
    boolean storyAccountingOwnerActive(String externalSessionId);

    @Select("""
            SELECT session.* FROM story_accounting_session session
            WHERE session.state IN ('ACTIVE','BIND_FAILED') AND session.owner_observed=1
              AND NOT EXISTS(SELECT 1 FROM story_accounting_active_remote active
                WHERE active.external_session_id=session.external_session_id)
            ORDER BY session.created_at,session.id LIMIT 32
            """)
    List<StoryAccountingSessionRow> listRetiredStoryAccountingSessions();

    @Select("SELECT * FROM story_accounting_call WHERE state='PREPARED' AND started_at < #{startupAt}")
    List<StoryAccountingCallRow> listInterruptedStoryAccountingCalls(String startupAt);
    @Select("SELECT * FROM story_accounting_session WHERE id=#{id}")
    Optional<StoryAccountingSessionRow> findStoryAccountingSessionById(String id);
}
