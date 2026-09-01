package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        "loopper.data-dir=target/acceptance-internal-launch-mapper-test"
})
class AcceptanceCandidateInternalLaunchMapperIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoopperMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        insertOwnerFixture();
    }

    @Test
    void roundTripsLaunchAndSeparatesClaimFromTheCreateDispatchCheckpoint() {
        AcceptanceCandidateInternalLaunchRow prepared = preparedLaunch();
        assertThat(mapper.insertAcceptanceCandidateInternalLaunch(prepared)).isEqualTo(1);
        assertThat(mapper.findAcceptanceCandidateInternalLaunch("launch-1")).contains(prepared);

        assertThat(mapper.claimAcceptanceCandidateInternalLaunchCreate(
                "launch-1", 0, "PREPARED", "worker", "claim-1",
                "2099-01-01T00:00:00Z", 1, "2026-09-01T10:00:00Z", "claimed-at"))
                .isEqualTo(1);
        AcceptanceCandidateInternalLaunchRow claimed = mapper.findAcceptanceCandidateInternalLaunch("launch-1")
                .orElseThrow();
        assertThat(claimed.state()).isEqualTo("PREPARED");
        assertThat(claimed.createDispatchAttempted()).isFalse();
        assertThat(claimed.createFence()).isEqualTo(1);
        assertThat(claimed.version()).isEqualTo(1);
        assertThat(mapper.claimAcceptanceCandidateInternalLaunchCreate(
                "launch-1", 0, "PREPARED", "other", "claim-2",
                "2099-01-01T00:00:00Z", 2, "2026-09-01T10:00:01Z", "stale"))
                .isZero();
        assertThat(mapper.markAcceptanceCandidateInternalLaunchCreateDispatchStarted(
                "launch-1", 1, "other", "claim-1", 1,
                "2026-09-01T10:01:00Z", "wrong-holder"))
                .isZero();
        assertThat(mapper.markAcceptanceCandidateInternalLaunchCreateDispatchStarted(
                "launch-1", 1, "worker", "claim-1", 1,
                "2026-09-01T10:01:00Z", "dispatched-at"))
                .isEqualTo(1);
        AcceptanceCandidateInternalLaunchRow dispatched = mapper.findAcceptanceCandidateInternalLaunch("launch-1")
                .orElseThrow();
        assertThat(dispatched.state()).isEqualTo("CREATING");
        assertThat(dispatched.createDispatchAttempted()).isTrue();
        assertThat(dispatched.createDispatchStartedAt()).isEqualTo("2026-09-01T10:01:00Z");
    }

    @Test
    void cleanupClaimAndStopDispatchAreSeparateHolderOnlyCasSteps() {
        mapper.insertAcceptanceCandidateInternalLaunch(preparedLaunch());
        jdbc.update("UPDATE acceptance_candidate_internal_launch SET state='STOPPING' WHERE id='launch-1'");
        insertBinding("cleanup-remote");
        AcceptanceCandidateInternalLaunchCleanupRemoteRow discovered =
                new AcceptanceCandidateInternalLaunchCleanupRemoteRow(
                        "launch-1", "cleanup-remote", "generation-1", "a".repeat(64),
                        "b".repeat(64), "c".repeat(64), "LAUNCH_AMBIGUITY", null,
                        "DISCOVERED", null, null,
                        null, null, null, 0, false, null,
                        null, null, "now", "now", 0);
        assertThat(mapper.insertAcceptanceCandidateInternalLaunchCleanupRemote(discovered)).isEqualTo(1);
        assertThat(mapper.existsAcceptanceCandidateInternalTrackedExternalSession("cleanup-remote")).isTrue();
        assertThat(mapper.listAcceptanceCandidateInternalLaunchCleanupRemotes("launch-1"))
                .containsExactly(discovered);

        assertThat(mapper.claimAcceptanceCandidateInternalLaunchCleanupRemote(
                "launch-1", "cleanup-remote", 0, "DISCOVERED", "worker", "stop-1",
                "2099-01-01T00:00:00Z", 1, "2026-09-01T10:00:00Z", "claimed-at"))
                .isEqualTo(1);
        AcceptanceCandidateInternalLaunchCleanupRemoteRow claimed = mapper
                .listAcceptanceCandidateInternalLaunchCleanupRemotes("launch-1").getFirst();
        assertThat(claimed.state()).isEqualTo("STOPPING");
        assertThat(claimed.stopDispatchAttempted()).isFalse();
        assertThat(mapper.markAcceptanceCandidateInternalLaunchCleanupStopDispatchStarted(
                "launch-1", "cleanup-remote", 1, "other", "stop-1", 1,
                "2026-09-01T10:01:00Z", "wrong-holder"))
                .isZero();
        assertThat(mapper.markAcceptanceCandidateInternalLaunchCleanupStopDispatchStarted(
                "launch-1", "cleanup-remote", 1, "worker", "stop-1", 1,
                "2026-09-01T10:01:00Z", "dispatched-at"))
                .isEqualTo(1);
        AcceptanceCandidateInternalLaunchCleanupRemoteRow dispatched = mapper
                .listAcceptanceCandidateInternalLaunchCleanupRemotes("launch-1").getFirst();
        assertThat(dispatched.stopDispatchAttempted()).isTrue();
        assertThat(dispatched.stopDispatchStartedAt()).isEqualTo("2026-09-01T10:01:00Z");
    }

    @Test
    void terminationIntentRoundTripsAndUsesOptimisticVersion() {
        mapper.insertAcceptanceCandidateInternalLaunch(preparedLaunch());
        jdbc.update("UPDATE designer_session SET state='STOPPING' WHERE id='s'");
        AcceptanceCandidateInternalTerminationIntentRow requested =
                new AcceptanceCandidateInternalTerminationIntentRow(
                        "intent-1", "launch-1", "s", "cmp", "run-1",
                        "DESIGNER_CANCEL", "CANCELLED", true, null, "DESIGNER_CANCEL", "REQUESTED", 0,
                        null, null, null, null, null, null, "now", "now", 0);
        assertThat(mapper.insertAcceptanceCandidateInternalTerminationIntent(requested)).isEqualTo(1);
        assertThat(mapper.findAcceptanceCandidateInternalTerminationIntentForLaunch("launch-1"))
                .contains(requested);
        assertThat(mapper.findActiveAcceptanceCandidateInternalTerminationIntentForCompilation("cmp"))
                .contains(requested);
        assertThat(mapper.listActiveAcceptanceCandidateInternalTerminationIntents("s"))
                .containsExactly(requested);

        AcceptanceCandidateInternalTerminationIntentRow disconnected =
                new AcceptanceCandidateInternalTerminationIntentRow(
                        requested.id(), requested.launchId(), requested.designerSessionId(),
                        requested.compilationId(), requested.candidateRunId(), requested.kind(),
                        requested.targetState(), requested.archiveWhenComplete(), requested.reasonCode(),
                        requested.parentAction(),
                        "DISCONNECTED",
                        requested.anchorDesignerVersion(),
                        null, null, null, null, "STOP_UNKNOWN", "transport", "now", "later", 0);
        assertThat(mapper.updateAcceptanceCandidateInternalTerminationIntent(disconnected)).isEqualTo(1);
        assertThat(mapper.updateAcceptanceCandidateInternalTerminationIntent(disconnected)).isZero();
        assertThat(mapper.findAcceptanceCandidateInternalTerminationIntent("intent-1").orElseThrow().version())
                .isEqualTo(1);
        assertThat(mapper.listRecoverableAcceptanceCandidateInternalTerminationIntents())
                .singleElement().satisfies(persisted -> {
                    assertThat(persisted.state()).isEqualTo(disconnected.state());
                    assertThat(persisted.lastErrorCode()).isEqualTo(disconnected.lastErrorCode());
                    assertThat(persisted.version()).isEqualTo(1);
                });
        assertThat(mapper.existsActiveAcceptanceCandidateInternalTerminationIntentForDesigner("s")).isTrue();
        assertThat(mapper.listTerminableAcceptanceCandidateInternalLaunchesForDesigner("s"))
                .extracting(AcceptanceCandidateInternalLaunchRow::id).containsExactly("launch-1");
        assertThat(mapper.existsAcceptanceCandidateInternalTrackedExternalSession("missing")).isFalse();

        AcceptanceCandidateInternalTerminationIntentRow ready =
                new AcceptanceCandidateInternalTerminationIntentRow(
                        disconnected.id(), disconnected.launchId(), disconnected.designerSessionId(),
                        disconnected.compilationId(), disconnected.candidateRunId(), disconnected.kind(),
                        disconnected.targetState(), disconnected.archiveWhenComplete(), disconnected.reasonCode(),
                        disconnected.parentAction(),
                        "READY",
                        disconnected.anchorDesignerVersion(), null, null, "ready", null,
                        null, null, "now", "ready", 1);
        jdbc.update("UPDATE acceptance_candidate_internal_launch SET state='CANCELLED',version=version+1 "
                + "WHERE id='launch-1'");
        assertThat(mapper.updateAcceptanceCandidateInternalTerminationIntent(ready)).isEqualTo(1);
        jdbc.update("UPDATE loop_spec_compilation SET state='SESSION_ERROR',version=version+1 WHERE id='cmp'");
        jdbc.update("UPDATE designer_session SET state='CANCELLED',version=version+1 WHERE id='s'");
        assertThat(mapper.completeAcceptanceCandidateInternalTerminationIntent(
                "intent-1", 2, "completed", "completed")).isEqualTo(1);
        assertThat(mapper.completeAcceptanceCandidateInternalTerminationIntent(
                "intent-1", 2, "duplicate", "duplicate")).isZero();
        AcceptanceCandidateInternalTerminationIntentRow completed = mapper
                .findAcceptanceCandidateInternalTerminationIntent("intent-1").orElseThrow();
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.archiveWhenComplete()).isTrue();
        assertThat(mapper.listRecoverableAcceptanceCandidateInternalTerminationIntents()).isEmpty();
        assertThat(mapper.existsActiveAcceptanceCandidateInternalTerminationIntentForDesigner("s")).isFalse();
    }

    @Test
    void compilationRunAndSettlementCasCommitTogetherAndRollbackWithoutResidue() {
        mapper.insertAcceptanceCandidateInternalLaunch(preparedLaunch());
        insertBinding("remote-1");
        markLaunchCreated();
        assertThat(mapper.existsAcceptanceCandidateInternalTrackedExternalSession("remote-1")).isTrue();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(mapper.advanceAcceptanceCandidateCompilationForInternalLaunch(
                    "launch-1", 1, 4, "remote-1", "attached-at")).isEqualTo(1);
            assertThat(mapper.insertCandidateSubmissionRun(openRun())).isEqualTo(1);
            assertThat(mapper.settleAcceptanceCandidateInternalLaunch(
                    "launch-1", 1, 5, "settled-at")).isEqualTo(1);
            throw new IllegalStateException("force outer rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT state FROM loop_spec_compilation WHERE id='cmp'", String.class))
                .isEqualTo("PENDING_HANDOFF");
        assertThat(jdbc.queryForObject("SELECT version FROM loop_spec_compilation WHERE id='cmp'", Long.class))
                .isEqualTo(4);
        assertThat(mapper.findCandidateSubmissionRun("run-1")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM acceptance_candidate_internal_launch_run_requirement", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM acceptance_candidate_internal_launch_settlement_certificate", Integer.class))
                .isZero();
        AcceptanceCandidateInternalLaunchRow afterRollback = mapper
                .findAcceptanceCandidateInternalLaunch("launch-1").orElseThrow();
        assertThat(afterRollback.state()).isEqualTo("CREATED");
        assertThat(afterRollback.settledOwnerVersion()).isNull();
        assertThat(afterRollback.version()).isEqualTo(1);

        transactions.executeWithoutResult(status -> {
            assertThat(mapper.advanceAcceptanceCandidateCompilationForInternalLaunch(
                    "launch-1", 1, 4, "remote-1", "attached-at")).isEqualTo(1);
            assertThat(mapper.insertCandidateSubmissionRun(openRun())).isEqualTo(1);
            assertThat(mapper.settleAcceptanceCandidateInternalLaunch(
                    "launch-1", 1, 5, "settled-at")).isEqualTo(1);
        });

        assertThat(mapper.findCandidateSubmissionRun("run-1")).isPresent();
        assertThat(mapper.findAcceptanceCandidateInternalLaunch("launch-1").orElseThrow().state())
                .isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM acceptance_candidate_internal_launch_run_requirement", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM acceptance_candidate_internal_launch_settlement_certificate", Integer.class))
                .isEqualTo(1);
        assertThat(mapper.advanceAcceptanceCandidateCompilationForInternalLaunch(
                "launch-1", 1, 4, "remote-1", "stale")).isZero();
        assertThat(mapper.settleAcceptanceCandidateInternalLaunch(
                "launch-1", 1, 5, "stale")).isZero();
    }

    private AcceptanceCandidateInternalLaunchRow preparedLaunch() {
        return new AcceptanceCandidateInternalLaunchRow(
                "launch-1", "cmp", "s", "WP-1", 1, "m", 0, "d".repeat(64),
                3, "AI_DISAMBIGUATION_V6", "{\"selection\":1}", "e".repeat(64),
                "{\"candidates\":[0,1]}", "f".repeat(64), "run-1",
                "ACCEPTANCE_CLOSED_CHOICE_V7", "ACCEPTANCE_CLOSED_CHOICE_V7", "PREPARED",
                4, null, null, "Acceptance internal launch-1", "/tmp/p", "generation-1",
                true, "loopper_internal_generation_1", "a".repeat(64),
                null, null, null, "ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS",
                "[]", "1".repeat(64), "2".repeat(64), "A".repeat(43),
                "LOCAL_REQUEST_ATTESTED", null, null, null, 0, false, null,
                null, null, null, null, null, null, null, "now", "now", 0);
    }

    private CandidateSubmissionRunRow openRun() {
        return new CandidateSubmissionRunRow(
                "run-1", "s", null, null, "LOOP_SPEC_COMPILATION", "cmp",
                "ACCEPTANCE_CLOSED_CHOICE_V7", "ACCEPTANCE_CLOSED_CHOICE_V7", 1, 5,
                "INTERNAL_MCP", "ACCEPTANCE_CLOSED_CHOICE_V7", "generation-1", "remote-1",
                "OPEN", 2, 0, null, "now", "now", 0, null);
    }

    private void markLaunchCreated() {
        jdbc.update("UPDATE acceptance_candidate_internal_launch SET state='CREATED',"
                + "create_claim_owner='worker',create_claim_token='claim',"
                + "create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,"
                + "create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',"
                + "external_session_id='remote-1',external_attested_at='attested-at',version=version+1 "
                + "WHERE id='launch-1'");
    }

    private void insertOwnerFixture() {
        jdbc.update("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','P','/tmp/p','now','now')");
        jdbc.update("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                + "VALUES('d','p','Goal','{}','DRAFT_READY','now','now')");
        jdbc.update("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at) "
                + "VALUES('s','p','RUNNING','READ_ONLY','d','now','now')");
        jdbc.update("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) "
                + "VALUES('m','s',1,'ASSISTANT','design','PERSISTED','now')");
        jdbc.update("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,"
                + "requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) "
                + "VALUES('r','s',1,'m','requirement','[]',0,'ACTIVE','now','now')");
        jdbc.update("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,"
                + "source_draft_version,created_at,updated_at) VALUES('dec','s','r','RUNNING',0,'now','now')");
        jdbc.update("INSERT INTO design_work_package(id,designer_session_id,requirement_revision_id,decomposition_id,"
                + "package_id,ordinal,title,objective,scope_in_json,scope_out_json,dependencies_json,deliverables_json,"
                + "acceptance_intent_json,requirement_refs_json,state,design_revision,design_message_id,created_at,"
                + "updated_at) VALUES('wp','s','r','dec','WP-1',0,'Package','Deliver','[]','[]','[]','[]','[]','[]',"
                + "'DESIGNING',1,'m','now','now')");
        jdbc.update("INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,"
                + "source_design_message_id,source_draft_version,work_package_id,created_at,updated_at,version) "
                + "VALUES('cmp','s',1,'PENDING_HANDOFF','m',0,'WP-1','now','now',4)");
        jdbc.update("INSERT INTO design_acceptance_planning(compilation_id,designer_session_id,work_package_id,"
                + "design_revision,contract_version,design_sha256,state,facts_json,capabilities_json,binding_json,"
                + "diagnostics_json,created_at,updated_at,version,binding_source) VALUES('cmp','s','WP-1',1,"
                + "'DESIGN_ACCEPTANCE_V7',?,'EXTRACTED','[]','[]','{\"selection\":1}','[]','now','now',3,"
                + "'AI_DISAMBIGUATION_V6')", "d".repeat(64));
    }

    private void insertBinding(String externalSessionId) {
        jdbc.update("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,"
                + "ownership_mode,endpoint_fingerprint,internal_mcp_server,created_at) "
                + "VALUES(?,'generation-1','MANAGED',?,'loopper_internal_generation_1','now')",
                externalSessionId, "a".repeat(64));
    }
}
