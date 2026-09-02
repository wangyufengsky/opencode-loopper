package io.opencode.loopper.persistence;

import io.opencode.loopper.domain.InsightFilter;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InsightPageMapper {
    @Select(InsightSql.FILTERED + """
            SELECT id,title,state,created_at,updated_at,attempt_count,attempted_stage_count,judge_count,judged_role_count,
              verification_count,verification_passed_count,requirement_judge_passed,risk_judge_passed,
              input_tokens,output_tokens,total_tokens,unknown_usage_count,human_approved
            FROM filtered
            <if test="cursorValue != null">WHERE updated_at &lt; #{cursorValue}
              OR (updated_at=#{cursorValue} AND id &lt; #{cursorId})</if>
            ORDER BY updated_at DESC,id DESC LIMIT #{limit}
            </script>
            """)
    List<TaskInsightRow> page(@Param("filter") InsightFilter filter, @Param("cursorValue") String cursorValue,
                             @Param("cursorId") String cursorId, @Param("limit") int limit);

    @Select(InsightSql.FILTERED + """
            SELECT SUM(input_tokens) AS input_tokens,SUM(output_tokens) AS output_tokens,
              SUM(total_tokens) AS total_tokens,COALESCE(SUM(unknown_usage_count),0) AS unknown_usage_count FROM filtered
            </script>
            """)
    GlobalUsageRow usage(@Param("filter") InsightFilter filter);

    @Select(InsightSql.FILTERED + """
            SELECT u.task_id,u.currency,CAST(SUM(CAST(u.cost_amount AS NUMERIC)) AS TEXT) AS amount
            FROM session_usage u JOIN filtered f ON f.id=u.task_id
            WHERE u.reliable=1 AND u.cost_amount IS NOT NULL AND u.currency IS NOT NULL
            <if test="taskIds != null">AND u.task_id IN
              <foreach collection="taskIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            GROUP BY u.task_id,u.currency ORDER BY u.task_id,u.currency
            </script>
            """)
    List<UsageCostRow> costs(@Param("filter") InsightFilter filter, @Param("taskIds") List<String> taskIds);
}
