package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.LoopperApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h",
        "loopper.data-dir=target/judge-candidate-persistence-mapper-test"
})
class JudgeCandidatePersistenceMapperIntegrationTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SHA_D = "d".repeat(64);

    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoopperMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        jdbc.update("""
                INSERT INTO project(id,name,root_path,created_at,updated_at)
                VALUES('project','P','/tmp/judge-project','now','now')
                """);
        jdbc.update("""
                INSERT INTO task(id,project_id,title,state,created_at,updated_at,version)
                VALUES('task','project','Task','JUDGING','now','now',0)
                """);
        jdbc.update("""
                INSERT INTO stage(
                  id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,
                  deliverables_json,verifiers_json,state,created_at,updated_at,version)
                VALUES('stage','task',1,'Implement','[]','[]','[]','[]',
                  'SUCCEEDED','now','now',0)
                """);
        jdbc.update("""
                INSERT INTO task_execution_cycle(
                  id,task_id,ordinal,kind,state,budget_json,authorized_at,started_at,version)
                VALUES('cycle','task',1,'INITIAL','RUNNING','{}','now','now',0)
                """);
        jdbc.update("""
                INSERT INTO attempt(
                  id,task_id,stage_id,execution_cycle_id,ordinal,state,created_at,ended_at,version)
                VALUES('attempt','task','stage','cycle',1,'SUCCEEDED','now','now',0)
                """);
    }

    @Test
    void roundTripsBatchFrozenSourceImmutableResultAndOneWaySettlement() {
        JudgeReviewBatchRow batch = new JudgeReviewBatchRow(
                "batch", "task", "cycle", "attempt", 1, "RUNNING",
                "batch-at", "batch-at", null, 0);
        assertThat(mapper.insertJudgeReviewBatch(batch)).isEqualTo(1);
        assertThat(mapper.findJudgeReviewBatch("batch")).contains(batch);
        assertThat(mapper.listJudgeReviewBatches("task")).containsExactly(batch);

        JudgeRunRow judge = new JudgeRunRow(
                "judge", "task", "attempt", "REQUIREMENT", 1, null, "CREATING",
                null, null, null, "judge-at", null, 0, "INTERNAL_MCP", null,
                "batch", 1L);
        assertThat(mapper.insertJudgeRun(judge)).isEqualTo(1);
        JudgeCandidateSourceSnapshotRow snapshot = new JudgeCandidateSourceSnapshotRow(
                "run", "judge", "task", "cycle", "attempt", "batch", "REQUIREMENT",
                1, 1, 0, "JUDGE_DECISION_V1", "Judge only frozen evidence.", SHA_A,
                "{\"verifications\":[]}", SHA_B, "snapshot-at");
        assertThat(mapper.insertJudgeCandidateSourceSnapshot(snapshot)).isEqualTo(1);
        assertThat(mapper.findJudgeCandidateSourceSnapshot("run")).contains(snapshot);

        createAndSettleLaunch();
        JudgeCandidateAcceptedResultRow accepted = new JudgeCandidateAcceptedResultRow(
                "run", "judge", "batch", "REQUIREMENT", 1, 1, "JUDGE_DECISION_V1",
                "{\"verdict\":\"PASS\",\"reason\":\"ok\"}", SHA_A,
                "{\"contractVersion\":\"JUDGE_DECISION_V1\",\"role\":\"REQUIREMENT\","
                        + "\"verdict\":\"PASS\",\"reason\":\"ok\"}",
                SHA_B, "PASS", "ok", "[]", null,
                "accepted-at", "accepted-at", 0);
        assertThat(mapper.insertJudgeCandidateAcceptedResult(accepted)).isEqualTo(1);
        assertThat(mapper.findJudgeCandidateAcceptedResult("run")).contains(accepted);
        assertThat(mapper.listUnsettledJudgeCandidateAcceptedResults()).containsExactly(accepted);

        finishRunAndOwner();
        assertThat(mapper.settleJudgeCandidateAcceptedResult(
                "run", 0, "judge", "settled-at")).isEqualTo(1);
        assertThat(mapper.settleJudgeCandidateAcceptedResult(
                "run", 0, "judge", "duplicate-at")).isZero();
        assertThat(mapper.findJudgeCandidateAcceptedResult("run"))
                .hasValueSatisfying(result -> {
                    assertThat(result.settledJudgeRunId()).isEqualTo("judge");
                    assertThat(result.updatedAt()).isEqualTo("settled-at");
                    assertThat(result.version()).isEqualTo(1);
                    assertThat(result.canonicalCandidateJson())
                            .isEqualTo(accepted.canonicalCandidateJson());
                });
        assertThat(mapper.listUnsettledJudgeCandidateAcceptedResults()).isEmpty();

        JudgeReviewBatchRow completed = new JudgeReviewBatchRow(
                batch.id(), batch.taskId(), batch.executionCycleId(), batch.finalAttemptId(),
                batch.generation(), "COMPLETED", batch.createdAt(), "ignored-by-mapper", "batch-ended", 0);
        assertThat(mapper.updateJudgeReviewBatch(completed)).isEqualTo(1);
        assertThat(mapper.findJudgeReviewBatch("batch"))
                .contains(new JudgeReviewBatchRow(
                        "batch", "task", "cycle", "attempt", 1, "COMPLETED",
                        "batch-at", "ignored-by-mapper", "batch-ended", 1));
    }

    private void createAndSettleLaunch() {
        jdbc.update("""
                INSERT INTO ai_candidate_internal_launch(
                  id,candidate_run_id,candidate_kind,task_id,owner_type,owner_id,judge_run_id,
                  workflow_step,source_revision,contract_version,max_attempts,state,
                  prepared_owner_version,exact_title,canonical_directory,runtime_generation_id,
                  managed,internal_mcp_server,endpoint_fingerprint,profile,permission_policy_json,
                  permission_policy_digest,create_request_sha256,creation_credential,attestation_type,
                  created_at,updated_at,version)
                VALUES('launch','run','JUDGE_DECISION_V1','task','JUDGE_RUN','judge','judge',
                  'JUDGE_DECISION_V1',1,'JUDGE_DECISION_V1',2,'PREPARED',0,'judge-launch',
                  '/tmp/judge-project','generation-1',1,'loopper_internal_generic',?,
                  'JUDGE_CANDIDATE_READ_ONLY','[]',?,?,?,
                  'LOCAL_REQUEST_ATTESTED','now','now',0)
                """, SHA_C, SHA_D, SHA_A, "C".repeat(43));
        jdbc.update("""
                INSERT INTO open_code_session_runtime_binding(
                  external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                  internal_mcp_server,created_at)
                VALUES('remote','generation-1','MANAGED',?,'loopper_internal_generic','now')
                """, SHA_C);
        jdbc.update("""
                UPDATE ai_candidate_internal_launch
                SET state='CREATED',create_claim_owner='worker',create_claim_token='claim',
                  create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                  create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                  external_session_id='remote',external_attested_at='attested-at',
                  updated_at='created-at',version=version+1 WHERE id='launch'
                """);
        jdbc.update("""
                UPDATE judge_run
                SET external_session_id='remote',state='RUNNING',version=version+1 WHERE id='judge'
                """);
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO ai_candidate_submission_run(
                      id,task_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,
                      external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('run','task','JUDGE_RUN','judge','JUDGE_DECISION_V1',
                      'JUDGE_DECISION_V1',1,1,'INTERNAL_MCP','JUDGE_DECISION_V1',
                      'generation-1','remote','OPEN',2,0,'now','now',0)
                    """);
            jdbc.update("""
                    UPDATE ai_candidate_internal_launch
                    SET state='SETTLED',settled_owner_version=1,settled_at='settled-at',
                      create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
                      updated_at='settled-at',version=version+1 WHERE id='launch'
                    """);
        });
    }

    private void finishRunAndOwner() {
        jdbc.update("""
                INSERT INTO ai_candidate_submission_attempt(
                  id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                  problems_json,response_json,canonical_result_sha256,created_at)
                VALUES('candidate-attempt','run',1,'key-1',?,'ACCEPTED',0,'[]','{}',?,'now')
                """, SHA_D, SHA_B);
        jdbc.update("""
                UPDATE ai_candidate_submission_run
                SET state='ACCEPTED',attempts_used=1,terminal_attempt_id='candidate-attempt',
                  updated_at='accepted',version=version+1 WHERE id='run'
                """);
        jdbc.update("""
                INSERT INTO ai_candidate_internal_termination_intent(
                  id,launch_id,candidate_run_id,intent_kind,target_launch_state,state,
                  anchor_owner_version,created_at,updated_at,version)
                VALUES('termination','launch','run','RUN_COMPLETED','COMPLETED','REQUESTED',
                  1,'now','now',0)
                """);
        jdbc.update("""
                UPDATE ai_candidate_internal_launch
                SET state='COMPLETED',termination_proof='REMOTE_COMPLETED',proof_at='proof-at',
                  failure_phase='REMOTE_STOP',updated_at='proof-at',version=version+1
                WHERE id='launch'
                """);
        jdbc.update("""
                UPDATE ai_candidate_internal_termination_intent
                SET state='READY',ready_at='ready-at',updated_at='ready-at',version=version+1
                WHERE id='termination'
                """);
        jdbc.update("""
                UPDATE judge_run
                SET state='COMPLETED',verdict='PASS',reason='ok',raw_output='{}',
                  ended_at='ended',version=version+1 WHERE id='judge'
                """);
    }
}
