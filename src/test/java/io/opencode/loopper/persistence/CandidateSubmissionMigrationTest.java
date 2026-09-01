package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CandidateSubmissionMigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void initialPromptRequiresRealAcceptanceV7RunAndItsExactAckGatesZeroSubmissionClose() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("candidate-initial-prompt.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                      internal_mcp_server,created_at)
                    VALUES('initial-remote','initial-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'loopper_internal_acceptance','now')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,created_at,updated_at)
                    VALUES('orphan-initial','missing-run','INITIAL','initial-remote','initial-generation',
                      'orphan-message','{}',
                      'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                      'CANCELLED','now','now')
                    """)).hasMessageContaining("initial candidate prompt requires");
            List<String> runForeignKeys = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA foreign_key_list(ai_candidate_prompt_dispatch)")) {
                while (result.next()) {
                    if ("ai_candidate_submission_run".equals(result.getString("table"))) {
                        runForeignKeys.add(result.getString("from") + "->" + result.getString("to"));
                    }
                }
            }
            assertThat(runForeignKeys).contains("run_id->id");
            insertSettledInternalAcceptanceRun(connection, statement);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,internal_launch_id,dispatch_kind,source_attempt_ordinal,
                      external_session_id,runtime_generation_id,message_id,request_json,request_sha256,state,
                      model_call_consumed,model_call_consumed_at,created_at,updated_at)
                    VALUES('initial-dispatch','initial-run','initial-launch','INITIAL',NULL,
                      'initial-remote','initial-generation',
                      'initial-message','{}',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'PROMPTING',1,'charged-at','now','now')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,internal_launch_id,dispatch_kind,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,created_at,updated_at)
                    VALUES('duplicate-initial','initial-run','initial-launch','INITIAL','initial-remote','initial-generation',
                      'duplicate-message','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'PROMPTING',1,'charged-at','now','now')
                    """)).hasMessageContaining("UNIQUE constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_submission_run
                    SET state='CLOSED',close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION'
                    WHERE id='initial-run'
                    """)).hasMessageContaining("requires acknowledged initial prompt");
            statement.executeUpdate("UPDATE ai_candidate_submission_run SET attempts_used=1 WHERE id='initial-run'");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_submission_run
                    SET state='CLOSED',close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION'
                    WHERE id='initial-run'
                    """)).hasMessageContaining("requires zero attempts");
            statement.executeUpdate("UPDATE ai_candidate_submission_run SET attempts_used=0 WHERE id='initial-run'");
            statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET dispatch_attempted=1,dispatch_started_at='posted-at',acknowledged=1,acked_at='acked-at',
                      state='ACKNOWLEDGED' WHERE id='initial-dispatch'
                    """);
            statement.executeUpdate("""
                    UPDATE ai_candidate_submission_run
                    SET state='CLOSED',close_reason='NORMAL_COMPLETION_ZERO_SUBMISSION'
                    WHERE id='initial-run'
                    """);
            assertThat(count(statement, "ai_candidate_submission_run",
                    "id='initial-run' AND state='CLOSED'")).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate(
                    "DELETE FROM ai_candidate_submission_run WHERE id='initial-run'"))
                    .hasMessageContaining("live Acceptance candidate run cannot be deleted");
            assertThat(count(statement, "ai_candidate_prompt_dispatch",
                    "id='initial-dispatch'")).isEqualTo(1);
        }
    }

    @Test
    void v51CreatesDurableGenericCandidatePromptDispatchContract() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("candidate-prompt-dispatch.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();

        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            List<String> columns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_prompt_dispatch)")) {
                while (result.next()) columns.add(result.getString("name"));
            }
            assertThat(columns).containsExactly(
                    "id", "run_id", "dispatch_kind", "source_attempt_ordinal", "external_session_id",
                    "runtime_generation_id",
                    "message_id", "request_json",
                    "request_sha256", "state", "model_call_consumed", "model_call_consumed_at",
                    "claim_owner", "claim_token", "claim_expires_at", "fence", "dispatch_attempted",
                    "dispatch_started_at", "acknowledged", "acked_at", "termination_proof",
                    "termination_proof_at", "last_error_code", "last_error_detail", "created_at",
                    "updated_at", "version", "internal_launch_id");
            List<String> attemptForeignKeys = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA foreign_key_list(ai_candidate_prompt_dispatch)")) {
                while (result.next()) {
                    if ("ai_candidate_submission_attempt".equals(result.getString("table"))) {
                        attemptForeignKeys.add(result.getString("from") + "->" + result.getString("to"));
                    }
                }
            }
            assertThat(attemptForeignKeys).containsExactlyInAnyOrder(
                    "run_id->run_id", "source_attempt_ordinal->ordinal");

            insertCandidateOwners(statement);
            statement.executeUpdate("INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,"
                    + "source_design_message_id,source_draft_version,created_at,updated_at) "
                    + "VALUES('cmp-cross','s',1,'RUNNING','m',0,'now','now')");
            statement.executeUpdate("INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,"
                    + "source_design_message_id,source_draft_version,created_at,updated_at) "
                    + "VALUES('cmp-anchor','s',2,'RUNNING','m',0,'now','now')");
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                      internal_mcp_server,created_at)
                    VALUES('prompt-remote','prompt-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'loopper_internal_prompt','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                      internal_mcp_server,created_at)
                    VALUES('other-remote','other-generation','MANAGED',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'loopper_internal_other','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('prompt-run','s','TASK_DECOMPOSITION','dec','DECOMPOSITION_PLAN_V2',
                      'PLANNING',1,0,'IN_PROCESS_LEGACY','DECOMPOSITION_PLAN_V2','prompt-generation',
                      'prompt-remote','OPEN',5,0,'now','now',0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                      problems_json,response_json,created_at)
                    VALUES
                      ('prompt-attempt-1','prompt-run',1,'prompt-key-1',
                        '1111111111111111111111111111111111111111111111111111111111111111',
                        'REJECTED',1,'[]','{}','now'),
                      ('prompt-attempt-2','prompt-run',2,'prompt-key-2',
                        '2222222222222222222222222222222222222222222222222222222222222222',
                        'ACCEPTED',1,'[]','{}','now'),
                      ('prompt-attempt-3','prompt-run',3,'prompt-key-3',
                        '3333333333333333333333333333333333333333333333333333333333333333',
                        'REJECTED',0,'[]','{}','now'),
                      ('prompt-attempt-4','prompt-run',4,'prompt-key-4',
                        '4444444444444444444444444444444444444444444444444444444444444444',
                        'REJECTED',1,'[]','{}','now'),
                      ('prompt-attempt-5','prompt-run',5,'prompt-key-5',
                        '5555555555555555555555555555555555555555555555555555555555555555',
                        'REJECTED',1,'[]','{}','now')
                    """);
            statement.executeUpdate("UPDATE ai_candidate_submission_run SET attempts_used=1 WHERE id='prompt-run'");

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,
                      fence,dispatch_attempted,dispatch_started_at,acknowledged,created_at,updated_at,version)
                    VALUES('dispatch-missing-attempt','prompt-run','CORRECTION',99,'prompt-remote','prompt-generation',
                      'prompt-message-missing','{}',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'PROMPTING',1,'charged-at',0,1,'dispatch-at',0,'now','now',0)
                    """)).hasMessageContaining("candidate prompt dispatch source attempt must be retryable REJECTED");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,
                      fence,dispatch_attempted,dispatch_started_at,acknowledged,created_at,updated_at,version)
                    VALUES('dispatch-accepted-attempt','prompt-run','CORRECTION',2,'prompt-remote','prompt-generation',
                      'prompt-message-accepted','{}',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'PROMPTING',1,'charged-at',0,1,'dispatch-at',0,'now','now',0)
                    """)).hasMessageContaining("candidate prompt dispatch source attempt must be retryable REJECTED");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,
                      fence,dispatch_attempted,dispatch_started_at,acknowledged,created_at,updated_at,version)
                    VALUES('dispatch-nonretryable-attempt','prompt-run','CORRECTION',3,'prompt-remote','prompt-generation',
                      'prompt-message-nonretryable','{}',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'PROMPTING',1,'charged-at',0,1,'dispatch-at',0,'now','now',0)
                    """)).hasMessageContaining("candidate prompt dispatch source attempt must be retryable REJECTED");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,
                      fence,dispatch_attempted,dispatch_started_at,acknowledged,created_at,updated_at,version)
                    VALUES('dispatch-wrong-binding','prompt-run','CORRECTION',1,'other-remote','other-generation',
                      'prompt-message-wrong-binding','{}',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'PROMPTING',1,'charged-at',0,1,'dispatch-at',0,'now','now',0)
                    """)).hasMessageContaining("candidate prompt dispatch runtime binding mismatch");

            statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,
                      model_call_consumed,model_call_consumed_at,fence,dispatch_attempted,
                      dispatch_started_at,acknowledged,created_at,updated_at,version)
                    VALUES('dispatch-1','prompt-run','CORRECTION',1,'prompt-remote','prompt-generation',
                      'prompt-message-1','{"prompt":"choose one"}',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'PROMPTING',1,'charged-at',0,1,'dispatch-at',0,'now','now',0)
                    """);
            try (var result = statement.executeQuery("""
                    SELECT request_json,request_sha256,state FROM ai_candidate_prompt_dispatch
                    WHERE id='dispatch-1'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("request_json")).isEqualTo("{\"prompt\":\"choose one\"}");
                assertThat(result.getString("request_sha256")).isEqualTo("b".repeat(64));
                assertThat(result.getString("state")).isEqualTo("PROMPTING");
            }

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,
                      model_call_consumed,model_call_consumed_at,fence,dispatch_attempted,
                      dispatch_started_at,acknowledged,created_at,updated_at,version)
                    VALUES('dispatch-duplicate','prompt-run','CORRECTION',1,'prompt-remote','prompt-generation',
                      'prompt-message-2','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'PROMPTING',1,'charged-at',0,1,'dispatch-at',0,'now','now',0)
                    """)).hasMessageContaining("UNIQUE constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET model_call_consumed=0,model_call_consumed_at=NULL WHERE id='dispatch-1'
                    """)).hasMessageContaining("candidate prompt dispatch model call evidence is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET claim_owner='worker',claim_token='token' WHERE id='dispatch-1'
                    """)).hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET dispatch_attempted=0,dispatch_started_at=NULL WHERE id='dispatch-1'
                    """)).hasMessageContaining("candidate prompt dispatch attempt evidence is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE ai_candidate_prompt_dispatch SET acknowledged=1 WHERE id='dispatch-1'"))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch SET termination_proof='ABORT_ACKNOWLEDGED'
                    WHERE id='dispatch-1'
                    """)).hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE ai_candidate_prompt_dispatch SET state='STOPPED' WHERE id='dispatch-1'"))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE ai_candidate_prompt_dispatch SET state='CANCELLED' WHERE id='dispatch-1'"))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch SET request_json='{"prompt":"changed"}'
                    WHERE id='dispatch-1'
                    """)).hasMessageContaining("candidate prompt dispatch identity and request are immutable");

            statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET state='ACKNOWLEDGED',acknowledged=1,acked_at='acked-at',updated_at='ack',version=version+1
                    WHERE id='dispatch-1'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET acknowledged=0,acked_at=NULL WHERE id='dispatch-1'
                    """)).hasMessageContaining("candidate prompt dispatch acknowledgement evidence is irreversible");
            statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET state='STOPPING',claim_owner='worker',claim_token='token',
                      claim_expires_at='expires-at',fence=fence+1,updated_at='stopping',version=version+1
                    WHERE id='dispatch-1'
                    """);
            statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET state='STOPPED',claim_owner=NULL,claim_token=NULL,claim_expires_at=NULL,
                      termination_proof='ABORT_ACKNOWLEDGED',termination_proof_at='proof-at',
                      updated_at='stopped',version=version+1
                    WHERE id='dispatch-1'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET termination_proof=NULL,termination_proof_at=NULL WHERE id='dispatch-1'
                    """)).hasMessageContaining("candidate prompt dispatch termination proof is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET claim_owner='worker',claim_token='token',claim_expires_at='expires-at'
                    WHERE id='dispatch-1'
                    """)).hasMessageContaining("CHECK constraint failed");

            statement.executeUpdate("UPDATE ai_candidate_submission_run SET attempts_used=4 WHERE id='prompt-run'");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,
                      dispatch_attempted,dispatch_started_at,created_at,updated_at)
                    VALUES('dispatch-cancel-without-proof','prompt-run','CORRECTION',4,'prompt-remote','prompt-generation',
                      'prompt-message-cancel-without-proof','{}',
                      'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                      'CANCELLED',1,'charged-at',1,'dispatch-at','now','now')
                    """)).hasMessageContaining("CHECK constraint failed");
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,model_call_consumed,model_call_consumed_at,
                      dispatch_attempted,dispatch_started_at,termination_proof,termination_proof_at,
                      created_at,updated_at)
                    VALUES('dispatch-cancel-with-proof','prompt-run','CORRECTION',4,'prompt-remote','prompt-generation',
                      'prompt-message-cancel-with-proof','{}',
                      'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                      'CANCELLED',1,'charged-at',1,'dispatch-at','ABORT_ACKNOWLEDGED','proof-at',
                      'now','now')
                    """);
            statement.executeUpdate("UPDATE ai_candidate_submission_run SET attempts_used=5 WHERE id='prompt-run'");
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_prompt_dispatch(
                      id,run_id,dispatch_kind,source_attempt_ordinal,external_session_id,runtime_generation_id,message_id,
                      request_json,request_sha256,state,created_at,updated_at)
                    VALUES('dispatch-cancel-undispatched','prompt-run','CORRECTION',5,'prompt-remote','prompt-generation',
                      'prompt-message-cancel-undispatched','{}',
                      'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                      'CANCELLED','now','now')
                    """);
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void createsCurrentCandidateSubmissionContractsOnFreshV46AndV50Databases() throws Exception {
        verifyMigration(temporaryDirectory.resolve("fresh.db"), null);
        verifyMigration(temporaryDirectory.resolve("upgrade-v46.db"), "46");
        verifyMigration(temporaryDirectory.resolve("upgrade-v50.db"), "50");
    }

    private void verifyMigration(Path database, String startingVersion) throws Exception {
        String url = "jdbc:sqlite:" + database;
        if (startingVersion != null) {
            Flyway.configure().dataSource(url, null, null)
                    .target(MigrationVersion.fromVersion(startingVersion)).load().migrate();
            if ("46".equals(startingVersion)) insertLegacyManagedSession(url);
        }
        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("55");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            List<String> tables = new ArrayList<>();
            try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (result.next()) tables.add(result.getString(1));
            }
            assertThat(tables).contains("ai_candidate_submission_run", "ai_candidate_submission_attempt",
                    "open_code_session_runtime_binding", "package_design_candidate_accepted_result",
                    "acceptance_candidate_legacy_handoff");

            List<String> bindingColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(open_code_session_runtime_binding)")) {
                while (result.next()) bindingColumns.add(result.getString("name"));
            }
            assertThat(bindingColumns).contains("internal_mcp_server");
            if ("46".equals(startingVersion)) {
                try (var result = statement.executeQuery("""
                        SELECT ownership_mode,runtime_generation_id,internal_mcp_server
                        FROM open_code_session_runtime_binding
                        WHERE external_session_id='legacy-designer-remote'
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("ownership_mode")).isEqualTo("LEGACY_UNKNOWN");
                    assertThat(result.getString("runtime_generation_id")).startsWith("legacy-unbound-");
                    assertThat(result.getString("internal_mcp_server")).isNull();
                }
            }
            List<String> handoffColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(acceptance_candidate_legacy_handoff)")) {
                while (result.next()) handoffColumns.add(result.getString("name"));
            }
            assertThat(handoffColumns).contains("old_external_session_id", "old_runtime_generation_id",
                    "old_endpoint_fingerprint", "old_termination_proof", "legacy_creation_key",
                    "legacy_external_session_id", "legacy_runtime_generation_id",
                    "legacy_endpoint_fingerprint", "legacy_termination_proof", "legacy_prompt_message_id",
                    "model_call_consumed", "current_owner_version", "version",
                    "create_claim_owner", "create_claim_token", "create_claim_expires_at",
                    "create_fence", "create_dispatch_attempted", "create_dispatch_started_at",
                    "prompt_claim_owner", "prompt_claim_token",
                    "prompt_claim_expires_at", "prompt_fence", "successor_exact_title",
                    "successor_canonical_directory", "successor_runtime_generation_id",
                    "successor_managed", "successor_internal_mcp_server",
                    "successor_endpoint_fingerprint", "successor_model_provider_id",
                    "successor_model_id", "successor_thinking", "successor_profile",
                    "successor_permission_policy_json", "successor_permission_policy_digest",
                    "successor_create_request_sha256",
                    "successor_creation_credential", "successor_attestation_type");

            List<String> cleanupColumns = new ArrayList<>();
            try (var result = statement.executeQuery(
                    "PRAGMA table_info(acceptance_candidate_handoff_cleanup_remote)")) {
                while (result.next()) cleanupColumns.add(result.getString("name"));
            }
            assertThat(tables).contains("acceptance_candidate_handoff_cleanup_remote");
            assertThat(cleanupColumns).containsExactly(
                    "handoff_id", "external_session_id", "runtime_generation_id",
                    "endpoint_fingerprint", "directory_sha256", "title_sha256",
                    "state", "termination_proof", "proof_at", "stop_claim_owner",
                    "stop_claim_token", "stop_claim_expires_at", "stop_fence",
                    "last_error_code", "last_error_detail", "created_at", "updated_at", "version");
            if ("50".equals(startingVersion)) {
                try (var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM acceptance_candidate_legacy_handoff")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                }
            }

            List<String> runColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_submission_run)")) {
                while (result.next()) runColumns.add(result.getString("name"));
            }
            assertThat(runColumns).contains("designer_session_id", "task_id", "project_id", "owner_type",
                            "owner_id", "source_revision", "owner_version", "submission_channel", "close_reason")
                    .doesNotContain("task_decomposition_id", "loop_spec_compilation_id", "design_work_package_id");

            List<String> compilationColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(loop_spec_compilation)")) {
                while (result.next()) compilationColumns.add(result.getString("name"));
            }
            assertThat(compilationColumns).contains("compilation_source", "fallback_reason");

            List<String> attemptColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_submission_attempt)")) {
                while (result.next()) attemptColumns.add(result.getString("name"));
            }
            assertThat(attemptColumns)
                    .contains("request_sha256", "problems_json", "response_json", "canonical_result_sha256")
                    .noneMatch(column -> column.contains("candidate"));

            List<String> acceptedResultColumns = new ArrayList<>();
            try (var result = statement.executeQuery(
                    "PRAGMA table_info(package_design_candidate_accepted_result)")) {
                while (result.next()) acceptedResultColumns.add(result.getString("name"));
            }
            assertThat(acceptedResultColumns).containsExactly(
                    "candidate_run_id", "design_work_package_id", "source_revision", "owner_version",
                    "contract_version", "canonical_candidate_json", "canonical_markdown",
                    "compiled_result_json", "canonical_result_sha256", "settled_compilation_id",
                    "created_at", "updated_at", "version");

            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                      internal_mcp_server,created_at)
                    VALUES('remote-1','generation-1','MANAGED','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'loopper_internal_generation1','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                      internal_mcp_server,created_at)
                    VALUES('legacy-remote','legacy-generation','EXTERNAL',
                      'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',NULL,'now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_legacy_handoff(
                      id,compilation_id,designer_session_id,work_package_id,source_design_revision,
                      source_design_message_id,source_draft_version,source_design_sha256,contract_version,state,
                      prepared_owner_version,current_owner_version,old_external_session_id,
                      old_runtime_generation_id,old_endpoint_fingerprint,old_external_state,
                      legacy_creation_key,successor_exact_title,successor_canonical_directory,
                      successor_runtime_generation_id,successor_managed,successor_internal_mcp_server,
                      successor_endpoint_fingerprint,successor_model_provider_id,successor_model_id,
                      successor_thinking,successor_profile,successor_permission_policy_json,
                      successor_permission_policy_digest,successor_create_request_sha256,
                      successor_creation_credential,successor_attestation_type,create_fence,
                      legacy_prompt_message_id,prompt_fence,model_call_consumed,created_at,updated_at,version)
                    VALUES('handoff-1','cmp','s','WP-1',1,'m',0,
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','CREATING_LEGACY',0,0,'remote-1','generation-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','RUNNING',
                      'creation-key-1','Acceptance legacy [loopper-create:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA]',
                      '/tmp/p','generation-1',1,'loopper_internal_generation1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',NULL,NULL,NULL,
                      'COMPILER_BINDING_NO_TOOLS','[]',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','LOCAL_REQUEST_ATTESTED',0,
                      'prompt-message-1',0,0,'now','now',0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_handoff_cleanup_remote(
                      handoff_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                      directory_sha256,title_sha256,state,created_at,updated_at,version)
                    VALUES('handoff-1','legacy-remote','legacy-generation',
                      'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                      'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                      'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                      'DISCOVERED','now','now',0)
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_handoff_cleanup_remote
                    SET termination_proof='ABORT_ACKNOWLEDGED',proof_at='proof-at'
                    WHERE handoff_id='handoff-1' AND external_session_id='legacy-remote'
                    """)).hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_handoff_cleanup_remote
                    SET state='STOPPING',stop_claim_owner='worker',stop_claim_token='token'
                    WHERE handoff_id='handoff-1' AND external_session_id='legacy-remote'
                    """)).hasMessageContaining("CHECK constraint failed");
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_handoff_cleanup_remote
                    SET state='STOPPING',stop_claim_owner='worker',stop_claim_token='token',
                      stop_claim_expires_at='expires-at',stop_fence=stop_fence+1
                    WHERE handoff_id='handoff-1' AND external_session_id='legacy-remote'
                    """);
            for (String terminal : List.of("SETTLED", "FAILED_STOPPED", "CANCELLED", "STALE")) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE acceptance_candidate_legacy_handoff SET state='" + terminal
                                + "' WHERE id='handoff-1'"))
                        .hasMessageContaining("cleanup remotes must be stopped before terminal handoff");
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_handoff_cleanup_remote SET state='STOPPED'
                    WHERE handoff_id='handoff-1' AND external_session_id='legacy-remote'
                    """)).hasMessageContaining("CHECK constraint failed");
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_handoff_cleanup_remote
                    SET state='STOPPED',stop_claim_owner=NULL,stop_claim_token=NULL,stop_claim_expires_at=NULL,
                      termination_proof='ABORT_ACKNOWLEDGED',proof_at='proof-at'
                    WHERE handoff_id='handoff-1' AND external_session_id='legacy-remote'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_legacy_handoff(
                      id,compilation_id,designer_session_id,work_package_id,source_design_revision,
                      source_design_message_id,source_draft_version,source_design_sha256,contract_version,state,
                      prepared_owner_version,current_owner_version,old_external_state,
                      legacy_creation_key,successor_exact_title,successor_canonical_directory,
                      successor_runtime_generation_id,successor_managed,successor_internal_mcp_server,
                      successor_endpoint_fingerprint,successor_profile,successor_permission_policy_json,
                      successor_permission_policy_digest,successor_create_request_sha256,
                      successor_creation_credential,successor_attestation_type,create_fence,
                      legacy_prompt_message_id,prompt_fence,model_call_consumed,created_at,updated_at,version)
                    VALUES('handoff-cross-owner','cmp-cross','s-other','WP-1',1,'m',0,
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','CREATING_LEGACY',0,0,'RUNNING',
                      'creation-key-cross-owner','Acceptance legacy cross owner','/tmp/p','generation-1',1,
                      'loopper_internal_generation1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'COMPILER_BINDING_NO_TOOLS','[]',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB','LOCAL_REQUEST_ATTESTED',0,
                      'prompt-message-cross-owner',0,0,'now','now',0)
                    """)).hasMessageContaining("owner/source anchor mismatch");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_legacy_handoff(
                      id,compilation_id,designer_session_id,work_package_id,source_design_revision,
                      source_design_message_id,source_draft_version,source_design_sha256,contract_version,state,
                      prepared_owner_version,current_owner_version,old_external_state,
                      legacy_creation_key,successor_exact_title,successor_canonical_directory,
                      successor_runtime_generation_id,successor_managed,successor_internal_mcp_server,
                      successor_endpoint_fingerprint,successor_profile,successor_permission_policy_json,
                      successor_permission_policy_digest,successor_create_request_sha256,
                      successor_creation_credential,successor_attestation_type,create_fence,
                      legacy_prompt_message_id,prompt_fence,model_call_consumed,created_at,updated_at,version)
                    VALUES('handoff-cross-anchor','cmp-anchor','s','WP-1',1,'m',0,
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','CREATING_LEGACY',0,0,'RUNNING',
                      'creation-key-cross-anchor','Acceptance legacy cross anchor','/tmp/p','generation-1',1,
                      'loopper_internal_generation1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'COMPILER_BINDING_NO_TOOLS','[]',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC','LOCAL_REQUEST_ATTESTED',0,
                      'prompt-message-cross-anchor',0,0,'now','now',0)
                    """)).hasMessageContaining("owner/source anchor mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET old_proof_at='now' WHERE id='handoff-1'"))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET state='STALE' WHERE id='handoff-1'"))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_legacy_handoff
                    SET old_external_session_id=NULL,old_runtime_generation_id=NULL,old_endpoint_fingerprint=NULL
                    WHERE id='handoff-1'
                    """)).hasMessageContaining("identity is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_legacy_handoff
                    SET legacy_external_session_id='legacy-remote' WHERE id='handoff-1'
                    """)).hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET successor_managed=0 WHERE id='handoff-1'"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET successor_model_provider_id='p' WHERE id='handoff-1'"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET model_call_consumed=1 WHERE id='handoff-1'"))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET create_dispatch_attempted=1 "
                            + "WHERE id='handoff-1'"))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET state='PROMPTING' WHERE id='handoff-1'"))
                    .hasMessageContaining("CHECK constraint failed");
            connection.setAutoCommit(false);
            try {
                assertThat(statement.executeUpdate("""
                        UPDATE design_requirement_revision
                        SET model_calls_used=model_calls_used+1 WHERE id='r'
                        """)).isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate("""
                        UPDATE acceptance_candidate_legacy_handoff
                        SET state='HANDED_OFF',legacy_external_session_id='legacy-remote',
                          legacy_runtime_generation_id='legacy-generation',
                          legacy_endpoint_fingerprint='dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                          legacy_prompt_sha256='ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                          model_call_consumed=1,model_call_consumed_at='now'
                        WHERE id='handoff-1'
                        """)).hasMessageContaining("CHECK constraint failed");
                connection.rollback();
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            }
            try (var result = statement.executeQuery(
                    "SELECT model_calls_used FROM design_requirement_revision WHERE id='r'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_legacy_handoff
                    SET state='FAILED_STOPPED',legacy_external_session_id='legacy-remote',
                      legacy_runtime_generation_id='legacy-generation',
                      legacy_endpoint_fingerprint='dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
                    WHERE id='handoff-1'
                    """)).hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET successor_permission_policy_json='{}' "
                            + "WHERE id='handoff-1'"))
                    .isInstanceOf(java.sql.SQLException.class);
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_legacy_handoff
                    SET state='HANDED_OFF',old_external_state='ABORT_ACKNOWLEDGED',
                      old_termination_proof='ABORT_ACKNOWLEDGED',old_proof_at='now',
                      legacy_external_session_id='legacy-remote',legacy_runtime_generation_id='legacy-generation',
                      legacy_endpoint_fingerprint='dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                      legacy_external_state='PROMPTED',
                      legacy_prompt_sha256='ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                      legacy_prompt_dispatch_attempted=1,legacy_prompt_dispatch_started_at='now',
                      model_call_consumed=1,model_call_consumed_at='now'
                    WHERE id='handoff-1'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET old_termination_proof='ALREADY_ABSENT' "
                            + "WHERE id='handoff-1'"))
                    .hasMessageContaining("old termination proof is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET old_proof_at='later' WHERE id='handoff-1'"))
                    .hasMessageContaining("old termination proof is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_legacy_handoff
                    SET old_termination_proof=NULL,old_proof_at=NULL WHERE id='handoff-1'
                    """)).hasMessageContaining("old termination proof is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET model_call_consumed_at='later' "
                            + "WHERE id='handoff-1'"))
                    .hasMessageContaining("model call evidence is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_legacy_handoff
                    SET model_call_consumed=0,model_call_consumed_at=NULL WHERE id='handoff-1'
                    """)).hasMessageContaining("model call evidence is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET state='SETTLED' WHERE id='handoff-1'"))
                    .hasMessageContaining("CHECK constraint failed");
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_legacy_handoff
                    SET state='SETTLED',legacy_external_state='ABORT_ACKNOWLEDGED',
                      legacy_termination_proof='ABORT_ACKNOWLEDGED',legacy_proof_at='now'
                    WHERE id='handoff-1'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET legacy_termination_proof='REMOTE_COMPLETED' "
                            + "WHERE id='handoff-1'"))
                    .hasMessageContaining("legacy termination proof is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE acceptance_candidate_legacy_handoff SET legacy_proof_at='later' WHERE id='handoff-1'"))
                    .hasMessageContaining("legacy termination proof is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_legacy_handoff
                    SET legacy_termination_proof=NULL,legacy_proof_at=NULL WHERE id='handoff-1'
                    """)).hasMessageContaining("legacy termination proof is irreversible");
            try (var result = statement.executeQuery("""
                    SELECT state,legacy_termination_proof
                    FROM acceptance_candidate_legacy_handoff WHERE id='handoff-1'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("state")).isEqualTo("SETTLED");
                assertThat(result.getString("legacy_termination_proof")).isEqualTo("ABORT_ACKNOWLEDGED");
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_handoff_cleanup_remote(
                      handoff_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                      directory_sha256,title_sha256,state,created_at,updated_at,version)
                    VALUES('handoff-1','late-remote','legacy-generation',
                      'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                      'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                      'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                      'DISCOVERED','now','now',0)
                    """)).hasMessageContaining("cleanup remote parent is not accepting registrations");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_handoff_cleanup_remote SET directory_sha256=
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                    WHERE handoff_id='handoff-1' AND external_session_id='legacy-remote'
                    """)).hasMessageContaining("identity is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_handoff_cleanup_remote
                    SET termination_proof='ALREADY_ABSENT'
                    WHERE handoff_id='handoff-1' AND external_session_id='legacy-remote'
                    """)).hasMessageContaining("cleanup termination proof is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_handoff_cleanup_remote SET proof_at='later'
                    WHERE handoff_id='handoff-1' AND external_session_id='legacy-remote'
                    """)).hasMessageContaining("cleanup termination proof is irreversible");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_handoff_cleanup_remote
                    SET termination_proof=NULL,proof_at=NULL
                    WHERE handoff_id='handoff-1' AND external_session_id='legacy-remote'
                    """)).hasMessageContaining("cleanup termination proof is irreversible");
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('run-1','s','TASK_DECOMPOSITION','dec','DECOMPOSITION_PLAN_V2','PLANNING',1,0,'IN_PROCESS_LEGACY',
                      'DECOMPOSITION_PLAN_V2','generation-1','remote-1','OPEN',5,0,'now','now',0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('package-run','s','DESIGN_WORK_PACKAGE','wp','PACKAGE_DESIGN_V1','PACKAGE_DESIGN',1,0,'INTERNAL_MCP',
                      'PACKAGE_DESIGN_V1','generation-1','remote-1','FALLBACK_REQUIRED',3,3,
                      'package-attempt','now','now',0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      created_at)
                    VALUES('package-attempt','package-run',3,'attempt-3',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'FALLBACK_REQUIRED',0,'[]','{}','now')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,
                      workflow_step,source_revision,owner_version,submission_channel,contract_version,
                      runtime_generation_id,external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-package-owner','s','TASK_DECOMPOSITION','dec','PACKAGE_DESIGN_V1','PACKAGE_DESIGN',1,0,
                      'INTERNAL_MCP','PACKAGE_DESIGN_V1','generation-1','remote-1','OPEN',3,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,task_id,owner_type,owner_id,candidate_kind,
                      workflow_step,source_revision,owner_version,submission_channel,contract_version,
                      runtime_generation_id,external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-two-scopes','s','missing-task','TASK_DECOMPOSITION','dec','DECOMPOSITION_PLAN_V2',
                      'PLANNING',1,0,'IN_PROCESS_LEGACY','DECOMPOSITION_PLAN_V2','generation-1','remote-1',
                      'OPEN',5,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,
                      workflow_step,source_revision,owner_version,submission_channel,contract_version,
                      runtime_generation_id,external_session_id,state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-owner-scope','s-other','TASK_DECOMPOSITION','dec','DECOMPOSITION_PLAN_V2',
                      'PLANNING',1,0,'IN_PROCESS_LEGACY','DECOMPOSITION_PLAN_V2','generation-1','remote-1',
                      'OPEN',5,0,'now','now',0)
                    """))
                    .hasMessageContaining("candidate owner scope mismatch");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE ai_candidate_submission_run SET owner_id='cmp' WHERE id='run-1'"))
                    .hasMessageContaining("candidate owner and scope are immutable");
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }

            statement.executeUpdate("""
                    INSERT INTO task_decomposition(
                      id,designer_session_id,requirement_revision_id,state,source_draft_version,created_at,updated_at)
                    VALUES('dec-fk-probe','s','r','RUNNING',0,'now','now')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-generation','s','TASK_DECOMPOSITION','dec-fk-probe','DECOMPOSITION_PLAN_V2','FK_PROBE',1,0,'IN_PROCESS_LEGACY',
                      'DECOMPOSITION_PLAN_V2','generation-other','remote-1','OPEN',5,0,'now','now',0)
                    """))
                    .hasMessageContaining("FOREIGN KEY constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('missing-launch','s','LOOP_SPEC_COMPILATION','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CHOICE',1,0,'INTERNAL_MCP',
                      'ACCEPTANCE_CLOSED_CHOICE_V7','generation-1','remote-1','OPEN',2,0,'now','now',0)
                    """))
                    .hasMessageContaining("CREATED internal launch gate");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-owner','s','TASK_DECOMPOSITION','dec','PACKAGE_DESIGN_V1','PACKAGE_DESIGN',1,0,'INTERNAL_MCP',
                      'PACKAGE_DESIGN_V1','generation-1','remote-1','OPEN',3,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            statement.executeUpdate("DELETE FROM design_work_package WHERE id='wp'");
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM ai_candidate_submission_run "
                    + "WHERE id='package-run'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                    + "WHERE id='package-attempt'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-state','s','TASK_DECOMPOSITION','dec','DECOMPOSITION_PLAN_V2','PLANNING',1,0,'IN_PROCESS_LEGACY',
                      'DECOMPOSITION_PLAN_V2','generation-1','remote-1','FAILED',5,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('bad-channel','s','TASK_DECOMPOSITION','dec','DECOMPOSITION_PLAN_V2','PLANNING',1,0,'PUBLIC_MCP',
                      'DECOMPOSITION_PLAN_V2','generation-1','remote-1','OPEN',5,0,'now','now',0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
        }
    }

    @Test
    void upgradingFromV47PreservesRunsAttemptsIndexesAndForeignKeys() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v47-with-data.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("47")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                    VALUES('preserved-remote','preserved-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,task_decomposition_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('preserved-run','s','dec','DECOMPOSITION_PLAN_V2','PLANNING',1,0,
                      'IN_PROCESS_LEGACY','DECOMPOSITION_PLAN_V2','preserved-generation','preserved-remote',
                      'WAITING_INPUT',5,1,'preserved-attempt','now','now',1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      created_at)
                    VALUES('preserved-attempt','preserved-run',1,'key',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'WAITING_INPUT',0,'[]','{}','now')
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("55");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            try (var result = statement.executeQuery("SELECT owner_type,owner_id,state,version "
                    + "FROM ai_candidate_submission_run WHERE id='preserved-run'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("owner_type")).isEqualTo("TASK_DECOMPOSITION");
                assertThat(result.getString("owner_id")).isEqualTo("dec");
                assertThat(result.getString("state")).isEqualTo("WAITING_INPUT");
                assertThat(result.getLong("version")).isEqualTo(1);
            }
            try (var result = statement.executeQuery("SELECT outcome FROM ai_candidate_submission_attempt "
                    + "WHERE id='preserved-attempt'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("WAITING_INPUT");
            }
            List<String> indexes = new ArrayList<>();
            try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='index'")) {
                while (result.next()) indexes.add(result.getString(1));
            }
            assertThat(indexes).contains("ux_candidate_submission_open_owner",
                    "idx_candidate_submission_scope_state", "idx_candidate_submission_attempt_run");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      created_at)
                    VALUES('orphan','missing-run',1,'orphan',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'REJECTED',1,'[]','{}','now')
                    """))
                    .hasMessageContaining("FOREIGN KEY constraint failed");
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void upgradingFromV48PreservesPackageAcceptedResultAndMapsTypedScopeOwner() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v48-with-data.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("48")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                    VALUES('package-remote','package-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,design_work_package_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('preserved-package-run','s','wp','PACKAGE_DESIGN_V1','PACKAGE_DESIGN_V1',1,0,
                      'INTERNAL_MCP','PACKAGE_DESIGN_V1','package-generation','package-remote',
                      'ACCEPTED',3,1,'preserved-package-attempt','now','now',1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      canonical_result_sha256,created_at)
                    VALUES('preserved-package-attempt','preserved-package-run',1,'key',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'ACCEPTED',0,'[]','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO package_design_candidate_accepted_result(
                      candidate_run_id,design_work_package_id,source_revision,owner_version,contract_version,
                      canonical_candidate_json,canonical_markdown,compiled_result_json,canonical_result_sha256,
                      created_at,updated_at,version)
                    VALUES('preserved-package-run','wp',1,0,'PACKAGE_DESIGN_V1','{}','# Package','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now','now',0)
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("55");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            try (var result = statement.executeQuery("""
                    SELECT designer_session_id,task_id,project_id,owner_type,owner_id,state,version
                    FROM ai_candidate_submission_run WHERE id='preserved-package-run'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("designer_session_id")).isEqualTo("s");
                assertThat(result.getString("task_id")).isNull();
                assertThat(result.getString("project_id")).isNull();
                assertThat(result.getString("owner_type")).isEqualTo("DESIGN_WORK_PACKAGE");
                assertThat(result.getString("owner_id")).isEqualTo("wp");
                assertThat(result.getString("state")).isEqualTo("ACCEPTED");
                assertThat(result.getLong("version")).isEqualTo(1);
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                    + "WHERE run_id='preserved-package-run'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM package_design_candidate_accepted_result "
                    + "WHERE candidate_run_id='preserved-package-run'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void upgradingFromV48PreservesAcceptanceClosedChoiceRunAndAttempts() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v48-acceptance-with-data.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("48")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                    VALUES('acceptance-remote','acceptance-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      '2026-08-31T01:00:00Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,loop_spec_compilation_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('preserved-acceptance-run','s','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CLOSED_CHOICE',7,3,
                      'INTERNAL_MCP','ACCEPTANCE_CLOSED_CHOICE_V7','acceptance-generation','acceptance-remote',
                      'ACCEPTED',2,2,'preserved-acceptance-attempt-2',
                      '2026-08-31T01:00:01Z','2026-08-31T01:00:03Z',2)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,loop_spec_compilation_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('historical-closed-acceptance-run','s','cmp','ACCEPTANCE_CLOSED_CHOICE_V7','CLOSED_CHOICE',7,3,
                      'INTERNAL_MCP','ACCEPTANCE_CLOSED_CHOICE_V7','acceptance-generation','acceptance-remote',
                      'CLOSED',2,0,'2026-08-31T01:00:01Z','2026-08-31T01:00:03Z',1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      canonical_result_sha256,created_at)
                    VALUES('preserved-acceptance-attempt-1','preserved-acceptance-run',1,'choice-key-1',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'REJECTED',1,'[{"code":"CHOICE_INVALID"}]','{"outcome":"REJECTED"}',NULL,
                      '2026-08-31T01:00:02Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      canonical_result_sha256,created_at)
                    VALUES('preserved-acceptance-attempt-2','preserved-acceptance-run',2,'choice-key-2',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'ACCEPTED',0,'[]','{"outcome":"ACCEPTED"}',
                      'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                      '2026-08-31T01:00:03Z')
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("55");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            try (var result = statement.executeQuery("""
                    SELECT designer_session_id,task_id,project_id,owner_type,owner_id,candidate_kind,workflow_step,
                           source_revision,owner_version,submission_channel,contract_version,runtime_generation_id,
                           external_session_id,state,max_attempts,attempts_used,terminal_attempt_id,created_at,
                           updated_at,version
                    FROM ai_candidate_submission_run WHERE id='preserved-acceptance-run'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("designer_session_id")).isEqualTo("s");
                assertThat(result.getString("task_id")).isNull();
                assertThat(result.getString("project_id")).isNull();
                assertThat(result.getString("owner_type")).isEqualTo("LOOP_SPEC_COMPILATION");
                assertThat(result.getString("owner_id")).isEqualTo("cmp");
                assertThat(result.getString("candidate_kind")).isEqualTo("ACCEPTANCE_CLOSED_CHOICE_V7");
                assertThat(result.getString("workflow_step")).isEqualTo("CLOSED_CHOICE");
                assertThat(result.getInt("source_revision")).isEqualTo(7);
                assertThat(result.getInt("owner_version")).isEqualTo(3);
                assertThat(result.getString("submission_channel")).isEqualTo("INTERNAL_MCP");
                assertThat(result.getString("contract_version")).isEqualTo("ACCEPTANCE_CLOSED_CHOICE_V7");
                assertThat(result.getString("runtime_generation_id")).isEqualTo("acceptance-generation");
                assertThat(result.getString("external_session_id")).isEqualTo("acceptance-remote");
                assertThat(result.getString("state")).isEqualTo("ACCEPTED");
                assertThat(result.getInt("max_attempts")).isEqualTo(2);
                assertThat(result.getInt("attempts_used")).isEqualTo(2);
                assertThat(result.getString("terminal_attempt_id")).isEqualTo("preserved-acceptance-attempt-2");
                assertThat(result.getString("created_at")).isEqualTo("2026-08-31T01:00:01Z");
                assertThat(result.getString("updated_at")).isEqualTo("2026-08-31T01:00:03Z");
                assertThat(result.getInt("version")).isEqualTo(2);
            }
            try (var result = statement.executeQuery("""
                    SELECT state,close_reason FROM ai_candidate_submission_run
                    WHERE id='historical-closed-acceptance-run'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("state")).isEqualTo("CLOSED");
                assertThat(result.getString("close_reason")).isNull();
            }
            try (var result = statement.executeQuery("""
                    SELECT id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                           canonical_result_sha256,created_at
                    FROM ai_candidate_submission_attempt
                    WHERE run_id='preserved-acceptance-run' ORDER BY ordinal
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("id")).isEqualTo("preserved-acceptance-attempt-1");
                assertThat(result.getInt("ordinal")).isEqualTo(1);
                assertThat(result.getString("idempotency_key")).isEqualTo("choice-key-1");
                assertThat(result.getString("request_sha256")).isEqualTo("b".repeat(64));
                assertThat(result.getString("outcome")).isEqualTo("REJECTED");
                assertThat(result.getInt("retryable")).isEqualTo(1);
                assertThat(result.getString("problems_json")).isEqualTo("[{\"code\":\"CHOICE_INVALID\"}]");
                assertThat(result.getString("response_json")).isEqualTo("{\"outcome\":\"REJECTED\"}");
                assertThat(result.getString("canonical_result_sha256")).isNull();
                assertThat(result.getString("created_at")).isEqualTo("2026-08-31T01:00:02Z");
                assertThat(result.next()).isTrue();
                assertThat(result.getString("id")).isEqualTo("preserved-acceptance-attempt-2");
                assertThat(result.getInt("ordinal")).isEqualTo(2);
                assertThat(result.getString("idempotency_key")).isEqualTo("choice-key-2");
                assertThat(result.getString("request_sha256")).isEqualTo("c".repeat(64));
                assertThat(result.getString("outcome")).isEqualTo("ACCEPTED");
                assertThat(result.getInt("retryable")).isZero();
                assertThat(result.getString("problems_json")).isEqualTo("[]");
                assertThat(result.getString("response_json")).isEqualTo("{\"outcome\":\"ACCEPTED\"}");
                assertThat(result.getString("canonical_result_sha256")).isEqualTo("d".repeat(64));
                assertThat(result.getString("created_at")).isEqualTo("2026-08-31T01:00:03Z");
                assertThat(result.next()).isFalse();
            }
            // Acceptance has no separate accepted-result table; that table is package-design-only.
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='preserved-acceptance-run'")).isZero();
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void upgradingFromV48FailsClosedAndRollsBackWhenHistoricalOwnerCrossesScope() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v48-mismatched-owner.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("48")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertCandidateOwners(statement);
            statement.executeUpdate("""
                    INSERT INTO open_code_session_runtime_binding(
                      external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                    VALUES('dirty-remote','dirty-generation','MANAGED',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,design_work_package_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,terminal_attempt_id,created_at,updated_at,version)
                    VALUES('dirty-package-run','s-other','wp','PACKAGE_DESIGN_V1','PACKAGE_DESIGN_V1',1,0,
                      'INTERNAL_MCP','PACKAGE_DESIGN_V1','dirty-generation','dirty-remote',
                      'ACCEPTED',3,1,'dirty-package-attempt','now','now',1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                      canonical_result_sha256,created_at)
                    VALUES('dirty-package-attempt','dirty-package-run',1,'dirty-key',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'ACCEPTED',0,'[]','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now')
                    """);
            statement.executeUpdate("""
                    INSERT INTO package_design_candidate_accepted_result(
                      candidate_run_id,design_work_package_id,source_revision,owner_version,contract_version,
                      canonical_candidate_json,canonical_markdown,compiled_result_json,canonical_result_sha256,
                      created_at,updated_at,version)
                    VALUES('dirty-package-run','wp',1,0,'PACKAGE_DESIGN_V1','{}','# Dirty package','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now','now',0)
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        assertThatThrownBy(flyway::migrate)
                .rootCause().hasMessageContaining("candidate owner scope mismatch");

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("48");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            List<String> runColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_submission_run)")) {
                while (result.next()) runColumns.add(result.getString("name"));
            }
            assertThat(runColumns).contains("design_work_package_id").doesNotContain("owner_type", "owner_id");
            assertThat(count(statement, "ai_candidate_submission_run", "id='dirty-package-run'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_attempt", "id='dirty-package-attempt'")).isEqualTo(1);
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='dirty-package-run'")).isEqualTo(1);
            assertThat(count(statement, "sqlite_master", "type='table' AND name LIKE '%_v48'")).isZero();
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @ParameterizedTest(name = "deleting {0} owner cascades its candidate records")
    @MethodSource("candidateOwnerDeleteCases")
    void deletingEveryNonAcceptanceConstructibleOwnerCascadesCandidateRecords(OwnerDeleteCase owner)
            throws Exception {
        assertThat(owner.candidateKind()).isNotEqualTo("ACCEPTANCE_CLOSED_CHOICE_V7");
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve(
                "delete-owner-" + owner.ownerType().toLowerCase() + ".db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertAllCandidateOwners(statement);
            insertSharedRuntimeBinding(statement);
            insertCandidateRunAttemptAndOptionalResult(statement, owner, "delete-run");

            assertThat(count(statement, owner.ownerTable(), "id='" + owner.ownerId() + "'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_run", "id='delete-run'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_attempt", "run_id='delete-run'")).isEqualTo(1);
            if (owner.hasAcceptedResult()) {
                assertThat(count(statement, "package_design_candidate_accepted_result",
                        "candidate_run_id='delete-run'")).isEqualTo(1);
            }

            statement.executeUpdate("DELETE FROM " + owner.ownerTable() + " WHERE id='" + owner.ownerId() + "'");

            assertThat(count(statement, "ai_candidate_submission_run", "id='delete-run'")).isZero();
            assertThat(count(statement, "ai_candidate_submission_attempt", "run_id='delete-run'")).isZero();
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='delete-run'")).isZero();
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void ownerDeleteCascadeParticipatesInTheCallingTransactionRollback() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("owner-delete-rollback.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        OwnerDeleteCase owner = candidateOwnerDeleteCases()
                .map(argument -> (OwnerDeleteCase) argument.get()[0])
                .filter(candidate -> candidate.hasAcceptedResult())
                .findFirst().orElseThrow();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertAllCandidateOwners(statement);
            insertSharedRuntimeBinding(statement);
            insertCandidateRunAttemptAndOptionalResult(statement, owner, "rollback-run");

            connection.setAutoCommit(false);
            statement.executeUpdate("DELETE FROM design_work_package WHERE id='wp'");
            assertThat(count(statement, "design_work_package", "id='wp'")).isZero();
            assertThat(count(statement, "ai_candidate_submission_run", "id='rollback-run'")).isZero();
            assertThat(count(statement, "ai_candidate_submission_attempt", "run_id='rollback-run'")).isZero();
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='rollback-run'")).isZero();
            connection.rollback();

            assertThat(count(statement, "design_work_package", "id='wp'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_run", "id='rollback-run'")).isEqualTo(1);
            assertThat(count(statement, "ai_candidate_submission_attempt", "run_id='rollback-run'")).isEqualTo(1);
            assertThat(count(statement, "package_design_candidate_accepted_result",
                    "candidate_run_id='rollback-run'")).isEqualTo(1);
            connection.setAutoCommit(true);
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static Stream<Arguments> candidateOwnerDeleteCases() {
        return Stream.of(
                Arguments.of(new OwnerDeleteCase("TASK_DECOMPOSITION", "dec", "task_decomposition",
                        "designer_session_id", "s", "DECOMPOSITION_PLAN_V2", 5, false)),
                Arguments.of(new OwnerDeleteCase("DESIGN_WORK_PACKAGE", "wp", "design_work_package",
                        "designer_session_id", "s", "PACKAGE_DESIGN_V1", 3, true)),
                Arguments.of(new OwnerDeleteCase("TASK_PACKAGE_PLAN_REVISION", "plan", "task_package_plan_revision",
                        "task_id", "task", "ROLLING_PACKAGE_PLAN_V1", 3, false)),
                Arguments.of(new OwnerDeleteCase("ANALYSIS_REPORT", "report", "analysis_report",
                        "designer_session_id", "s", "REVIEWER_REPORT_V1", 3, false)),
                Arguments.of(new OwnerDeleteCase("PROJECT_CONVENTION_DRAFT", "convention",
                        "project_convention_draft", "project_id", "p", "PROJECT_CONVENTION_V1", 3, false)),
                Arguments.of(new OwnerDeleteCase("JUDGE_RUN", "judge", "judge_run",
                        "task_id", "task", "JUDGE_DECISION_V1", 2, false)));
    }

    private void insertCandidateRunAttemptAndOptionalResult(
            java.sql.Statement statement, OwnerDeleteCase owner, String runId) throws Exception {
        String attemptId = runId + "-attempt";
        statement.executeUpdate("""
                INSERT INTO ai_candidate_submission_run(
                  id,%s,owner_type,owner_id,candidate_kind,workflow_step,source_revision,owner_version,
                  submission_channel,contract_version,runtime_generation_id,external_session_id,state,max_attempts,
                  attempts_used,terminal_attempt_id,created_at,updated_at,version)
                VALUES('%s','%s','%s','%s','%s','OWNER_DELETE',1,0,'IN_PROCESS_LEGACY','%s',
                  'shared-generation','shared-remote','ACCEPTED',%d,1,'%s','now','now',1)
                """.formatted(owner.scopeColumn(), runId, owner.scopeId(), owner.ownerType(), owner.ownerId(),
                owner.candidateKind(), owner.candidateKind(), owner.maxAttempts(), attemptId));
        statement.executeUpdate("""
                INSERT INTO ai_candidate_submission_attempt(
                  id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,
                  canonical_result_sha256,created_at)
                VALUES('%s','%s',1,'owner-delete-key',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                  'ACCEPTED',0,'[]','{}',
                  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now')
                """.formatted(attemptId, runId));
        if (owner.hasAcceptedResult()) {
            statement.executeUpdate("""
                    INSERT INTO package_design_candidate_accepted_result(
                      candidate_run_id,design_work_package_id,source_revision,owner_version,contract_version,
                      canonical_candidate_json,canonical_markdown,compiled_result_json,canonical_result_sha256,
                      created_at,updated_at,version)
                    VALUES('%s','wp',1,0,'PACKAGE_DESIGN_V1','{}','# Package','{}',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','now','now',0)
                    """.formatted(runId));
        }
    }

    private void insertSharedRuntimeBinding(java.sql.Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO open_code_session_runtime_binding(
                  external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,created_at)
                VALUES('shared-remote','shared-generation','MANAGED',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','now')
                """);
    }

    private void insertAllCandidateOwners(java.sql.Statement statement) throws Exception {
        insertCandidateOwners(statement);
        statement.executeUpdate("""
                INSERT INTO task(id,project_id,loop_draft_id,title,state,created_at,updated_at)
                VALUES('task','p','d','Task','PENDING_START','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,
                  deliverables_json,verifiers_json,state,created_at,updated_at)
                VALUES('stage','task',0,'Stage','[]','[]','[]','[]','RUNNING','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO attempt(id,task_id,stage_id,ordinal,state,created_at)
                VALUES('task-attempt','task','stage',1,'RUNNING','now')
                """);
        statement.executeUpdate("""
                INSERT INTO task_package_plan_revision(
                  id,task_id,designer_session_id,requirement_revision_id,revision,state,origin,plan_json,
                  base_task_version,base_package_version,created_at,updated_at)
                VALUES('plan','task','s','r',1,'PROPOSED','AI','{}',0,0,'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO designer_task_profile(
                  id,designer_session_id,requirement_revision_id,state,intent,workflow_template,mutation_mode,
                  artifact_kinds_json,technologies_json,test_policy,execution_strategy,role_pack_id,
                  role_pack_version,confidence,evidence_json,resolution_source,decision_required,created_at,updated_at)
                VALUES('profile','s','r','FROZEN','SOFTWARE_CHANGE','DIRECT_SOFTWARE_DESIGN','WRITE',
                  '[]','[]','REQUIRED','OPENCODE','software-java','2026-08-dynamic-v7',90,'[]',
                  'USER_CONFIRMED',0,'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO analysis_report(
                  id,designer_session_id,task_profile_id,state,title,markdown,evidence_json,created_at,updated_at)
                VALUES('report','s','profile','READY','Report','# Report','[]','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO project_convention_draft(
                  id,project_id,state,source_exists,source_sha256,source_content,created_at,updated_at)
                VALUES('convention','p','READY',0,
                  'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd','',
                  'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO judge_run(id,task_id,attempt_id,role,ordinal,state,created_at)
                VALUES('judge','task','task-attempt','REQUIREMENT',1,'RUNNING','now')
                """);
    }

    private record OwnerDeleteCase(
            String ownerType,
            String ownerId,
            String ownerTable,
            String scopeColumn,
            String scopeId,
            String candidateKind,
            int maxAttempts,
            boolean hasAcceptedResult) {}

    private int count(java.sql.Statement statement, String table, String predicate) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private void insertLegacyManagedSession(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) "
                    + "VALUES('legacy-p','Legacy','/tmp/legacy','now','now')");
            statement.executeUpdate("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                    + "VALUES('legacy-d','legacy-p','G','{}','DRAFT_READY','now','now')");
            statement.executeUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,"
                    + "external_session_id,created_at,updated_at) VALUES('legacy-s','legacy-p','RUNNING','READ_ONLY',"
                    + "'legacy-d','legacy-designer-remote','now','now')");
        }
    }

    private void insertCandidateOwners(java.sql.Statement statement) throws Exception {
        statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) "
                + "VALUES('p','P','/tmp/p','now','now')");
        statement.executeUpdate("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                + "VALUES('d','p','G','{}','DRAFT_READY','now','now')");
        statement.executeUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at) "
                + "VALUES('s','p','RUNNING','READ_ONLY','d','now','now')");
        statement.executeUpdate("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                + "VALUES('d-other','p','G','{}','DRAFT_READY','now','now')");
        statement.executeUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at) "
                + "VALUES('s-other','p','RUNNING','READ_ONLY','d-other','now','now')");
        statement.executeUpdate("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) "
                + "VALUES('m','s',1,'ASSISTANT','design','PERSISTED','now')");
        statement.executeUpdate("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,"
                + "requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) "
                + "VALUES('r','s',1,'m','requirement','[]',0,'ACTIVE','now','now')");
        statement.executeUpdate("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,"
                + "source_draft_version,created_at,updated_at) VALUES('dec','s','r','RUNNING',0,'now','now')");
        statement.executeUpdate("INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,"
                + "source_design_message_id,source_draft_version,work_package_id,created_at,updated_at) "
                + "VALUES('cmp','s',1,'RUNNING','m',0,'WP-1','now','now')");
        statement.executeUpdate("INSERT INTO design_work_package(id,designer_session_id,requirement_revision_id,"
                + "decomposition_id,package_id,ordinal,title,objective,scope_in_json,scope_out_json,dependencies_json,"
                + "deliverables_json,acceptance_intent_json,requirement_refs_json,state,design_revision,created_at,updated_at) "
                + "VALUES('wp','s','r','dec','WP-1',0,'Package','Deliver','[]','[]','[]','[]','[]','[]',"
                + "'DESIGNING',1,'now','now')");
        statement.executeUpdate("UPDATE design_work_package SET design_message_id='m' WHERE id='wp'");
    }

    private void insertSettledInternalAcceptanceRun(
            java.sql.Connection connection, java.sql.Statement statement) throws Exception {
        statement.executeUpdate("UPDATE loop_spec_compilation SET state='PENDING_HANDOFF',"
                + "external_session_id=NULL,external_session_state=NULL,version=4 WHERE id='cmp'");
        statement.executeUpdate("""
                INSERT INTO design_acceptance_planning(
                  compilation_id,designer_session_id,work_package_id,design_revision,contract_version,
                  design_sha256,state,facts_json,capabilities_json,binding_json,diagnostics_json,
                  created_at,updated_at,version,binding_source)
                VALUES('cmp','s','WP-1',1,'DESIGN_ACCEPTANCE_V7',
                  'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                  'EXTRACTED','[]','[]','{"selection":1}','[]','now','now',3,'AI_DISAMBIGUATION_V6')
                """);
        statement.executeUpdate("""
                INSERT INTO acceptance_candidate_internal_launch(
                  id,compilation_id,designer_session_id,work_package_id,source_design_revision,
                  source_design_message_id,source_draft_version,source_design_sha256,
                  planning_version,planning_binding_source,planning_binding_json,planning_binding_sha256,
                  route_plan_json,route_plan_sha256,candidate_run_id,contract_version,workflow_step,state,
                  prepared_owner_version,exact_title,canonical_directory,runtime_generation_id,managed,
                  internal_mcp_server,endpoint_fingerprint,profile,permission_policy_json,
                  permission_policy_digest,create_request_sha256,creation_credential,attestation_type,
                  created_at,updated_at,version)
                VALUES('initial-launch','cmp','s','WP-1',1,'m',0,
                  'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                  3,'AI_DISAMBIGUATION_V6','{"selection":1}',
                  'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                  '{"candidates":[0,1]}',
                  'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                  'initial-run','ACCEPTANCE_CLOSED_CHOICE_V7','ACCEPTANCE_CLOSED_CHOICE_V7','PREPARED',4,
                  'Acceptance initial','/tmp/p','initial-generation',1,'loopper_internal_acceptance',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS','[]',
                  '1111111111111111111111111111111111111111111111111111111111111111',
                  '2222222222222222222222222222222222222222222222222222222222222222',
                  'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','LOCAL_REQUEST_ATTESTED','now','now',0)
                """);
        statement.executeUpdate("""
                UPDATE acceptance_candidate_internal_launch
                SET state='CREATED',create_claim_owner='worker',create_claim_token='claim',
                  create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                  create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                  external_session_id='initial-remote',external_attested_at='attested-at',version=1
                WHERE id='initial-launch'
                """);
        statement.executeUpdate("""
                UPDATE loop_spec_compilation
                SET state='RUNNING',external_session_id='initial-remote',
                  external_session_state='CANDIDATE_PROMPT_PENDING',version=5 WHERE id='cmp'
                """);
        connection.setAutoCommit(false);
        try {
            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_run(
                      id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                      owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                      state,max_attempts,attempts_used,created_at,updated_at,version)
                    VALUES('initial-run','s','LOOP_SPEC_COMPILATION','cmp','ACCEPTANCE_CLOSED_CHOICE_V7',
                      'ACCEPTANCE_CLOSED_CHOICE_V7',1,5,'INTERNAL_MCP','ACCEPTANCE_CLOSED_CHOICE_V7',
                      'initial-generation','initial-remote','OPEN',2,0,'now','now',0)
                    """);
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='SETTLED',settled_owner_version=5,settled_at='settled-at',
                      create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,version=2
                    WHERE id='initial-launch'
                    """);
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
