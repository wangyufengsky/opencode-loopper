package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReadModelMapper {
    @Select("""
            <script>
            SELECT t.id,t.project_id,p.name AS project_name,t.title,
              substr(COALESCE(d.goal,''),1,240) AS goal_preview,t.branch_name,t.state,
              retry.cause AS retry_cause,retry.due_at AS retry_due_at,
              CASE WHEN d.id IS NULL THEN 0 ELSE 1 END AS has_design_history,
              CASE WHEN archive.task_id IS NULL THEN 0 ELSE 1 END AS archived,
              COALESCE(attempts.attempt_count,0) AS attempt_count,
              COALESCE(CAST(json_extract(d.spec_json,'$.limits.maxTaskAttempts') AS INTEGER),12) AS max_attempts,
              t.created_at,t.updated_at,t.version,t.execution_mode,t.workspace_policy
            FROM task t
            JOIN project p ON p.id=t.project_id
            LEFT JOIN loop_draft d ON d.id=t.loop_draft_id
            LEFT JOIN task_archive archive ON archive.task_id=t.id
            LEFT JOIN (
              SELECT task_id,COUNT(*) AS attempt_count FROM attempt GROUP BY task_id
            ) attempts ON attempts.task_id=t.id
            LEFT JOIN task_retry_schedule retry ON retry.id=(
              SELECT candidate.id FROM task_retry_schedule candidate
              WHERE candidate.task_id=t.id AND candidate.state IN ('SCHEDULED','PAUSED','CLAIMED')
              ORDER BY CASE candidate.state WHEN 'SCHEDULED' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END,
                candidate.updated_at DESC,candidate.id DESC LIMIT 1
            )
            WHERE 1=1
            <if test="projectId != null">AND t.project_id=#{projectId}</if>
            <if test="states != null and !states.isEmpty()">
              AND t.state IN
              <foreach collection="states" item="state" open="(" separator="," close=")">#{state}</foreach>
            </if>
            <choose>
              <when test="archiveMode == 'ACTIVE'">AND archive.task_id IS NULL</when>
              <when test="archiveMode == 'ARCHIVED'">AND archive.task_id IS NOT NULL</when>
            </choose>
            <if test="queryPattern != null">
              AND (lower(t.title) LIKE #{queryPattern} ESCAPE '\\'
                OR lower(COALESCE(d.goal,'')) LIKE #{queryPattern} ESCAPE '\\'
                OR lower(p.name) LIKE #{queryPattern} ESCAPE '\\'
                OR lower(COALESCE(t.branch_name,'')) LIKE #{queryPattern} ESCAPE '\\')
            </if>
            <if test="cursorValue != null">
              <choose>
                <when test="oldest">AND (t.updated_at &gt; #{cursorValue} OR (t.updated_at=#{cursorValue} AND t.id &gt; #{cursorId}))</when>
                <otherwise>AND (t.updated_at &lt; #{cursorValue} OR (t.updated_at=#{cursorValue} AND t.id &lt; #{cursorId}))</otherwise>
              </choose>
            </if>
            <choose>
              <when test="oldest">ORDER BY t.updated_at,t.id</when>
              <otherwise>ORDER BY t.updated_at DESC,t.id DESC</otherwise>
            </choose>
            LIMIT #{limit}
            </script>
            """)
    List<TaskSummaryRow> taskSummaries(
            @Param("projectId") String projectId,
            @Param("states") List<String> states,
            @Param("archiveMode") String archiveMode,
            @Param("queryPattern") String queryPattern,
            @Param("oldest") boolean oldest,
            @Param("cursorValue") String cursorValue,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit);

    @Select("""
            SELECT t.id,t.project_id,p.name AS project_name,t.title,COALESCE(d.goal,'') AS goal,
              t.branch_name,t.worktree_path,t.state,retry.cause AS retry_cause,retry.ordinal AS retry_ordinal,
              retry.created_at AS retry_created_at,retry.due_at AS retry_due_at,retry.delay_seconds AS retry_delay_seconds,
              NULL AS waiting_reason_code,
              CASE WHEN d.id IS NULL THEN 0 ELSE 1 END AS has_design_history,
              CASE WHEN archive.task_id IS NULL THEN 0 ELSE 1 END AS archived,
              cycle.state AS execution_result,cycle.ordinal AS execution_cycle_ordinal,
              checkpoint.state AS checkpoint_state,lineage.parent_task_id,
              (SELECT child.child_task_id FROM task_lineage child WHERE child.parent_task_id=t.id
                AND child.recovery_mode IN ('INHERIT_CHANGES','REWORK_ALL_STAGES')
                ORDER BY child.created_at DESC,child.child_task_id DESC LIMIT 1) AS successor_task_id,
              COALESCE(attempts.attempt_count,0) AS attempt_count,
              COALESCE(CAST(json_extract(d.spec_json,'$.limits.maxTaskAttempts') AS INTEGER),12) AS max_task_attempts,
              COALESCE(CAST(json_extract(d.spec_json,'$.limits.maxStageAttempts') AS INTEGER),3) AS max_stage_attempts,
              t.created_at,t.updated_at,t.version,t.execution_mode,t.workspace_policy
            FROM task t JOIN project p ON p.id=t.project_id LEFT JOIN loop_draft d ON d.id=t.loop_draft_id
            LEFT JOIN task_archive archive ON archive.task_id=t.id
            LEFT JOIN (SELECT task_id,COUNT(*) AS attempt_count FROM attempt GROUP BY task_id) attempts ON attempts.task_id=t.id
            LEFT JOIN task_retry_schedule retry ON retry.id=(
              SELECT candidate.id FROM task_retry_schedule candidate
              WHERE candidate.task_id=t.id AND candidate.state IN ('SCHEDULED','PAUSED','CLAIMED')
              ORDER BY CASE candidate.state WHEN 'SCHEDULED' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END,
                candidate.updated_at DESC,candidate.id DESC LIMIT 1
            )
            LEFT JOIN task_execution_cycle cycle ON cycle.id=(SELECT c.id FROM task_execution_cycle c WHERE c.task_id=t.id ORDER BY c.ordinal DESC LIMIT 1)
            LEFT JOIN task_workspace_checkpoint checkpoint ON checkpoint.id=(SELECT c.id FROM task_workspace_checkpoint c WHERE c.task_id=t.id ORDER BY c.created_at DESC,c.id DESC LIMIT 1)
            LEFT JOIN task_lineage lineage ON lineage.child_task_id=t.id
            WHERE t.id=#{taskId}
            """)
    Optional<TaskOverviewRow> taskOverview(String taskId);

    @Select("""
            SELECT s.id,s.ordinal,s.objective,s.state,s.allowed_paths_json,s.forbidden_paths_json,
              s.deliverables_json,s.verifiers_json,s.created_at,s.updated_at,s.work_package_id,
              COUNT(a.id) AS attempt_count
            FROM stage s LEFT JOIN attempt a ON a.stage_id=s.id
            WHERE s.task_id=#{taskId} GROUP BY s.id ORDER BY s.ordinal
            """)
    List<TaskStageReadRow> taskOverviewStages(String taskId);

    @Select("""
            <script>
            WITH scoped AS (
              SELECT t.id,t.state,CASE WHEN archive.task_id IS NULL THEN 0 ELSE 1 END AS archived
              FROM task t JOIN project p ON p.id=t.project_id
              LEFT JOIN loop_draft d ON d.id=t.loop_draft_id
              LEFT JOIN task_archive archive ON archive.task_id=t.id
              WHERE 1=1
              <if test="projectId != null">AND t.project_id=#{projectId}</if>
              <if test="queryPattern != null">
                AND (lower(t.title) LIKE #{queryPattern} ESCAPE '\\'
                  OR lower(COALESCE(d.goal,'')) LIKE #{queryPattern} ESCAPE '\\'
                  OR lower(p.name) LIKE #{queryPattern} ESCAPE '\\'
                  OR lower(COALESCE(t.branch_name,'')) LIKE #{queryPattern} ESCAPE '\\')
              </if>
            ), base AS (
              SELECT * FROM scoped WHERE 1=1
              <choose>
                <when test="archiveMode == 'ACTIVE'">AND archived=0</when>
                <when test="archiveMode == 'ARCHIVED'">AND archived=1</when>
              </choose>
            )
            SELECT state,COUNT(*) AS count FROM base GROUP BY state
            UNION ALL
            SELECT 'MATCHED_TOTAL' AS state,COUNT(*) AS count FROM base
            <if test="states != null and !states.isEmpty()">
              WHERE state IN
              <foreach collection="states" item="state" open="(" separator="," close=")">#{state}</foreach>
            </if>
            UNION ALL
            SELECT 'ARCHIVED_TOTAL' AS state,COUNT(*) AS count FROM base WHERE archived=1
            </script>
            """)
    List<TaskFacetRow> taskFacets(
            @Param("projectId") String projectId,
            @Param("states") List<String> states,
            @Param("archiveMode") String archiveMode,
            @Param("queryPattern") String queryPattern);

    @Select("""
            SELECT result.id,result.attempt_id,result.verifier_index,result.type,result.state,result.summary,
              json_remove(result.evidence_json,'$.output') AS evidence_summary_json,result.created_at
            FROM verification_result result
            JOIN attempt ON attempt.id=result.attempt_id
            WHERE attempt.task_id=#{taskId}
            ORDER BY attempt.created_at DESC,result.verifier_index
            """)
    List<VerificationSummaryRow> verificationSummaries(String taskId);

    @Select("""
            SELECT a.id,a.task_id,a.stage_id,a.execution_cycle_id,a.ordinal,a.state,a.failure_kind,
              a.summary,a.created_at,a.ended_at,
              (SELECT s.id FROM execution_session s WHERE s.attempt_id=a.id
                ORDER BY s.created_at,s.id LIMIT 1) AS session_id
            FROM attempt a WHERE a.task_id=#{taskId} ORDER BY a.created_at DESC,a.id DESC
            """)
    List<TaskAuditAttemptRow> taskAuditAttempts(String taskId);

    @Select("""
            SELECT 'TASK' AS entry_type,NULL AS payload_json FROM task WHERE id=#{taskId}
            UNION ALL
            SELECT 'ERROR',json_object(
              'id',id,'layer',layer,'code',code,'message',message,'retryable',retryable,
              'stageId',stage_id,'attemptId',attempt_id,'sessionId',session_id,'at',occurred_at)
            FROM error_event WHERE task_id=#{taskId}
            UNION ALL
            SELECT 'JUDGE',json_object(
              'id',id,'role',role,'ordinal',ordinal,'status',state,'verdict',verdict,'reason',reason,
              'externalSessionId',external_session_id,'createdAt',created_at,'endedAt',ended_at,
              'hasRawOutput',CASE WHEN raw_output IS NOT NULL AND raw_output!='' THEN 1 ELSE 0 END)
            FROM judge_run WHERE task_id=#{taskId}
            UNION ALL
            SELECT 'ARTIFACT',json_object(
              'id',id,'kind',kind,'name',name,'contentType',content_type,
              'metadataSummaryJson',CASE WHEN kind='DIFF' THEN metadata_json ELSE '{}' END,
              'contentBytes',length(CAST(content AS BLOB)),'attemptId',attempt_id,
              'judgeRunId',judge_run_id,'createdAt',created_at)
            FROM task_artifact WHERE task_id=#{taskId}
            """)
    List<TaskAuditEntryRow> taskAuditEntries(String taskId);

    @Select("""
            SELECT id,task_id,attempt_id,judge_run_id,kind,name,content_type,
              CASE WHEN kind='DIFF' THEN metadata_json ELSE '{}' END AS metadata_summary_json,
              length(CAST(content AS BLOB)) AS content_bytes,created_at
            FROM task_artifact WHERE task_id=#{taskId} ORDER BY created_at DESC
            """)
    List<TaskArtifactSummaryRow> artifactSummaries(String taskId);

    @Select("""
            SELECT result.* FROM verification_result result
            JOIN attempt ON attempt.id=result.attempt_id
            WHERE result.id=#{id} AND attempt.task_id=#{taskId}
            """)
    Optional<VerificationResultRow> findVerificationForTask(@Param("taskId") String taskId, @Param("id") String id);

    @Select("SELECT * FROM error_event WHERE id=#{id} AND task_id=#{taskId}")
    Optional<ErrorEventRow> findErrorForTask(@Param("taskId") String taskId, @Param("id") String id);

    @Select("SELECT * FROM judge_run WHERE id=#{id} AND task_id=#{taskId}")
    Optional<JudgeRunRow> findJudgeForTask(@Param("taskId") String taskId, @Param("id") String id);

    @Select("SELECT * FROM task_artifact WHERE id=#{id} AND task_id=#{taskId}")
    Optional<TaskArtifactRow> findArtifactForTask(@Param("taskId") String taskId, @Param("id") String id);

    @Select("""
            <script>
            WITH latest AS (
              SELECT s.id,s.project_id,s.loop_draft_id,s.state,s.workflow_phase,s.created_at,s.updated_at,
                s.current_requirement_revision,s.active_work_package_id,
                ROW_NUMBER() OVER (PARTITION BY s.loop_draft_id ORDER BY s.created_at DESC,s.id DESC) AS row_number
              FROM designer_session s
            )
            SELECT latest.id,latest.project_id,p.name AS project_name,latest.state,latest.workflow_phase,
              latest.created_at,latest.updated_at,d.id AS draft_id,d.status AS draft_status,d.goal,
              latest.current_requirement_revision AS requirement_revision,latest.active_work_package_id,
              CASE WHEN archive.designer_session_id IS NULL THEN 0 ELSE 1 END AS archived,
              archive.archived_at,t.id AS task_id,t.state AS task_state
            FROM latest
            JOIN loop_draft d ON d.id=latest.loop_draft_id
            JOIN project p ON p.id=latest.project_id
            LEFT JOIN designer_session_archive archive ON archive.designer_session_id=latest.id
            LEFT JOIN task t ON t.loop_draft_id=d.id
            WHERE latest.row_number=1
              AND (#{projectId} IS NULL OR latest.project_id=#{projectId})
              AND (#{archiveMode}='ALL'
                OR (#{archiveMode}='ACTIVE' AND archive.designer_session_id IS NULL)
                OR (#{archiveMode}='ARCHIVED' AND archive.designer_session_id IS NOT NULL))
              AND (#{queryPattern} IS NULL OR lower(d.goal) LIKE #{queryPattern} ESCAPE '\\'
                OR lower(p.name) LIKE #{queryPattern} ESCAPE '\\')
              <choose>
                <when test="statusMode == 'CONFIRMED'">
                  AND (d.status='CONFIRMED' OR t.id IS NOT NULL)
                </when>
                <when test="statusMode == 'WAITING_INPUT'">
                  AND d.status!='CONFIRMED' AND t.id IS NULL AND latest.state='WAITING_INPUT'
                </when>
                <when test="statusMode == 'FAILED'">
                  AND d.status!='CONFIRMED' AND t.id IS NULL
                  AND (latest.state IN ('SESSION_ERROR','CANCELLED') OR latest.workflow_phase='FAILED')
                </when>
                <when test="statusMode == 'REVIEWING'">
                  AND d.status!='CONFIRMED' AND t.id IS NULL
                  AND (latest.state IN ('REVIEWING','COMPLETED') OR latest.workflow_phase IN ('FINAL_REVIEW','COMPLETED'))
                </when>
                <when test="statusMode == 'PROCESSING'">
                  AND d.status!='CONFIRMED' AND t.id IS NULL
                  AND latest.state NOT IN ('WAITING_INPUT','SESSION_ERROR','CANCELLED','REVIEWING','COMPLETED')
                  AND latest.workflow_phase NOT IN ('FAILED','FINAL_REVIEW','COMPLETED')
                </when>
              </choose>
              <if test="cursorValue != null">
                <choose>
                  <when test="oldest">AND (latest.updated_at &gt; #{cursorValue} OR (latest.updated_at=#{cursorValue} AND latest.id &gt; #{cursorId}))</when>
                  <otherwise>AND (latest.updated_at &lt; #{cursorValue} OR (latest.updated_at=#{cursorValue} AND latest.id &lt; #{cursorId}))</otherwise>
                </choose>
              </if>
            <choose>
              <when test="oldest">ORDER BY latest.updated_at,latest.id</when>
              <otherwise>ORDER BY latest.updated_at DESC,latest.id DESC</otherwise>
            </choose>
            LIMIT #{limit}
            </script>
            """)
    List<DesignerSessionHistoryRow> designerHistoryPage(
            @Param("projectId") String projectId, @Param("statusMode") String statusMode,
            @Param("archiveMode") String archiveMode, @Param("queryPattern") String queryPattern,
            @Param("cursorValue") String cursorValue, @Param("cursorId") String cursorId,
            @Param("oldest") boolean oldest, @Param("limit") int limit);

    @Select("""
            WITH latest AS (
              SELECT s.id,s.project_id,s.loop_draft_id,s.state,s.workflow_phase,
                ROW_NUMBER() OVER (PARTITION BY s.loop_draft_id ORDER BY s.created_at DESC,s.id DESC) AS row_number
              FROM designer_session s
            ), scoped AS (
              SELECT latest.id,latest.state,latest.workflow_phase,d.status AS draft_status,t.id AS task_id,
                CASE WHEN archive.designer_session_id IS NULL THEN 0 ELSE 1 END AS archived
              FROM latest JOIN loop_draft d ON d.id=latest.loop_draft_id
              JOIN project p ON p.id=latest.project_id
              LEFT JOIN designer_session_archive archive ON archive.designer_session_id=latest.id
              LEFT JOIN task t ON t.loop_draft_id=d.id
              WHERE latest.row_number=1
                AND (#{projectId} IS NULL OR latest.project_id=#{projectId})
                AND (#{queryPattern} IS NULL OR lower(d.goal) LIKE #{queryPattern} ESCAPE '\\'
                  OR lower(p.name) LIKE #{queryPattern} ESCAPE '\\')
            ), base AS (
              SELECT * FROM scoped WHERE (#{archiveMode}='ALL'
                OR (#{archiveMode}='ACTIVE' AND archived=0)
                OR (#{archiveMode}='ARCHIVED' AND archived=1))
            )
            SELECT 'CONFIRMED_TOTAL' AS state,COUNT(*) AS count FROM base
              WHERE draft_status='CONFIRMED' OR task_id IS NOT NULL
            UNION ALL SELECT 'RESUMABLE_TOTAL',COUNT(*) FROM base
              WHERE archived=0 AND draft_status!='CONFIRMED' AND task_id IS NULL
                AND state NOT IN ('STOPPING','CANCELLED')
            UNION ALL SELECT 'STOP_RETRY_TOTAL',COUNT(*) FROM base
              WHERE archived=0 AND draft_status!='CONFIRMED' AND task_id IS NULL AND state='STOPPING'
            UNION ALL SELECT 'ARCHIVED_TOTAL',COUNT(*) FROM base WHERE archived=1
            UNION ALL SELECT 'MATCHED_TOTAL',COUNT(*) FROM base
            """)
    List<TaskFacetRow> designerHistoryFacets(
            @Param("projectId") String projectId, @Param("archiveMode") String archiveMode,
            @Param("queryPattern") String queryPattern);

    @Select("""
            WITH task_counts AS (
              SELECT project_id,COUNT(*) AS task_count FROM task GROUP BY project_id
            ), latest_designer AS (
              SELECT s.id,s.project_id,s.loop_draft_id,
                ROW_NUMBER() OVER (PARTITION BY s.loop_draft_id ORDER BY s.created_at DESC,s.id DESC) AS rn
              FROM designer_session s
            ), open_designer_counts AS (
              SELECT latest.project_id,COUNT(*) AS open_count FROM latest_designer latest
              JOIN loop_draft d ON d.id=latest.loop_draft_id
              LEFT JOIN designer_session_archive archive ON archive.designer_session_id=latest.id
              WHERE latest.rn=1 AND d.status!='CONFIRMED' AND archive.designer_session_id IS NULL
              GROUP BY latest.project_id
            )
            SELECT p.id,p.name,p.root_path,p.description,p.updated_at,
              COALESCE(tasks.task_count,0) AS task_count,
              COALESCE(designs.open_count,0) AS open_designer_session_count,
              COALESCE(stack.analysis_state,'UNANALYZED') AS stack_profile_state,
              COALESCE(stack.technology_families_json,'[]') AS stack_technology_families_json,
              COALESCE(stack.component_count,0) AS stack_component_count,
              stack.analyzed_at AS stack_analyzed_at
            FROM project p LEFT JOIN task_counts tasks ON tasks.project_id=p.id
            LEFT JOIN open_designer_counts designs ON designs.project_id=p.id
            LEFT JOIN project_stack_profile stack ON stack.id=(
              SELECT current.id FROM project_stack_profile current
              WHERE current.project_id=p.id ORDER BY current.analyzed_at DESC,current.id DESC LIMIT 1
            )
            WHERE p.managed=1 ORDER BY p.created_at DESC
            """)
    List<ProjectSummaryRow> projectSummaries();

    @Select("SELECT * FROM designer_message WHERE designer_session_id=#{sessionId} AND ordinal &lt; #{beforeOrdinal} ORDER BY ordinal DESC LIMIT #{limit}")
    List<DesignerMessageRow> designerMessagesPage(@Param("sessionId") String sessionId,
                                                   @Param("beforeOrdinal") int beforeOrdinal,
                                                   @Param("limit") int limit);

    @Select("SELECT * FROM automation_run ORDER BY detected_at DESC,id DESC")
    List<AutomationRunRow> allAutomationRuns();

    @Select("SELECT * FROM loopspec_template_version ORDER BY template_id,version_number DESC")
    List<LoopSpecTemplateVersionRow> allTemplateVersions();

}
