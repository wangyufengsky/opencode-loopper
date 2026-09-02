package io.opencode.loopper.persistence;

/** Shared filtered facts keep page rows, token totals and currency totals in the same scope. */
final class InsightSql {
    private InsightSql() { }
    static final String FILTERED = """
            <script>
            WITH execution_attempt AS (
              SELECT * FROM attempt WHERE COALESCE(failure_kind,'')!='SESSION_FORK_SNAPSHOT'
            ), attempt_stats AS (
              SELECT task_id,COUNT(*) AS attempt_count,COUNT(DISTINCT stage_id) AS attempted_stage_count
              FROM execution_attempt GROUP BY task_id
            ), latest_attempt AS (
              SELECT id,task_id,stage_id,ROW_NUMBER() OVER (PARTITION BY task_id,stage_id ORDER BY ordinal DESC,id DESC) AS rn
              FROM execution_attempt
            ), verification_stats AS (
              SELECT la.task_id,COUNT(v.id) AS verification_count,
                SUM(CASE WHEN v.state='PASS' THEN 1 ELSE 0 END) AS verification_passed_count
              FROM latest_attempt la LEFT JOIN verification_result v ON v.attempt_id=la.id
              WHERE la.rn=1 GROUP BY la.task_id
            ), judge_ranked AS (
              SELECT j.*,ROW_NUMBER() OVER (PARTITION BY task_id,role ORDER BY ordinal DESC,id DESC) AS rn
              FROM judge_run j
            ), judge_stats AS (
              SELECT task_id,COUNT(*) AS judge_count,COUNT(DISTINCT role) AS judged_role_count,
                MAX(CASE WHEN rn=1 AND role='REQUIREMENT' AND verdict='PASS' THEN 1 ELSE 0 END) AS requirement_judge_passed,
                MAX(CASE WHEN rn=1 AND role='RISK' AND verdict='PASS' THEN 1 ELSE 0 END) AS risk_judge_passed
              FROM judge_ranked GROUP BY task_id
            ), usage_stats AS (
              SELECT task_id,
                SUM(CASE WHEN reliable=1 THEN input_tokens END) AS input_tokens,
                SUM(CASE WHEN reliable=1 THEN output_tokens END) AS output_tokens,
                SUM(CASE WHEN reliable=1 THEN total_tokens END) AS total_tokens,
                SUM(CASE WHEN reliable=0 THEN 1 ELSE 0 END) AS unknown_usage_count
              FROM session_usage GROUP BY task_id
            ), facts AS (
            SELECT t.id,t.project_id,t.title,t.state,t.created_at,t.updated_at,
              CASE WHEN archive.task_id IS NULL THEN 0 ELSE 1 END AS archived,
              CASE WHEN human.cycle_id IS NULL THEN 0 ELSE 1 END AS human_approved,
              COALESCE(a.attempt_count,0) AS attempt_count,COALESCE(a.attempted_stage_count,0) AS attempted_stage_count,
              COALESCE(j.judge_count,0) AS judge_count,COALESCE(j.judged_role_count,0) AS judged_role_count,
              COALESCE(v.verification_count,0) AS verification_count,
              COALESCE(v.verification_passed_count,0) AS verification_passed_count,
              COALESCE(j.requirement_judge_passed,0) AS requirement_judge_passed,
              COALESCE(j.risk_judge_passed,0) AS risk_judge_passed,
              u.input_tokens,u.output_tokens,u.total_tokens,COALESCE(u.unknown_usage_count,0) AS unknown_usage_count
            FROM task t LEFT JOIN attempt_stats a ON a.task_id=t.id
            LEFT JOIN verification_stats v ON v.task_id=t.id LEFT JOIN judge_stats j ON j.task_id=t.id
            LEFT JOIN usage_stats u ON u.task_id=t.id
            LEFT JOIN task_archive archive ON archive.task_id=t.id
            LEFT JOIN task_judge_approval human ON human.cycle_id=(SELECT c.id FROM task_execution_cycle c WHERE c.task_id=t.id ORDER BY c.ordinal DESC LIMIT 1)
            ), matched AS (
              SELECT *, CASE WHEN verification_count=0 OR verification_passed_count!=verification_count THEN 'PENDING'
                WHEN (requirement_judge_passed=1 AND risk_judge_passed=1) OR human_approved=1 THEN 'PASS'
                ELSE 'REVIEW_REQUIRED' END AS quality_state FROM facts
            ), filtered AS (
              SELECT * FROM matched WHERE 1=1
              <if test="filter.projectId != null">AND project_id=#{filter.projectId}</if>
              <if test="filter.state != null">AND state=#{filter.state}</if>
              <if test="filter.quality != null">AND quality_state=#{filter.quality}</if>
              <if test="filter.query != null">AND instr(lower(title),lower(#{filter.query}))>0</if>
              <if test="filter.archive == 'ACTIVE'">AND archived=0</if>
              <if test="filter.archive == 'ARCHIVED'">AND archived=1</if>
            )
            """;
}
