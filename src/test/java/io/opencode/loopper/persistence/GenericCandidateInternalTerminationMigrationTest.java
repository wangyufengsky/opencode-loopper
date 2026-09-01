package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenericCandidateInternalTerminationMigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void migratesSettledLaunchAndCompletesNormalTerminalWithoutErasingCertificate() throws Exception {
        String url = settledV58Database("normal-completion.db");
        migrateLatest(url);

        try (Connection connection = DriverManager.getConnection(url);
            Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            assertThatThrownBy(() -> insertIntent(statement, "RUN_COMPLETED", "FAILED_STOPPED"))
                    .hasMessageContaining("CHECK constraint failed");
            insertIntent(statement, "RUN_COMPLETED", "COMPLETED");
            quietRemoteProtocol(statement);
            terminalize(statement, "COMPLETED");

            assertThat(value(statement, "SELECT state FROM ai_candidate_internal_launch "
                    + "WHERE id='reviewer-launch'")) .isEqualTo("COMPLETED");
            assertThat(value(statement, "SELECT settled_owner_version || ':' || settled_at "
                    + "FROM ai_candidate_internal_launch WHERE id='reviewer-launch'"))
                    .isEqualTo("1:settled-at");
            assertThat(value(statement, "SELECT settled_owner_version || ':' || settled_at "
                    + "FROM ai_candidate_internal_launch_settlement_certificate "
                    + "WHERE launch_id='reviewer-launch'"))
                    .isEqualTo("1:settled-at");
            assertThat(value(statement, "SELECT state FROM ai_candidate_internal_termination_intent "
                    + "WHERE id='termination'")) .isEqualTo("READY");
            assertThat(foreignKeyViolationCount(statement)).isZero();
        }
    }

    @Test
    void settledProtocolFailureRetainsSettlementFactsInFailedStoppedTerminal() throws Exception {
        String url = settledV58Database("protocol-failure.db");
        migrateLatest(url);

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertIntent(statement, "PROTOCOL_FAILURE", "FAILED_STOPPED");
            statement.executeUpdate("""
                    UPDATE ai_candidate_internal_termination_intent
                    SET owner_cancel_requested=1,updated_at='cancel-at',version=version+1
                    WHERE id='termination'
                    """);
            assertThat(value(statement, "SELECT owner_cancel_requested FROM "
                    + "ai_candidate_internal_termination_intent WHERE id='termination'"))
                    .isEqualTo("1");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_internal_termination_intent
                    SET owner_cancel_requested=0 WHERE id='termination'
                    """)).hasMessageContaining("owner cancellation is monotonic");
            quietRemoteProtocol(statement);
            terminalize(statement, "FAILED_STOPPED");

            assertThat(value(statement, "SELECT state || ':' || settled_owner_version || ':' || settled_at "
                    + "FROM ai_candidate_internal_launch WHERE id='reviewer-launch'"))
                    .isEqualTo("FAILED_STOPPED:1:settled-at");
            assertThat(value(statement, "SELECT termination_proof FROM ai_candidate_internal_launch "
                    + "WHERE id='reviewer-launch'")) .isEqualTo("REMOTE_COMPLETED");
            assertThat(foreignKeyViolationCount(statement)).isZero();
        }
    }

    private String settledV58Database(String name) throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve(name);
        Flyway.configure().dataSource(url, null, null).target("58").load().migrate();
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertOwnerFixture(statement);
            statement.executeUpdate(reviewerLaunchInsert());
            statement.executeUpdate("""
                    INSERT INTO reviewer_report_candidate_source_snapshot(
                      candidate_run_id,analysis_report_id,source_revision,prepared_owner_version,
                      contract_version,canonical_source_manifest_json,source_manifest_sha256,created_at)
                    VALUES('reviewer-run','report',7,0,'REVIEWER_REPORT_V1','[]',
                      'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                      internal_mcp_server,created_at)
                    VALUES('reviewer-remote','generation-1','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'loopper_internal_generic','now')
                    """);
            statement.executeUpdate("""
                    UPDATE ai_candidate_internal_launch
                    SET state='CREATED',create_claim_owner='worker',create_claim_token='claim',
                      create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                      create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                      external_session_id='reviewer-remote',external_attested_at='attested-at',
                      version=version+1
                    WHERE id='reviewer-launch'
                    """);
            statement.executeUpdate("""
                    UPDATE analysis_report
                    SET external_session_id='reviewer-remote',
                      external_session_state='CANDIDATE_PROMPT_PENDING',version=1,updated_at='attached-at'
                    WHERE id='report'
                    """);
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate(reviewerRunInsert());
                statement.executeUpdate("""
                        UPDATE ai_candidate_internal_launch
                        SET state='SETTLED',settled_owner_version=1,settled_at='settled-at',
                          create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
                          version=version+1
                        WHERE id='reviewer-launch'
                        """);
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,
                      dispatch_attempted,dispatch_started_at,acknowledged,acked_at,
                      created_at,updated_at,candidate_launch_id)
                    VALUES('initial-prompt','reviewer-run','INITIAL','reviewer-remote','generation-1',
                      'initial-message','{}',
                      'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                      'ACKNOWLEDGED',1,'model-at',1,'dispatch-at',1,'ack-at','now','ack-at',
                      'reviewer-launch')
                    """);
            statement.executeUpdate("""
                    UPDATE ai_candidate_submission_run
                    SET state='CLOSED',close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION',
                      updated_at='closed-at',version=version+1
                    WHERE id='reviewer-run'
                    """);
        }
        return url;
    }

    private void migrateLatest(String url) {
        Flyway.configure().dataSource(url, null, null).load().migrate();
    }

    private void insertIntent(Statement statement, String kind, String target) throws Exception {
        statement.executeUpdate("""
                INSERT INTO ai_candidate_internal_termination_intent(
                  id,launch_id,candidate_run_id,intent_kind,target_launch_state,state,reason_code,
                  anchor_owner_version,created_at,updated_at,version)
                VALUES('termination','reviewer-launch','reviewer-run','%s','%s','REQUESTED',
                  'TEST_TERMINATION',1,'termination-at','termination-at',0)
                """.formatted(kind, target));
    }

    private void quietRemoteProtocol(Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO ai_candidate_internal_launch_cleanup_remote(
                  launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                  directory_sha256,title_sha256,purpose,state,termination_proof,proof_at,
                  created_at,updated_at,version)
                VALUES('reviewer-launch','reviewer-remote','generation-1',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                  'TERMINATION','STOPPED','REMOTE_COMPLETED','stopped-at','now','stopped-at',0)
                """);
        statement.executeUpdate("""
                UPDATE ai_candidate_prompt_dispatch
                SET state='STOPPED',termination_proof='REMOTE_COMPLETED',
                  termination_proof_at='stopped-at',updated_at='stopped-at',version=version+1
                WHERE id='initial-prompt'
                """);
    }

    private void terminalize(Statement statement, String target) throws Exception {
        statement.executeUpdate("""
                UPDATE ai_candidate_internal_launch
                SET state='%s',termination_proof='REMOTE_COMPLETED',proof_at='stopped-at',
                  failure_phase='REMOTE_STOP',updated_at='terminal-at',version=version+1
                WHERE id='reviewer-launch'
                """.formatted(target));
        statement.executeUpdate("""
                UPDATE ai_candidate_internal_termination_intent
                SET state='READY',ready_at='ready-at',updated_at='ready-at',version=version+1
                WHERE id='termination'
                """);
    }

    private void insertOwnerFixture(Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO project(id,name,root_path,created_at,updated_at)
                VALUES('project','Project','/tmp/project','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at)
                VALUES('draft','project','Goal','{}','DRAFT_READY','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at)
                VALUES('designer','project','RUNNING','READ_ONLY','draft','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_message(
                  id,designer_session_id,ordinal,role,content,delivery_state,created_at)
                VALUES('message','designer',1,'ASSISTANT','requirement','PERSISTED','now')
                """);
        statement.executeUpdate("""
                INSERT INTO design_requirement_revision(
                  id,designer_session_id,revision,source_message_id,requirement_text,
                  requirement_segments_json,source_draft_version,state,created_at,updated_at)
                VALUES('revision','designer',1,'message','requirement','[]',0,'ACTIVE','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_task_profile(
                  id,designer_session_id,requirement_revision_id,state,intent,workflow_template,
                  mutation_mode,artifact_kinds_json,technologies_json,test_policy,execution_strategy,
                  role_pack_id,role_pack_version,confidence,evidence_json,resolution_source,
                  decision_required,created_at,updated_at)
                VALUES('profile','designer','revision','FROZEN','READ_ONLY_REVIEW','REVIEWER_REPORT',
                  'READ_ONLY','[]','[]','NOT_APPLICABLE','OPENCODE','reviewer','2026-08-dynamic-v7',
                  90,'[]','USER_CONFIRMED',0,'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO analysis_report(
                  id,designer_session_id,task_profile_id,state,title,markdown,evidence_json,
                  reviewer_contract_version,created_at,updated_at,version)
                VALUES('report','designer','profile','RUNNING','Report','','[]','REVIEWER_REPORT_V1',
                  'now','now',0)
                """);
    }

    private String reviewerLaunchInsert() {
        return """
                INSERT INTO ai_candidate_internal_launch(
                  id,candidate_run_id,candidate_kind,designer_session_id,owner_type,owner_id,
                  analysis_report_id,workflow_step,source_revision,contract_version,max_attempts,state,
                  prepared_owner_version,exact_title,canonical_directory,runtime_generation_id,managed,
                  internal_mcp_server,endpoint_fingerprint,profile,permission_policy_json,
                  permission_policy_digest,create_request_sha256,creation_credential,attestation_type,
                  created_at,updated_at,version)
                VALUES('reviewer-launch','reviewer-run','REVIEWER_REPORT_V1','designer',
                  'ANALYSIS_REPORT','report','report','REVIEWER_REPORT_V1',7,'REVIEWER_REPORT_V1',3,
                  'PREPARED',0,'Reviewer candidate candidate_launch_id=reviewer-launch','/tmp/project',
                  'generation-1',1,'loopper_internal_generic',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'REVIEWER_CANDIDATE_READ_ONLY','[]',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                  'RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR','LOCAL_REQUEST_ATTESTED',
                  'now','now',0)
                """;
    }

    private String reviewerRunInsert() {
        return """
                INSERT INTO ai_candidate_submission_run(
                  id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                  owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                  state,max_attempts,attempts_used,created_at,updated_at,version)
                VALUES('reviewer-run','designer','ANALYSIS_REPORT','report','REVIEWER_REPORT_V1',
                  'REVIEWER_REPORT_V1',7,1,'INTERNAL_MCP','REVIEWER_REPORT_V1','generation-1',
                  'reviewer-remote','OPEN',3,0,'now','now',0)
                """;
    }

    private String value(Statement statement, String sql) throws Exception {
        try (var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private int foreignKeyViolationCount(Statement statement) throws Exception {
        int count = 0;
        try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
            while (result.next()) count++;
        }
        return count;
    }
}
