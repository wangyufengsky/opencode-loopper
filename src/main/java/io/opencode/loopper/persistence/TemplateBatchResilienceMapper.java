package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Durable retry budgets and transport diagnostics, independent of business lifecycle states. */
@Mapper
public interface TemplateBatchResilienceMapper {
    @Select("""
            SELECT b.id AS batch_id,COALESCE(p.automatic_retries,0) AS automatic_retries,
                COALESCE(p.retry_limit,json_extract(r.contract_json,'$.batchMaxRetries'),0) AS retry_limit
            FROM template_task_batch b JOIN template_task_run r ON r.task_id=b.task_id
            LEFT JOIN template_batch_retry_policy p ON p.batch_id=b.id WHERE b.id=#{id}
            """)
    Optional<RetryPolicy> retryPolicy(String id);
    @Insert("INSERT INTO template_batch_retry_policy VALUES(#{batchId},#{automaticRetries},#{retryLimit}) ON CONFLICT(batch_id) DO NOTHING")
    int insertRetryPolicy(RetryPolicy row);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM template_batch_transport_issue WHERE batch_id=#{id}")
    Optional<Issue> issue(String id);
    @Insert("""
            INSERT INTO template_batch_transport_issue VALUES(#{batchId},#{sessionId},#{promptSha256},#{operation},
                #{errorCode},#{errorMessage},#{firstFailedAt},#{lastFailedAt},#{failures},#{nextCheckAt},#{blocksDispatch},NULL)
            ON CONFLICT(batch_id) DO UPDATE SET session_id=excluded.session_id,prompt_sha256=excluded.prompt_sha256,
                operation=excluded.operation,error_code=excluded.error_code,error_message=excluded.error_message,
                first_failed_at=excluded.first_failed_at,last_failed_at=excluded.last_failed_at,failures=excluded.failures,
                next_check_at=excluded.next_check_at,blocks_dispatch=excluded.blocks_dispatch,resolved_at=NULL
            """)
    int saveIssue(Issue row);
    @Update("UPDATE template_batch_transport_issue SET resolved_at=#{at} WHERE batch_id=#{id} AND last_failed_at=#{expected} AND resolved_at IS NULL")
    int resolve(String id, String expected, String at);
    @Update("UPDATE template_batch_transport_issue SET next_check_at=#{at} WHERE batch_id=#{id} AND resolved_at IS NULL")
    int expedite(String id, String at);
    @Select("""
            SELECT EXISTS(SELECT 1 FROM template_batch_transport_issue i JOIN template_task_batch b ON b.id=i.batch_id
                WHERE b.task_id=#{taskId} AND b.id!=COALESCE(#{exceptBatch},'') AND i.resolved_at IS NULL AND i.blocks_dispatch=1
                  AND b.state NOT IN ('VALIDATED','FAILED','STOPPED'))
            """)
    boolean dispatchBlocked(String taskId, String exceptBatch);
    @Delete("DELETE FROM template_batch_transport_issue WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{id})")
    int deleteIssues(String id);
    @Delete("DELETE FROM template_batch_retry_policy WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{id})")
    int deletePolicies(String id);

    record RetryPolicy(String batchId, int automaticRetries, int retryLimit) { }
    record Issue(String batchId, String sessionId, String promptSha256, String operation, String errorCode,
                 String errorMessage, String firstFailedAt, String lastFailedAt, int failures, String nextCheckAt,
                 int blocksDispatch, String resolvedAt) { }
}
