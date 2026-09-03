package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JudgeCandidatePersistenceMigrationTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SHA_D = "d".repeat(64);

    @TempDir Path temporaryDirectory;

    @Test
    void upgradesV62WithoutInventingHistoricalJudgeRestartIdentity() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v62.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("62")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            fixture(statement);
            statement.executeUpdate("""
                    INSERT INTO judge_run(
                      id,task_id,attempt_id,role,ordinal,state,created_at,version,response_mode)
                    VALUES('legacy','task','attempt','REQUIREMENT',1,'COMPLETED','now',0,'TEXT_MARKER')
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("67");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT review_batch_id,source_revision FROM judge_run WHERE id='legacy'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("review_batch_id")).isNull();
            assertThat(result.getObject("source_revision")).isNull();
        }
    }

    @Test
    void requiresExactImmutableSnapshotBeforeGenericCreateDispatch() throws Exception {
        String url = migrated("source-gates.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            fixture(statement);
            batchAndJudge(statement);

            assertThatThrownBy(() -> statement.executeUpdate(launchInsert()))
                    .hasMessageContaining("Judge candidate launch requires exact frozen source snapshot");
            assertThatThrownBy(() -> statement.executeUpdate(snapshotInsert("RISK", 1, 0)))
                    .hasMessageContaining("Judge source snapshot owner mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(snapshotInsert("REQUIREMENT", 2, 0)))
                    .hasMessageContaining("Judge source snapshot owner mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(snapshotInsert("REQUIREMENT", 1, 1)))
                    .hasMessageContaining("Judge source snapshot owner mismatch");

            assertThat(statement.executeUpdate(snapshotInsert("REQUIREMENT", 1, 0))).isEqualTo(1);
            assertThat(statement.executeUpdate(launchInsert())).isEqualTo(1);
            assertThat(statement.executeUpdate("""
                    UPDATE ai_candidate_internal_launch
                    SET state='CREATING',create_claim_owner='worker',create_claim_token='claim',
                      create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                      create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                      updated_at='dispatch-at',version=version+1 WHERE id='launch'
                    """)).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE judge_candidate_source_snapshot
                    SET canonical_evidence_json='{}' WHERE candidate_run_id='run'
                    """))
                    .hasMessageContaining("Judge source snapshot is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE judge_run SET source_revision=2 WHERE id='judge'
                    """))
                    .hasMessageContaining("Judge candidate restart identity is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE judge_review_batch SET generation=2 WHERE id='batch'
                    """))
                    .hasMessageContaining("Judge review batch identity is immutable");
        }
    }

    @Test
    void acceptedPayloadIsImmutableAndSettlementRequiresAcceptedRunStopProofAndCompletedOwner()
            throws Exception {
        String url = migrated("accepted-result.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            fixture(statement);
            batchAndJudge(statement);
            statement.executeUpdate(snapshotInsert("REQUIREMENT", 1, 0));
            statement.executeUpdate(launchInsert());
            createAndSettleLaunch(connection, statement);

            assertThatThrownBy(() -> statement.executeUpdate(
                    acceptedInsert("RISK", 1, 1)))
                    .hasMessageContaining("Judge accepted result run mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(
                    acceptedInsert("REQUIREMENT", 2, 1)))
                    .hasMessageContaining("Judge accepted result run mismatch");
            assertThat(statement.executeUpdate(
                    acceptedInsert("REQUIREMENT", 1, 1))).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE judge_candidate_accepted_result SET reason='changed'
                    WHERE candidate_run_id='run'
                    """))
                    .hasMessageContaining("Judge accepted result payload is immutable");

            markRunAccepted(statement);
            assertThatThrownBy(() -> statement.executeUpdate(settleResult()))
                    .hasMessageContaining("Judge accepted result settlement requires positive remote stop proof");
            markLaunchStopped(statement);
            assertThatThrownBy(() -> statement.executeUpdate(settleResult()))
                    .hasMessageContaining("Judge accepted result settlement requires completed Judge owner");
            statement.executeUpdate("""
                    UPDATE judge_run
                    SET state='COMPLETED',verdict='PASS',reason='ok',
                      raw_output='{}',ended_at='ended',version=version+1 WHERE id='judge'
                    """);
            assertThat(statement.executeUpdate(settleResult())).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE judge_candidate_accepted_result
                    SET settled_judge_run_id=NULL,updated_at='again',version=version+1
                    WHERE candidate_run_id='run'
                    """))
                    .hasMessageContaining("Judge accepted result settlement is irreversible");
        }
    }

    private String migrated(String name) {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve(name);
        Flyway.configure().dataSource(url, null, null).load().migrate();
        return url;
    }

    private void fixture(Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO project(id,name,root_path,created_at,updated_at)
                VALUES('project','P','/tmp/judge-project','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO task(id,project_id,title,state,created_at,updated_at,version)
                VALUES('task','project','Task','JUDGING','now','now',0)
                """);
        statement.executeUpdate("""
                INSERT INTO stage(
                  id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,
                  deliverables_json,verifiers_json,state,created_at,updated_at,version)
                VALUES('stage','task',1,'Implement','[]','[]','[]','[]',
                  'SUCCEEDED','now','now',0)
                """);
        statement.executeUpdate("""
                INSERT INTO task_execution_cycle(
                  id,task_id,ordinal,kind,state,budget_json,authorized_at,started_at,version)
                VALUES('cycle','task',1,'INITIAL','RUNNING','{}','now','now',0)
                """);
        statement.executeUpdate("""
                INSERT INTO attempt(
                  id,task_id,stage_id,execution_cycle_id,ordinal,state,created_at,ended_at,version)
                VALUES('attempt','task','stage','cycle',1,'SUCCEEDED','now','now',0)
                """);
    }

    private void batchAndJudge(Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO judge_review_batch(
                  id,task_id,execution_cycle_id,final_attempt_id,generation,state,
                  created_at,updated_at,version)
                VALUES('batch','task','cycle','attempt',1,'RUNNING','now','now',0)
                """);
        statement.executeUpdate("""
                INSERT INTO judge_run(
                  id,task_id,attempt_id,role,ordinal,state,created_at,version,response_mode,
                  review_batch_id,source_revision)
                VALUES('judge','task','attempt','REQUIREMENT',1,'CREATING','now',0,
                  'INTERNAL_MCP','batch',1)
                """);
    }

    private String snapshotInsert(String role, long sourceRevision, long preparedVersion) {
        return """
                INSERT INTO judge_candidate_source_snapshot(
                  candidate_run_id,judge_run_id,task_id,execution_cycle_id,final_attempt_id,
                  review_batch_id,role,ordinal,source_revision,prepared_owner_version,contract_version,
                  source_prompt,source_prompt_sha256,canonical_evidence_json,evidence_sha256,created_at)
                VALUES('run','judge','task','cycle','attempt','batch','%s',1,%d,%d,
                  'JUDGE_DECISION_V1','Judge only the frozen evidence.','%s',
                  '{"verifications":[],"taskSpecSha256":"%s"}','%s','now')
                """.formatted(role, sourceRevision, preparedVersion, SHA_A, SHA_B, SHA_C);
    }

    private String launchInsert() {
        return """
                INSERT INTO ai_candidate_internal_launch(
                  id,candidate_run_id,candidate_kind,task_id,owner_type,owner_id,judge_run_id,
                  workflow_step,source_revision,contract_version,max_attempts,state,
                  prepared_owner_version,exact_title,canonical_directory,runtime_generation_id,
                  managed,internal_mcp_server,endpoint_fingerprint,profile,permission_policy_json,
                  permission_policy_digest,create_request_sha256,creation_credential,attestation_type,
                  created_at,updated_at,version)
                VALUES('launch','run','JUDGE_DECISION_V1','task','JUDGE_RUN','judge','judge',
                  'JUDGE_DECISION_V1',1,'JUDGE_DECISION_V1',2,'PREPARED',0,'judge-launch',
                  '/tmp/judge-project','generation-1',1,'loopper_internal_generic','%s',
                  'JUDGE_CANDIDATE_READ_ONLY','[]','%s','%s','%s',
                  'LOCAL_REQUEST_ATTESTED','now','now',0)
                """.formatted(SHA_C, SHA_D, SHA_A, "C".repeat(43));
    }

    private void createAndSettleLaunch(Connection connection, Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO open_code_session_runtime_binding(
                  external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                  internal_mcp_server,created_at)
                VALUES('remote','generation-1','MANAGED','%s','loopper_internal_generic','now')
                """.formatted(SHA_C));
        statement.executeUpdate("""
                UPDATE ai_candidate_internal_launch
                SET state='CREATED',create_claim_owner='worker',create_claim_token='claim',
                  create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                  create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                  external_session_id='remote',external_attested_at='attested-at',
                  updated_at='created-at',version=version+1 WHERE id='launch'
                """);
        statement.executeUpdate("""
                UPDATE judge_run
                SET external_session_id='remote',state='RUNNING',version=version+1 WHERE id='judge'
                """);
        connection.setAutoCommit(false);
        try {
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,task_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,
                      external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('run','task','JUDGE_RUN','judge','JUDGE_DECISION_V1',
                      'JUDGE_DECISION_V1',1,1,'INTERNAL_MCP','JUDGE_DECISION_V1',
                      'generation-1','remote','OPEN',2,0,'now','now',0)
                    """);
            statement.executeUpdate("""
                    UPDATE ai_candidate_internal_launch
                    SET state='SETTLED',settled_owner_version=1,settled_at='settled-at',
                      create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
                      updated_at='settled-at',version=version+1 WHERE id='launch'
                    """);
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private String acceptedInsert(String role, long sourceRevision, long ownerVersion) {
        return """
                INSERT INTO judge_candidate_accepted_result(
                  candidate_run_id,judge_run_id,review_batch_id,role,source_revision,owner_version,
                  contract_version,canonical_candidate_json,candidate_payload_sha256,
                  canonical_decision_json,canonical_result_sha256,verdict,reason,evidence_json,
                  created_at,updated_at,version)
                VALUES('run','judge','batch','%s',%d,%d,'JUDGE_DECISION_V1',
                  '{"verdict":"PASS","reason":"ok"}','%s',
                  '{"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"PASS","reason":"ok"}',
                  '%s','PASS','ok','[{"sourceEvidenceSha256":"%s"}]','now','now',0)
                """.formatted(role, sourceRevision, ownerVersion, SHA_A, SHA_B, SHA_C);
    }

    private void markRunAccepted(Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO ai_candidate_submission_attempt(
                  id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                  problems_json,response_json,canonical_result_sha256,created_at)
                VALUES('candidate-attempt','run',1,'key-1','%s','ACCEPTED',0,'[]','{}','%s','now')
                """.formatted(SHA_D, SHA_B));
        statement.executeUpdate("""
                UPDATE ai_candidate_submission_run
                SET state='ACCEPTED',attempts_used=1,terminal_attempt_id='candidate-attempt',
                  updated_at='accepted',version=version+1 WHERE id='run'
                """);
    }

    private void markLaunchStopped(Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO ai_candidate_internal_termination_intent(
                  id,launch_id,candidate_run_id,intent_kind,target_launch_state,state,
                  anchor_owner_version,created_at,updated_at,version)
                VALUES('termination','launch','run','RUN_COMPLETED','COMPLETED','REQUESTED',
                  1,'now','now',0)
                """);
        statement.executeUpdate("""
                UPDATE ai_candidate_internal_launch
                SET state='COMPLETED',termination_proof='REMOTE_COMPLETED',proof_at='proof-at',
                  failure_phase='REMOTE_STOP',updated_at='proof-at',version=version+1
                WHERE id='launch'
                """);
        statement.executeUpdate("""
                UPDATE ai_candidate_internal_termination_intent
                SET state='READY',ready_at='ready-at',updated_at='ready-at',version=version+1
                WHERE id='termination'
                """);
    }

    private String settleResult() {
        return """
                UPDATE judge_candidate_accepted_result
                SET settled_judge_run_id='judge',updated_at='settled',version=version+1
                WHERE candidate_run_id='run'
                """;
    }
}
