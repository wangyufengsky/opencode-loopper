package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcceptanceCandidateInternalLaunchMigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void migratesFreshAndV51DatabasesWithCleanLaunchForeignKeys() throws Exception {
        verifyMigration(temporaryDirectory.resolve("fresh.db"), false);
        verifyMigration(temporaryDirectory.resolve("v51.db"), true);
    }

    @Test
    void upgradesExistingV53CleanupRowsAsLaunchAmbiguity() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("v53-with-cleanup.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("53")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertOwnerFixture(statement);
            insertCompilationAndPlanning(statement, "cmp-1");
            statement.executeUpdate(launchInsert("launch-1", "cmp-1", "run-1", 0));
            statement.executeUpdate("UPDATE acceptance_candidate_internal_launch SET state='STOPPING' "
                    + "WHERE id='launch-1'");
            insertBinding(statement, "cleanup-remote");
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_launch_cleanup_remote(
                      launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                      directory_sha256,title_sha256,state,created_at,updated_at)
                    VALUES('launch-1','cleanup-remote','generation-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'DISCOVERED','now','now')
                    """);
        }

        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT purpose,termination_intent_id
                     FROM acceptance_candidate_internal_launch_cleanup_remote
                     WHERE launch_id='launch-1'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("purpose")).isEqualTo("LAUNCH_AMBIGUITY");
            assertThat(result.getString("termination_intent_id")).isNull();
        }
    }

    @Test
    void anchorsLaunchAndMakesCreateSettlementAndCleanupEvidenceIrreversible() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("constraints.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertOwnerFixture(statement);
            insertCompilationAndPlanning(statement, "cmp-1");

            assertThatThrownBy(() -> statement.executeUpdate(launchInsert("bad", "cmp-1", "run-bad", 1)))
                    .hasMessageContaining("owner/source/planning anchor mismatch");
            statement.executeUpdate(launchInsert("launch-1", "cmp-1", "run-1", 0));
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET route_plan_json='{"changed":true}' WHERE id='launch-1'
                    """)).hasMessageContaining("identity and plan are immutable");

            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET create_claim_owner='worker',create_claim_token='claim-1',
                      create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,version=version+1
                    WHERE id='launch-1'
                    """);
            try (var result = statement.executeQuery("""
                    SELECT state,create_dispatch_attempted FROM acceptance_candidate_internal_launch
                    WHERE id='launch-1'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("state")).isEqualTo("PREPARED");
                assertThat(result.getInt("create_dispatch_attempted")).isZero();
            }
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='CREATING',create_dispatch_attempted=1,
                      create_dispatch_started_at='2026-09-01T10:00:00Z',version=version+1
                    WHERE id='launch-1'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET create_dispatch_attempted=0,create_dispatch_started_at=NULL WHERE id='launch-1'
                    """)).hasMessageContaining("create checkpoint is irreversible");

            insertBinding(statement, "remote-1");
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='CREATED',external_session_id='remote-1',external_attested_at='attested-at',
                      version=version+1 WHERE id='launch-1'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET external_attested_at='changed' WHERE id='launch-1'
                    """)).hasMessageContaining("remote attestation is irreversible");
            attachCompilation(statement, "cmp-1", "remote-1");
            openAndSettle(connection, statement, "run-1", "cmp-1", "remote-1", "launch-1");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch SET settled_at='changed' WHERE id='launch-1'
                    """)).hasMessageContaining("settlement is irreversible");

            insertCompilationAndPlanning(statement, "cmp-2");
            statement.executeUpdate(launchInsert("launch-2", "cmp-2", "run-2", 0));
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='STOPPING',create_claim_owner='worker',create_claim_token='claim-2',
                      create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                      create_dispatch_attempted=1,create_dispatch_started_at='dispatch-2',version=version+1
                    WHERE id='launch-2'
                    """);
            insertBinding(statement, "cleanup-remote");
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_launch_cleanup_remote(
                      launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                      directory_sha256,title_sha256,state,created_at,updated_at)
                    VALUES('launch-2','cleanup-remote','generation-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'DISCOVERED','now','now')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    DELETE FROM acceptance_candidate_internal_launch_cleanup_remote
                    WHERE launch_id='launch-2' AND external_session_id='cleanup-remote'
                    """)).hasMessageContaining("live Acceptance internal cleanup");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='FAILED_STOPPED',create_claim_owner=NULL,create_claim_token=NULL,
                      create_claim_expires_at=NULL WHERE id='launch-2'
                    """)).hasMessageContaining("cleanup remotes must be stopped");
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch_cleanup_remote
                    SET state='STOPPING',stop_claim_owner='worker',stop_claim_token='stop-1',
                      stop_claim_expires_at='2099-01-01T00:00:00Z',stop_fence=1,version=version+1
                    WHERE launch_id='launch-2' AND external_session_id='cleanup-remote'
                    """);
            try (var result = statement.executeQuery("""
                    SELECT stop_dispatch_attempted FROM acceptance_candidate_internal_launch_cleanup_remote
                    WHERE launch_id='launch-2' AND external_session_id='cleanup-remote'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch_cleanup_remote
                    SET stop_dispatch_attempted=1,stop_dispatch_started_at='stop-dispatch',version=version+1
                    WHERE launch_id='launch-2' AND external_session_id='cleanup-remote'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch_cleanup_remote
                    SET stop_dispatch_attempted=0,stop_dispatch_started_at=NULL
                    WHERE launch_id='launch-2' AND external_session_id='cleanup-remote'
                    """)).hasMessageContaining("stop checkpoint is irreversible");
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch_cleanup_remote
                    SET state='STOPPED',termination_proof='ABORT_ACKNOWLEDGED',proof_at='proof-at',
                      stop_claim_owner=NULL,stop_claim_token=NULL,stop_claim_expires_at=NULL,version=version+1
                    WHERE launch_id='launch-2' AND external_session_id='cleanup-remote'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch_cleanup_remote
                    SET termination_proof=NULL,proof_at=NULL
                    WHERE launch_id='launch-2' AND external_session_id='cleanup-remote'
                    """)).hasMessageContaining("termination proof is irreversible");
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='FAILED_STOPPED',create_claim_owner=NULL,create_claim_token=NULL,
                      create_claim_expires_at=NULL,version=version+1 WHERE id='launch-2'
                    """);
        }
    }

    @Test
    void failsClosedForRunPromptAndParentCascadeBypasses() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("protocol-gates.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertOwnerFixture(statement);
            insertCompilationAndPlanning(statement, "cmp-1");
            statement.executeUpdate(launchInsert("launch-1", "cmp-1", "run-1", 0));

            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE loop_spec_compilation SET state='SESSION_ERROR' WHERE id='cmp-1'"))
                    .hasMessageContaining("nonterminal Acceptance candidate protocol");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE designer_session SET state='CANCELLED' WHERE id='s'"))
                    .hasMessageContaining("nonterminal Acceptance candidate protocol");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "DELETE FROM loop_spec_compilation WHERE id='cmp-1'"))
                    .hasMessageContaining("nonterminal Acceptance candidate protocol");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM designer_session WHERE id='s'"))
                    .hasMessageContaining("nonterminal Acceptance candidate protocol");

            insertBinding(statement, "remote-1");
            markLaunchCreated(statement, "launch-1", "remote-1");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_launch_run_requirement(
                      candidate_run_id,launch_id,created_at)
                    VALUES('run-1','launch-1','forged')
                    """)).hasMessageContaining("cannot be forged");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_launch_settlement_certificate(
                      launch_id,candidate_run_id,settled_owner_version,settled_at)
                    VALUES('launch-1','run-1',5,'forged')
                    """)).hasMessageContaining("cannot be forged");
            assertThatThrownBy(() -> statement.executeUpdate(runInsert(
                    "run-1", "cmp-1", "INTERNAL_MCP", "remote-1")))
                    .hasMessageContaining("CREATED internal launch gate");
            assertThatThrownBy(() -> statement.executeUpdate(runInsert(
                    "legacy-bypass", "cmp-1", "IN_PROCESS_LEGACY", "remote-1")))
                    .hasMessageContaining("LEGACY_CREATED handoff gate");

            attachCompilation(statement, "cmp-1", "remote-1");
            openAndSettle(connection, statement, "run-1", "cmp-1", "remote-1", "launch-1");

            assertThatThrownBy(() -> statement.executeUpdate(promptInsert(
                    "prompt-missing-launch", "run-1", null, "INITIAL", null, "message-missing")))
                    .hasMessageContaining("SETTLED internal launch gate");
            statement.executeUpdate(promptInsert(
                    "prompt-initial", "run-1", "launch-1", "INITIAL", null, "message-initial"));

            statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                      problems_json,response_json,created_at)
                    VALUES('attempt-1','run-1',1,'key-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'REJECTED',1,'[]','{}','now')
                    """);
            statement.executeUpdate("UPDATE ai_candidate_submission_run SET attempts_used=1 WHERE id='run-1'");

            assertThatThrownBy(() -> statement.executeUpdate(promptInsert(
                    "prompt-unacked-initial", "run-1", "launch-1", "CORRECTION", 1, "message-unacked")))
                    .hasMessageContaining("requires acknowledged INITIAL");
            statement.executeUpdate("""
                    UPDATE ai_candidate_prompt_dispatch
                    SET dispatch_attempted=1,dispatch_started_at='posted-at',acknowledged=1,
                      acked_at='acked-at',state='ACKNOWLEDGED',updated_at='acked-at',version=version+1
                    WHERE id='prompt-initial'
                    """);

            insertCompilationAndPlanning(statement, "cmp-2");
            statement.executeUpdate(launchInsert("launch-2", "cmp-2", "run-2", 0));
            insertBinding(statement, "remote-2");
            markLaunchCreated(statement, "launch-2", "remote-2");
            attachCompilation(statement, "cmp-2", "remote-2");
            openAndSettle(connection, statement, "run-2", "cmp-2", "remote-2", "launch-2");

            assertThatThrownBy(() -> statement.executeUpdate(promptInsert(
                    "prompt-wrong-launch", "run-1", "launch-2", "CORRECTION", 1, "message-wrong")))
                    .hasMessageContaining("exact SETTLED internal launch gate");
            statement.executeUpdate(promptInsert(
                    "prompt-correction", "run-1", "launch-1", "CORRECTION", 1, "message-correction"));

            assertThatThrownBy(() -> statement.executeUpdate(
                    "DELETE FROM ai_candidate_prompt_dispatch WHERE id='prompt-correction'"))
                    .hasMessageContaining("live candidate prompt dispatch");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "DELETE FROM ai_candidate_submission_run WHERE id='run-1'"))
                    .hasMessageContaining("live Acceptance candidate run");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "DELETE FROM acceptance_candidate_internal_launch WHERE id='launch-1'"))
                    .hasMessageContaining("live Acceptance internal launch");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch_run_requirement
                    SET created_at='changed' WHERE candidate_run_id='run-1'
                    """)).hasMessageContaining("requirement is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    DELETE FROM acceptance_candidate_internal_launch_settlement_certificate
                    WHERE launch_id='launch-1'
                    """)).hasMessageContaining("certificate cannot be deleted");

            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE loop_spec_compilation SET state='COMPLETED' WHERE id='cmp-1'"))
                    .hasMessageContaining("nonterminal Acceptance candidate protocol");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "DELETE FROM loop_spec_compilation WHERE id='cmp-1'"))
                    .hasMessageContaining("nonterminal Acceptance candidate protocol");
        }
    }

    @Test
    void durableTerminationIntentFencesProgressAndKeepsLegacyCleanupPurposeCompatible() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("termination-intent.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertOwnerFixture(statement);
            insertCompilationAndPlanning(statement, "cmp-1");
            statement.executeUpdate(launchInsert("launch-1", "cmp-1", "run-1", 0));

            statement.executeUpdate("UPDATE designer_session SET state='STOPPING' WHERE id='s'");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_termination_intent(
                      id,launch_id,designer_session_id,compilation_id,candidate_run_id,kind,target_state,parent_action,state,
                      anchor_designer_version,created_at,updated_at)
                    VALUES('bad-anchor','launch-1','s','cmp-1','run-1','DESIGNER_CANCEL','CANCELLED',
                      'DESIGNER_CANCEL','REQUESTED',1,'now','now')
                    """)).hasMessageContaining("owner anchor mismatch");
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_termination_intent(
                      id,launch_id,designer_session_id,compilation_id,candidate_run_id,kind,target_state,parent_action,state,
                      anchor_designer_version,created_at,updated_at)
                    VALUES('intent-1','launch-1','s','cmp-1','run-1','DESIGNER_CANCEL','CANCELLED',
                      'DESIGNER_CANCEL','REQUESTED',0,'now','now')
                    """);
            try (var result = statement.executeQuery("""
                    SELECT archive_when_complete FROM acceptance_candidate_internal_termination_intent
                    WHERE id='intent-1'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_termination_intent
                    SET archive_when_complete=1 WHERE id='intent-1'
                    """)).hasMessageContaining("identity is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='CREATING',create_claim_owner='worker',create_claim_token='claim',
                      create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                      create_dispatch_attempted=1,create_dispatch_started_at='dispatch'
                    WHERE id='launch-1'
                    """)).hasMessageContaining("blocks launch progress");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_termination_intent
                    SET kind='OWNER_REPLACEMENT' WHERE id='intent-1'
                    """)).hasMessageContaining("identity is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_termination_intent
                    SET state='COMPLETED',ready_at='ready',completed_at='done' WHERE id='intent-1'
                    """)).hasMessageContaining("cancellation parent is not terminal");

            statement.executeUpdate("UPDATE acceptance_candidate_internal_launch SET state='STOPPING' "
                    + "WHERE id='launch-1'");
            insertBinding(statement, "cleanup-remote");
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_launch_cleanup_remote(
                      launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                      directory_sha256,title_sha256,state,created_at,updated_at)
                    VALUES('launch-1','cleanup-remote','generation-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'DISCOVERED','now','now')
                    """);
            try (var result = statement.executeQuery("""
                    SELECT purpose,termination_intent_id
                    FROM acceptance_candidate_internal_launch_cleanup_remote
                    WHERE launch_id='launch-1'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("purpose")).isEqualTo("LAUNCH_AMBIGUITY");
                assertThat(result.getString("termination_intent_id")).isNull();
            }
        }
    }

    @Test
    void settledLaunchRequiresRunAndRemoteProofBeforeIntentCanComplete() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("settled-termination.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertOwnerFixture(statement);
            insertCompilationAndPlanning(statement, "cmp-1");
            statement.executeUpdate(launchInsert("launch-1", "cmp-1", "run-1", 0));
            insertBinding(statement, "remote-1");
            markLaunchCreated(statement, "launch-1", "remote-1");
            attachCompilation(statement, "cmp-1", "remote-1");
            openAndSettle(connection, statement, "run-1", "cmp-1", "remote-1", "launch-1");
            statement.executeUpdate("UPDATE designer_session SET state='STOPPING' WHERE id='s'");
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_termination_intent(
                      id,launch_id,designer_session_id,compilation_id,candidate_run_id,kind,target_state,parent_action,state,
                      anchor_designer_version,created_at,updated_at)
                    VALUES('intent-1','launch-1','s','cmp-1','run-1','DESIGNER_CANCEL','CANCELLED',
                      'DESIGNER_CANCEL','REQUESTED',0,'now','now')
                    """);

            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='CANCELLED',settled_owner_version=NULL,settled_at=NULL,
                      termination_proof='ABORT_ACKNOWLEDGED',proof_at='proof'
                    WHERE id='launch-1'
                    """)).hasMessageContaining("run must be terminal");
            statement.executeUpdate("UPDATE ai_candidate_submission_run SET state='CLOSED' WHERE id='run-1'");
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_launch_cleanup_remote(
                      launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                      directory_sha256,title_sha256,purpose,termination_intent_id,state,
                      termination_proof,proof_at,created_at,updated_at)
                    VALUES('launch-1','remote-1','generation-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'TERMINATION_INTENT','intent-1','STOPPED','ABORT_ACKNOWLEDGED','proof','now','now')
                    """);
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='CANCELLED',settled_owner_version=NULL,settled_at=NULL,
                      termination_proof='ABORT_ACKNOWLEDGED',proof_at='proof',version=version+1
                    WHERE id='launch-1'
                    """);
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_termination_intent
                    SET state='READY',ready_at='ready',updated_at='ready',version=version+1
                    WHERE id='intent-1'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_termination_intent
                    SET state='COMPLETED',completed_at='done' WHERE id='intent-1'
                    """)).hasMessageContaining("parent is not terminal");
            statement.executeUpdate("UPDATE designer_session SET state='CANCELLED' WHERE id='s'");
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_termination_intent
                    SET state='COMPLETED',completed_at='done',updated_at='done',version=version+1
                    WHERE id='intent-1'
                    """);
        }
    }

    @Test
    void initialPromptFailureReasonsRequireExactEvidenceAndKeepUserPrioritiesRecoverable() throws Exception {
        verifyInitialFailure("budget", "BUDGET_EXHAUSTED", "RESULT_UNKNOWN");
        verifyInitialFailure("lookup", "LOOKUP_UNSUPPORTED", "RESULT_UNKNOWN");
        verifyInitialFailure("unknown", "RESULT_UNKNOWN", "LOOKUP_UNSUPPORTED");
    }

    @Test
    void v55PreservesV54IntentAndTerminationCleanupForeignKeys() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("v54-intent-upgrade.db");
        Flyway.configure().dataSource(url, null, null)
                .target(MigrationVersion.fromVersion("54")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertOwnerFixture(statement);
            insertCompilationAndPlanning(statement, "cmp-1");
            statement.executeUpdate(launchInsert("launch-1", "cmp-1", "run-1", 0));
            statement.executeUpdate("UPDATE designer_session SET state='STOPPING' WHERE id='s'");
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_termination_intent(
                      id,launch_id,designer_session_id,compilation_id,candidate_run_id,kind,target_state,
                      archive_when_complete,state,anchor_designer_version,created_at,updated_at,version)
                    VALUES('intent-1','launch-1','s','cmp-1','run-1','DESIGNER_CANCEL','CANCELLED',
                      1,'REQUESTED',0,'now','now',0)
                    """);
            statement.executeUpdate("UPDATE acceptance_candidate_internal_launch SET state='STOPPING' "
                    + "WHERE id='launch-1'");
            insertBinding(statement, "cleanup-remote");
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_launch_cleanup_remote(
                      launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                      directory_sha256,title_sha256,purpose,termination_intent_id,state,created_at,updated_at)
                    VALUES('launch-1','cleanup-remote','generation-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'TERMINATION_INTENT','intent-1','DISCOVERED','now','now')
                    """);
        }

        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            try (var result = statement.executeQuery("""
                    SELECT archive_when_complete,reason_code FROM acceptance_candidate_internal_termination_intent
                    WHERE id='intent-1'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("archive_when_complete")).isEqualTo(1);
                assertThat(result.getString("reason_code")).isNull();
            }
            assertThat(statement.executeQuery("""
                    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote
                    WHERE termination_intent_id='intent-1' AND state='DISCOVERED'
                    """).next()).isTrue();
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void verifyInitialFailure(String suffix, String reason, String wrongReason) throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("initial-" + suffix + ".db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            insertOwnerFixture(statement);
            insertCompilationAndPlanning(statement, "cmp-1");
            statement.executeUpdate(launchInsert("launch-1", "cmp-1", "run-1", 0));
            insertBinding(statement, "remote-1");
            markLaunchCreated(statement, "launch-1", "remote-1");
            attachCompilation(statement, "cmp-1", "remote-1");
            openAndSettle(connection, statement, "run-1", "cmp-1", "remote-1", "launch-1");
            if ("BUDGET_EXHAUSTED".equals(reason)) {
                statement.executeUpdate("UPDATE design_requirement_revision "
                        + "SET model_calls_used=max_model_calls WHERE id='r'");
            } else {
                statement.executeUpdate(promptInsert(
                        "prompt-1", "run-1", "launch-1", "INITIAL", null, "message-1"));
                if ("LOOKUP_UNSUPPORTED".equals(reason)) {
                    statement.executeUpdate("""
                            UPDATE ai_candidate_prompt_dispatch
                            SET state='DISCONNECTED',last_error_code='OPENCODE_PROMPT_LOOKUP_UNAVAILABLE',
                              updated_at='failed',version=version+1 WHERE id='prompt-1'
                            """);
                } else {
                    statement.executeUpdate("""
                            UPDATE ai_candidate_prompt_dispatch
                            SET state='DISCONNECTED',dispatch_attempted=1,dispatch_started_at='posted',
                              last_error_code='OPENCODE_PROMPT_RESULT_UNKNOWN',updated_at='failed',version=version+1
                            WHERE id='prompt-1'
                            """);
                }
            }

            assertThatThrownBy(() -> statement.executeUpdate(initialFailureIntent(wrongReason)))
                    .hasMessageContaining("initial prompt failure evidence mismatch");
            statement.executeUpdate(initialFailureIntent(reason));
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO ai_candidate_submission_attempt(
                      id,run_id,ordinal,idempotency_key,request_sha256,outcome,retryable,
                      problems_json,response_json,created_at)
                    VALUES('attempt-1','run-1',1,'key-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'REJECTED',1,'[]','{}','now')
                    """)).hasMessageContaining("active acceptance internal termination");
            if (!"BUDGET_EXHAUSTED".equals(reason)) {
                assertThatThrownBy(() -> statement.executeUpdate("""
                        UPDATE ai_candidate_prompt_dispatch
                        SET acknowledged=1,acked_at='forged',updated_at='forged' WHERE id='prompt-1'
                        """)).hasMessageContaining("freezes prompt evidence");
                statement.executeUpdate("UPDATE ai_candidate_prompt_dispatch SET state='STOPPING',"
                        + "updated_at='stopping',version=version+1 WHERE id='prompt-1'");
                statement.executeUpdate("""
                        UPDATE ai_candidate_prompt_dispatch
                        SET state='STOPPED',termination_proof='ABORT_ACKNOWLEDGED',
                          termination_proof_at='proof',updated_at='stopped',version=version+1
                        WHERE id='prompt-1'
                        """);
            }
            statement.executeUpdate("""
                    INSERT INTO acceptance_candidate_internal_launch_cleanup_remote(
                      launch_id,external_session_id,runtime_generation_id,endpoint_fingerprint,
                      directory_sha256,title_sha256,purpose,termination_intent_id,state,
                      termination_proof,proof_at,created_at,updated_at)
                    VALUES('launch-1','remote-1','generation-1',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                      'TERMINATION_INTENT','intent-1','STOPPED','ABORT_ACKNOWLEDGED','proof','now','now')
                    """);
            statement.executeUpdate("UPDATE ai_candidate_submission_run SET state='CLOSED' WHERE id='run-1'");
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_launch
                    SET state='FAILED_STOPPED',settled_owner_version=NULL,settled_at=NULL,
                      termination_proof='ABORT_ACKNOWLEDGED',proof_at='proof',version=version+1
                    WHERE id='launch-1'
                    """);
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_termination_intent
                    SET state='READY',ready_at='ready',updated_at='ready',version=version+1
                    WHERE id='intent-1'
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_termination_intent
                    SET state='COMPLETED',completed_at='early' WHERE id='intent-1'
                    """)).hasMessageContaining("parent is not terminal");
            if ("BUDGET_EXHAUSTED".equals(reason)) {
                statement.executeUpdate("UPDATE loop_spec_compilation SET state='SESSION_ERROR',"
                        + "last_error_code='DESIGNER_CANCELLED' WHERE id='cmp-1'");
                statement.executeUpdate("UPDATE designer_session SET state='CANCELLED' WHERE id='s'");
            } else if ("LOOKUP_UNSUPPORTED".equals(reason)) {
                statement.executeUpdate("UPDATE design_requirement_revision SET state='SUPERSEDED' WHERE id='r'");
            } else {
                statement.executeUpdate("UPDATE loop_spec_compilation SET state='SESSION_ERROR',"
                        + "last_error_code='OPENCODE_PROMPT_RESULT_UNKNOWN' WHERE id='cmp-1'");
            }
            statement.executeUpdate("""
                    UPDATE acceptance_candidate_internal_termination_intent
                    SET state='COMPLETED',completed_at='done',updated_at='done',version=version+1
                    WHERE id='intent-1'
                    """);
        }
    }

    private String initialFailureIntent(String reason) {
        return """
                INSERT INTO acceptance_candidate_internal_termination_intent(
                  id,launch_id,designer_session_id,compilation_id,candidate_run_id,kind,target_state,
                  archive_when_complete,reason_code,parent_action,state,anchor_designer_version,
                  anchor_requirement_revision_id,anchor_discussion_revision,created_at,updated_at,version)
                VALUES('intent-1','launch-1','s','cmp-1','run-1','INITIAL_PROMPT_FAILURE','FAILED_STOPPED',
                  0,'%s','NONE','REQUESTED',0,'r',0,'now','now',0)
                """.formatted(reason);
    }

    private void verifyMigration(Path database, boolean startAtV51) throws Exception {
        String url = "jdbc:sqlite:" + database;
        if (startAtV51) {
            Flyway.configure().dataSource(url, null, null)
                    .target(MigrationVersion.fromVersion("51")).load().migrate();
        }
        Flyway flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("68");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            List<String> tables = new ArrayList<>();
            try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (result.next()) tables.add(result.getString(1));
            }
            assertThat(tables).contains(
                    "acceptance_candidate_internal_launch",
                    "acceptance_candidate_internal_launch_cleanup_remote",
                    "acceptance_candidate_internal_termination_intent");
            List<String> cleanupColumns = new ArrayList<>();
            try (var result = statement.executeQuery(
                    "PRAGMA table_info(acceptance_candidate_internal_launch_cleanup_remote)")) {
                while (result.next()) cleanupColumns.add(result.getString("name"));
            }
            assertThat(cleanupColumns).contains("purpose", "termination_intent_id");
            List<String> intentColumns = new ArrayList<>();
            try (var result = statement.executeQuery(
                    "PRAGMA table_info(acceptance_candidate_internal_termination_intent)")) {
                while (result.next()) intentColumns.add(result.getString("name"));
            }
            assertThat(intentColumns).contains("archive_when_complete", "reason_code");
            List<String> promptColumns = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA table_info(ai_candidate_prompt_dispatch)")) {
                while (result.next()) promptColumns.add(result.getString("name"));
            }
            assertThat(promptColumns).contains("internal_launch_id");
            List<String> promptForeignKeys = new ArrayList<>();
            try (var result = statement.executeQuery("PRAGMA foreign_key_list(ai_candidate_prompt_dispatch)")) {
                while (result.next()) {
                    if ("internal_launch_id".equals(result.getString("from"))) {
                        promptForeignKeys.add(result.getString("table"));
                    }
                }
            }
            assertThat(promptForeignKeys).containsExactly("acceptance_candidate_internal_launch");
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void insertOwnerFixture(java.sql.Statement statement) throws Exception {
        statement.executeUpdate("INSERT INTO project(id,name,root_path,created_at,updated_at) "
                + "VALUES('p','P','/tmp/p','now','now')");
        statement.executeUpdate("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                + "VALUES('d','p','Goal','{}','DRAFT_READY','now','now')");
        statement.executeUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,"
                + "current_requirement_revision,created_at,updated_at) "
                + "VALUES('s','p','RUNNING','READ_ONLY','d',1,'now','now')");
        statement.executeUpdate("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,"
                + "delivery_state,created_at) VALUES('m','s',1,'ASSISTANT','design','PERSISTED','now')");
        statement.executeUpdate("""
                INSERT INTO design_requirement_revision(
                  id,designer_session_id,revision,source_message_id,requirement_text,
                  requirement_segments_json,source_draft_version,state,created_at,updated_at)
                VALUES('r','s',1,'m','requirement','[]',0,'ACTIVE','now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO task_decomposition(
                  id,designer_session_id,requirement_revision_id,state,source_draft_version,created_at,updated_at)
                VALUES('dec','s','r','RUNNING',0,'now','now')
                """);
        statement.executeUpdate("""
                INSERT INTO design_work_package(
                  id,designer_session_id,requirement_revision_id,decomposition_id,package_id,ordinal,title,objective,
                  scope_in_json,scope_out_json,dependencies_json,deliverables_json,acceptance_intent_json,
                  requirement_refs_json,state,design_revision,design_message_id,created_at,updated_at)
                VALUES('wp','s','r','dec','WP-1',0,'Package','Deliver','[]','[]','[]','[]','[]','[]',
                  'DESIGNING',1,'m','now','now')
                """);
    }

    private void insertCompilationAndPlanning(java.sql.Statement statement, String compilationId) throws Exception {
        statement.executeUpdate("""
                INSERT INTO loop_spec_compilation(
                  id,designer_session_id,design_revision,state,source_design_message_id,source_draft_version,
                  work_package_id,created_at,updated_at,version)
                VALUES('%s','s',1,'PENDING_HANDOFF','m',0,'WP-1','now','now',4)
                """.formatted(compilationId));
        statement.executeUpdate("""
                INSERT INTO design_acceptance_planning(
                  compilation_id,designer_session_id,work_package_id,design_revision,contract_version,
                  design_sha256,state,facts_json,capabilities_json,binding_json,diagnostics_json,
                  created_at,updated_at,version,binding_source)
                VALUES('%s','s','WP-1',1,'DESIGN_ACCEPTANCE_V7',
                  'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                  'EXTRACTED','[]','[]','{"selection":1}','[]','now','now',3,'AI_DISAMBIGUATION_V6')
                """.formatted(compilationId));
    }

    private void insertBinding(java.sql.Statement statement, String remoteId) throws Exception {
        statement.executeUpdate("""
                INSERT INTO open_code_session_runtime_binding(
                  external_session_id,runtime_generation_id,ownership_mode,endpoint_fingerprint,
                  internal_mcp_server,created_at)
                VALUES('%s','generation-1','MANAGED',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'loopper_internal_generation_1','now')
                """.formatted(remoteId));
    }

    private void markLaunchCreated(java.sql.Statement statement, String launchId, String remoteId) throws Exception {
        statement.executeUpdate("""
                UPDATE acceptance_candidate_internal_launch
                SET state='CREATED',create_claim_owner='worker',create_claim_token='claim',
                  create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,
                  create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',
                  external_session_id='%s',external_attested_at='attested-at',version=version+1
                WHERE id='%s'
                """.formatted(remoteId, launchId));
    }

    private void attachCompilation(java.sql.Statement statement, String compilationId, String remoteId)
            throws Exception {
        statement.executeUpdate("""
                UPDATE loop_spec_compilation
                SET state='RUNNING',external_session_id='%s',external_session_state='CANDIDATE_PROMPT_PENDING',
                  version=version+1 WHERE id='%s'
                """.formatted(remoteId, compilationId));
    }

    private String runInsert(String runId, String compilationId, String channel, String remoteId) {
        return """
                INSERT INTO ai_candidate_submission_run(
                  id,designer_session_id,owner_type,owner_id,candidate_kind,workflow_step,source_revision,
                  owner_version,submission_channel,contract_version,runtime_generation_id,external_session_id,
                  state,max_attempts,attempts_used,created_at,updated_at,version)
                VALUES('%s','s','LOOP_SPEC_COMPILATION','%s','ACCEPTANCE_CLOSED_CHOICE_V7',
                  'ACCEPTANCE_CLOSED_CHOICE_V7',1,5,'%s','ACCEPTANCE_CLOSED_CHOICE_V7',
                  'generation-1','%s','OPEN',2,0,'now','now',0)
                """.formatted(runId, compilationId, channel, remoteId);
    }

    private String settleLaunch(String launchId, long ownerVersion) {
        return """
                UPDATE acceptance_candidate_internal_launch
                SET state='SETTLED',settled_owner_version=%d,settled_at='settled-at',
                  create_claim_owner=NULL,create_claim_token=NULL,create_claim_expires_at=NULL,
                  version=version+1 WHERE id='%s'
                """.formatted(ownerVersion, launchId);
    }

    private void openAndSettle(java.sql.Connection connection, java.sql.Statement statement,
            String runId, String compilationId, String remoteId, String launchId) throws Exception {
        connection.setAutoCommit(false);
        try {
            statement.executeUpdate(runInsert(runId, compilationId, "INTERNAL_MCP", remoteId));
            statement.executeUpdate(settleLaunch(launchId, 5));
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private String promptInsert(String id, String runId, String launchId, String kind,
            Integer sourceAttemptOrdinal, String messageId) {
        String launchValue = launchId == null ? "NULL" : "'" + launchId + "'";
        String ordinalValue = sourceAttemptOrdinal == null ? "NULL" : sourceAttemptOrdinal.toString();
        return """
                INSERT INTO ai_candidate_prompt_dispatch(
                  id,run_id,internal_launch_id,dispatch_kind,source_attempt_ordinal,
                  external_session_id,runtime_generation_id,message_id,request_json,request_sha256,state,
                  model_call_consumed,model_call_consumed_at,created_at,updated_at,version)
                VALUES('%s','%s',%s,'%s',%s,'remote-1','generation-1','%s','{}',
                  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                  'PROMPTING',1,'now','now','now',0)
                """.formatted(id, runId, launchValue, kind, ordinalValue, messageId);
    }

    private String launchInsert(String id, String compilationId, String runId, long draftVersion) {
        String credential = (id.endsWith("1") ? "A" : id.endsWith("2") ? "B" : "C").repeat(43);
        return """
                INSERT INTO acceptance_candidate_internal_launch(
                  id,compilation_id,designer_session_id,work_package_id,source_design_revision,
                  source_design_message_id,source_draft_version,source_design_sha256,
                  planning_version,planning_binding_source,planning_binding_json,planning_binding_sha256,
                  route_plan_json,route_plan_sha256,candidate_run_id,contract_version,workflow_step,state,
                  prepared_owner_version,exact_title,canonical_directory,runtime_generation_id,managed,
                  internal_mcp_server,endpoint_fingerprint,profile,permission_policy_json,
                  permission_policy_digest,create_request_sha256,creation_credential,attestation_type,
                  created_at,updated_at,version)
                VALUES('%s','%s','s','WP-1',1,'m',%d,
                  'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                  3,'AI_DISAMBIGUATION_V6','{"selection":1}',
                  'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                  '{"candidates":[0,1]}',
                  'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                  '%s','ACCEPTANCE_CLOSED_CHOICE_V7','ACCEPTANCE_CLOSED_CHOICE_V7','PREPARED',4,
                  'Acceptance internal %s','/tmp/p','generation-1',1,'loopper_internal_generation_1',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                  'ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS','[]',
                  '1111111111111111111111111111111111111111111111111111111111111111',
                  '2222222222222222222222222222222222222222222222222222222222222222',
                  '%s','LOCAL_REQUEST_ATTESTED','now','now',0)
                """.formatted(id, compilationId, draftVersion, runId, id, credential);
    }
}
