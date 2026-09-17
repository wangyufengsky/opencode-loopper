package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Bounded metadata-only projection: no prompts, transcript, candidate output or credentials. */
@Mapper
public interface TemplateSessionDiagnosticMapper {
    String SELECT = """
            SELECT b.id AS batch_id,b.version AS batch_version,b.session_id AS local_session_id,
                s.external_session_id,b.purpose,b.ordinal+1 AS ordinal,b.generation,st.ordinal+1 AS stage_ordinal,
                b.state,t.state AS task_state,a.state AS attempt_state,t.worktree_path,
                json_extract(b.prompt_json,'$.messageId') AS request_message_id,b.created_at,
                c.created_at AS accepted_at,COALESCE((SELECT MAX(submission_revision) FROM template_candidate_submission WHERE batch_id=b.id),0) AS submission_revision,
                o.observed_at,o.last_activity_at,o.last_progress_at,o.remote_state,COALESCE(o.connected,0) AS connected,
                r.action AS recovery_action,r.requested_at AS recovery_requested_at,r.proof AS stop_proof,
                r.proof_at AS stop_confirmed_at,r.error_code AS recovery_error,b.error_code,b.updated_at,
                COALESCE(p.automatic_retries,0) AS automatic_retries,
                COALESCE(p.retry_limit,json_extract(tr.contract_json,'$.batchMaxRetries'),0) AS retry_limit,
                i.operation AS failed_operation,i.error_code AS transport_error,i.error_message AS transport_message,
                i.first_failed_at,i.last_failed_at,i.failures AS transport_failures,i.next_check_at,
                CASE WHEN NOT EXISTS(SELECT 1 FROM template_task_batch n WHERE n.attempt_id=b.attempt_id
                    AND n.purpose=b.purpose AND n.ordinal=b.ordinal AND n.generation>b.generation)
                    AND a.ordinal=(SELECT MAX(n.ordinal) FROM attempt n WHERE n.stage_id=a.stage_id) THEN 1 ELSE 0 END AS current_generation
            FROM template_task_batch b JOIN task t ON t.id=b.task_id JOIN attempt a ON a.id=b.attempt_id
            JOIN template_task_run tr ON tr.task_id=b.task_id
            JOIN stage st ON st.id=a.stage_id LEFT JOIN execution_session s ON s.id=b.session_id
            LEFT JOIN template_candidate_submission c ON c.batch_id=b.id AND c.accepted=1
            LEFT JOIN template_batch_observation o ON o.batch_id=b.id AND o.prompt_sha256=b.prompt_sha256
            LEFT JOIN template_batch_recovery r ON r.batch_id=b.id
            LEFT JOIN template_batch_retry_policy p ON p.batch_id=b.id
            LEFT JOIN template_batch_transport_issue i ON i.batch_id=b.id AND i.resolved_at IS NULL
            WHERE b.task_id=#{taskId}
            """;

    @Select(SELECT + " AND b.id=#{id}")
    Optional<Row> find(String taskId, String id);

    @Select("""
            <script>SELECT * FROM (
            """ + SELECT + """
            ) WHERE current_generation=1
            <if test="filter == 'ACTIVE'">AND state NOT IN ('VALIDATED','FAILED','STOPPED')</if>
            <if test="filter == 'ATTENTION'">AND (state IN ('FAILED','STOPPED') OR
                (state NOT IN ('VALIDATED','FAILED','STOPPED') AND (accepted_at IS NOT NULL OR recovery_action IS NOT NULL OR transport_error IS NOT NULL
                OR (observed_at IS NOT NULL AND (connected=0 OR observed_at &lt; #{disconnectedBefore}
                    OR last_activity_at &lt; #{stalledBefore})))))</if>
            <if test="time != null">AND (created_at &gt; #{time} OR (created_at=#{time} AND batch_id &gt; #{id}))</if>
            ORDER BY created_at,batch_id LIMIT #{limit}</script>
            """)
    List<Row> list(String taskId, String filter, String time, String id, int limit, String disconnectedBefore, String stalledBefore);

    record Row(String batchId, long batchVersion, String localSessionId, String externalSessionId, String purpose,
               int ordinal, int generation, int stageOrdinal, String state, String taskState, String attemptState,
               String worktreePath, String requestMessageId, String createdAt, String acceptedAt, long submissionRevision,
               String observedAt, String lastActivityAt, String lastProgressAt, String remoteState, int connected,
               String recoveryAction, String recoveryRequestedAt, String stopProof, String stopConfirmedAt,
               String recoveryError, String errorCode, String updatedAt, int automaticRetries, int retryLimit,
               String failedOperation, String transportError, String transportMessage, String firstFailedAt,
               String lastFailedAt, Integer transportFailures, String nextCheckAt, int currentGeneration) { }
}
