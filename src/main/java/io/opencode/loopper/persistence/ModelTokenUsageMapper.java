package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

/** Narrow persistence contract for the live, display-only token projection. */
public interface ModelTokenUsageMapper {
    @Insert("""
            INSERT INTO model_token_usage(
              id,designer_session_id,task_id,external_session_id,input_tokens,output_tokens,total_tokens,
              reliable,complete,observed_at)
            VALUES(#{id},#{designerSessionId},#{taskId},#{externalSessionId},#{inputTokens},#{outputTokens},
              #{totalTokens},#{reliable},#{complete},#{observedAt})
            ON CONFLICT DO UPDATE SET
              input_tokens=CASE
                WHEN excluded.input_tokens IS NULL THEN model_token_usage.input_tokens
                WHEN model_token_usage.input_tokens IS NULL THEN excluded.input_tokens
                ELSE MAX(model_token_usage.input_tokens,excluded.input_tokens) END,
              output_tokens=CASE
                WHEN excluded.output_tokens IS NULL THEN model_token_usage.output_tokens
                WHEN model_token_usage.output_tokens IS NULL THEN excluded.output_tokens
                ELSE MAX(model_token_usage.output_tokens,excluded.output_tokens) END,
              total_tokens=CASE
                WHEN excluded.total_tokens IS NULL THEN model_token_usage.total_tokens
                WHEN model_token_usage.total_tokens IS NULL THEN excluded.total_tokens
                ELSE MAX(model_token_usage.total_tokens,excluded.total_tokens) END,
              reliable=MAX(model_token_usage.reliable,excluded.reliable),
              complete=MAX(model_token_usage.complete,excluded.complete),
              observed_at=excluded.observed_at
            WHERE (excluded.input_tokens IS NOT NULL AND
                    (model_token_usage.input_tokens IS NULL OR excluded.input_tokens>model_token_usage.input_tokens))
               OR (excluded.output_tokens IS NOT NULL AND
                    (model_token_usage.output_tokens IS NULL OR excluded.output_tokens>model_token_usage.output_tokens))
               OR (excluded.total_tokens IS NOT NULL AND
                    (model_token_usage.total_tokens IS NULL OR excluded.total_tokens>model_token_usage.total_tokens))
               OR excluded.reliable>model_token_usage.reliable
               OR excluded.complete>model_token_usage.complete
            """)
    int upsertModelTokenUsage(ModelTokenUsageRow row);

    @Select("SELECT * FROM model_token_usage WHERE designer_session_id=#{sessionId} ORDER BY observed_at,id")
    List<ModelTokenUsageRow> listDesignerModelTokenUsage(String sessionId);

    @Select("SELECT * FROM model_token_usage WHERE task_id=#{taskId} ORDER BY observed_at,id")
    List<ModelTokenUsageRow> listTaskModelTokenUsage(String taskId);

    @Select("""
            SELECT external_session_id FROM execution_session
            WHERE task_id=#{taskId} AND external_session_id IS NOT NULL AND state IN ('CREATING','RUNNING')
            UNION
            SELECT external_session_id FROM judge_run
            WHERE task_id=#{taskId} AND external_session_id IS NOT NULL AND state IN ('PENDING','RUNNING')
            """)
    List<String> listActiveTaskModelRemoteIds(String taskId);
}
