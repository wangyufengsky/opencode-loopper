package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the deferred Acceptance settlement certificate on a production-equivalent SQLite connection. */
@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h",
        "spring.datasource.hikari.maximum-pool-size=1",
        "loopper.internal-candidate.runtime-guard-enabled=false"
})
class AcceptanceCandidateInternalSettlementTransactionIntegrationTest {
    private static final Path DATA = temporaryDataDirectory();

    @DynamicPropertySource
    static void productionLikeSqlite(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("loopper.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL");
        properties.add("loopper.data-dir", DATA::toString);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoopperMapper mapper;
    @Autowired private MachineCandidateSubmission submissions;
    @Autowired private AcceptanceCandidateInternalLaunchSettlementService settlements;
    @Autowired private AcceptanceCandidateInternalTerminationIntentStore terminationIntents;
    @Autowired private AcceptanceCandidateInternalTerminationSettlementService terminationSettlements;
    @Autowired private AcceptanceCandidateInternalParentSettlement parentSettlements;
    @Autowired private DesignerTerminationService designerTerminations;
    @Autowired private DesignerSessionService designerSessions;
    @Autowired private OpenCodeClient openCode;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void terminationSettlementClosesOnlyOpenRunAsOwnerRequested() {
        TerminationScenario scenario = prepareTerminationScenario("termination-open", MachineCandidateRunState.OPEN);

        AcceptanceCandidateInternalTerminationSettlementService.SettledRun settled =
                terminationSettlements.finishSettled(scenario.intentId(), "ABORT_ACKNOWLEDGED");

        assertThat(settled.outcome()).isEqualTo(
                AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.OWNER_STOPPED);
        assertThat(settled.terminalRun().state()).isEqualTo(MachineCandidateRunState.CLOSED);
        assertThat(settled.terminalRun().closeReason()).isEqualTo(
                MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
        assertTerminationReady(scenario, MachineCandidateRunState.CLOSED,
                MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
    }

    @Test
    void activeTerminationStillRejectsAttemptThatWouldKeepRunOpen() {
        TerminationScenario scenario = prepareTerminationScenario(
                "termination-rejected", MachineCandidateRunState.OPEN);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_candidate_submission_attempt(id,run_id,ordinal,"
                        + "idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,"
                        + "canonical_result_sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                "rejected-after-intent", scenario.runId(), 1, "rejected-after-intent", "a".repeat(64),
                "REJECTED", true, "[]", "{}", null, "now"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("active acceptance internal termination blocks submission insert");
        assertThat(submissions.find(scenario.runId()).orElseThrow().state())
                .isEqualTo(MachineCandidateRunState.OPEN);
    }

    @Test
    void userCancellationPromotesReadyInitialFailureAndCommitsParentIntentAndArchiveTogether() {
        TerminationScenario scenario = prepareInitialTerminationScenario(
                "initial-promoted-cancel", MachineCandidateRunState.CLOSED);
        terminationSettlements.finishSettled(scenario.intentId(), "ABORT_ACKNOWLEDGED");
        FakeOpenCodeClient fakeOpenCode = (FakeOpenCodeClient) openCode;
        fakeOpenCode.reset();

        DesignerTerminationService.Result result = designerTerminations.stop(
                scenario.scenario().sessionId(), true);

        assertThat(result.complete()).isTrue();
        assertThat(result.archived()).isTrue();
        assertThat(fakeOpenCode.abortedSessionIds()).doesNotContain(scenario.scenario().remoteId());
        assertThat(jdbc.queryForMap("SELECT state,parent_action,archive_when_complete "
                        + "FROM acceptance_candidate_internal_termination_intent WHERE id=?", scenario.intentId()))
                .containsEntry("state", "COMPLETED")
                .containsEntry("parent_action", "DESIGNER_CANCEL")
                .containsEntry("archive_when_complete", 1);
        assertThat(jdbc.queryForMap("SELECT state,last_error_code FROM loop_spec_compilation WHERE id=?",
                scenario.scenario().compilationId()))
                .containsEntry("state", "SESSION_ERROR")
                .containsEntry("last_error_code", "DESIGNER_CANCELLED");
        assertThat(jdbc.queryForObject("SELECT state FROM designer_session WHERE id=?", String.class,
                scenario.scenario().sessionId())).isEqualTo("CANCELLED");
    }

    @Test
    void requirementReplacementPromotesReadyInitialFailureWithoutGenericRemoteAbort() {
        TerminationScenario scenario = prepareInitialTerminationScenario(
                "initial-promoted-replacement", MachineCandidateRunState.CLOSED);
        terminationSettlements.finishSettled(scenario.intentId(), "ABORT_ACKNOWLEDGED");
        FakeOpenCodeClient fakeOpenCode = (FakeOpenCodeClient) openCode;
        fakeOpenCode.reset();

        designerSessions.reopenRequirement(scenario.scenario().sessionId(), 0);

        assertThat(fakeOpenCode.abortedSessionIds()).doesNotContain(scenario.scenario().remoteId());
        assertThat(jdbc.queryForMap("SELECT state,parent_action,archive_when_complete "
                        + "FROM acceptance_candidate_internal_termination_intent WHERE id=?", scenario.intentId()))
                .containsEntry("state", "COMPLETED")
                .containsEntry("parent_action", "OWNER_REPLACEMENT")
                .containsEntry("archive_when_complete", 0);
        assertThat(jdbc.queryForObject("SELECT state FROM design_requirement_revision WHERE id=?", String.class,
                scenario.scenario().revisionId())).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForMap("SELECT state,current_requirement_revision,workflow_phase "
                        + "FROM designer_session WHERE id=?", scenario.scenario().sessionId()))
                .containsEntry("state", "REVIEWING")
                .containsEntry("current_requirement_revision", null)
                .containsEntry("workflow_phase", "DISCUSSING_REQUIREMENT");
    }

    @Test
    void terminationSettlementPreservesAcceptedTerminalRace() {
        TerminationScenario scenario = prepareTerminationScenario(
                "termination-accepted", MachineCandidateRunState.ACCEPTED);

        AcceptanceCandidateInternalTerminationSettlementService.SettledRun settled =
                terminationSettlements.finishSettled(scenario.intentId(), "ABORT_ACKNOWLEDGED");

        assertThat(settled.outcome()).isEqualTo(
                AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.TERMINAL_RACE);
        assertThat(settled.terminalRun().state()).isEqualTo(MachineCandidateRunState.ACCEPTED);
        assertThat(settled.terminalRun().closeReason()).isNull();
        assertTerminationReady(scenario, MachineCandidateRunState.ACCEPTED, null);
    }

    @Test
    void terminationSettlementPreservesWaitingInputTerminalRace() {
        TerminationScenario scenario = prepareTerminationScenario(
                "termination-waiting", MachineCandidateRunState.WAITING_INPUT);

        AcceptanceCandidateInternalTerminationSettlementService.SettledRun settled =
                terminationSettlements.finishSettled(scenario.intentId(), "ABORT_ACKNOWLEDGED");

        assertThat(settled.outcome()).isEqualTo(
                AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.TERMINAL_RACE);
        assertThat(settled.terminalRun().state()).isEqualTo(MachineCandidateRunState.WAITING_INPUT);
        assertThat(settled.terminalRun().closeReason()).isNull();
        assertTerminationReady(scenario, MachineCandidateRunState.WAITING_INPUT, null);
    }

    @Test
    void terminationSettlementPreservesFallbackRequiredTerminalRace() {
        TerminationScenario scenario = prepareTerminationScenario(
                "termination-fallback", MachineCandidateRunState.FALLBACK_REQUIRED);

        AcceptanceCandidateInternalTerminationSettlementService.SettledRun settled =
                terminationSettlements.finishSettled(scenario.intentId(), "ABORT_ACKNOWLEDGED");

        assertThat(settled.outcome()).isEqualTo(
                AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.TERMINAL_RACE);
        assertThat(settled.terminalRun().state()).isEqualTo(MachineCandidateRunState.FALLBACK_REQUIRED);
        assertThat(settled.terminalRun().closeReason()).isNull();
        assertTerminationReady(scenario, MachineCandidateRunState.FALLBACK_REQUIRED, null);
    }

    @Test
    void terminationSettlementPreservesClosedTerminalRaceReason() {
        TerminationScenario scenario = prepareTerminationScenario(
                "termination-closed", MachineCandidateRunState.CLOSED);

        AcceptanceCandidateInternalTerminationSettlementService.SettledRun settled =
                terminationSettlements.finishSettled(scenario.intentId(), "ABORT_ACKNOWLEDGED");

        assertThat(settled.outcome()).isEqualTo(
                AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.TERMINAL_RACE);
        assertThat(settled.terminalRun().state()).isEqualTo(MachineCandidateRunState.CLOSED);
        assertThat(settled.terminalRun().closeReason()).isEqualTo(
                MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED);
        assertTerminationReady(scenario, MachineCandidateRunState.CLOSED,
                MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED);
    }

    @Test
    void initialFailureAcceptedRaceCompletesOnlyWithItsParentOutcomeInOneTransaction() {
        TerminationScenario scenario = prepareInitialTerminationScenario(
                "initial-accepted", MachineCandidateRunState.ACCEPTED);
        AcceptanceCandidateInternalTerminationSettlementService.SettledRun settled =
                terminationSettlements.finishSettled(scenario.intentId(), "ABORT_ACKNOWLEDGED");
        assertThat(settled.outcome()).isEqualTo(
                AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.TERMINAL_RACE);

        parentSettlements.settleInitialFailure(scenario.intentId(), () -> assertThat(jdbc.update(
                "UPDATE loop_spec_compilation SET state='COMPLETED',version=version+1 WHERE id=?",
                scenario.compilationId())).isEqualTo(1));

        assertThat(mapper.findLoopSpecCompilation(scenario.compilationId()).orElseThrow().state())
                .isEqualTo("COMPLETED");
        assertThat(mapper.findAcceptanceCandidateInternalTerminationIntent(scenario.intentId()).orElseThrow().state())
                .isEqualTo("COMPLETED");
    }

    @Test
    void initialFailureParentMutationRollsBackWhenIntentCompletionGateRejects() {
        TerminationScenario scenario = prepareInitialTerminationScenario(
                "initial-rollback", MachineCandidateRunState.ACCEPTED);
        terminationSettlements.finishSettled(scenario.intentId(), "ABORT_ACKNOWLEDGED");
        String trigger = "reject_initial_intent_completion_rollback";
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE OF state ON "
                + "acceptance_candidate_internal_termination_intent WHEN NEW.id='" + scenario.intentId()
                + "' AND NEW.state='COMPLETED' BEGIN SELECT RAISE(ABORT,'forced completion failure'); END");
        try {
            assertThatThrownBy(() -> parentSettlements.settleInitialFailure(scenario.intentId(), () ->
                    jdbc.update("UPDATE loop_spec_compilation SET state='COMPLETED',version=version+1 WHERE id=?",
                            scenario.compilationId()))).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }

        assertThat(mapper.findLoopSpecCompilation(scenario.compilationId()).orElseThrow().state())
                .isEqualTo("RUNNING");
        assertThat(mapper.findAcceptanceCandidateInternalTerminationIntent(scenario.intentId()).orElseThrow().state())
                .isEqualTo("READY");
    }

    @Test
    void settlementServiceCommitsOwnerRunLaunchRequirementCertificateAndBothLifecycleAudits() {
        Scenario scenario = prepareScenario("service-success", "U");

        var settled = settlements.settle(scenario.launchId());

        assertThat(settled.launch().state()).isEqualTo("SETTLED");
        assertThat(settled.launch().settledOwnerVersion()).isEqualTo(5);
        assertThat(settled.run().runId()).isEqualTo(scenario.runId());
        assertThat(jdbc.queryForMap("SELECT state,external_session_id,external_session_state,version "
                        + "FROM loop_spec_compilation WHERE id=?", scenario.compilationId()))
                .containsEntry("state", "RUNNING")
                .containsEntry("external_session_id", scenario.remoteId())
                .containsEntry("external_session_state", "CANDIDATE_PROMPT_PENDING")
                .containsEntry("version", 5);
        assertThat(count("acceptance_candidate_internal_launch_run_requirement",
                "candidate_run_id=?", scenario.runId())).isOne();
        assertThat(count("acceptance_candidate_internal_launch_settlement_certificate",
                "candidate_run_id=?", scenario.runId())).isOne();
        assertThat(count("state_transition_event",
                "machine_type='LOOPSPEC_COMPILATION' AND entity_id=?", scenario.compilationId())).isOne();
        assertThat(count("state_transition_event",
                "machine_type='CANDIDATE_SUBMISSION_RUN' AND entity_id=?", scenario.runId())).isOne();
        assertThat(count("state_transition_event",
                "machine_type='ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH' AND entity_id=?", scenario.launchId())).isOne();
    }

    @Test
    void settlementServiceTriggerFailureRollsBackOwnerRunLaunchRequirementCertificateAndAudits() {
        Scenario scenario = prepareScenario("service-rollback", "V");
        String trigger = "reject_internal_settlement_service_rollback";
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE OF state ON "
                + "acceptance_candidate_internal_launch WHEN NEW.id='" + scenario.launchId() + "' "
                + "AND NEW.state='SETTLED' BEGIN SELECT RAISE(ABORT,'forced settlement failure'); END");
        try {
            assertThatThrownBy(() -> settlements.settle(scenario.launchId()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }

        assertThat(jdbc.queryForMap("SELECT state,external_session_id,version "
                        + "FROM loop_spec_compilation WHERE id=?", scenario.compilationId()))
                .containsEntry("state", "PENDING_HANDOFF")
                .containsEntry("external_session_id", null)
                .containsEntry("version", 4);
        assertThat(jdbc.queryForMap("SELECT state,settled_owner_version,version "
                        + "FROM acceptance_candidate_internal_launch WHERE id=?", scenario.launchId()))
                .containsEntry("state", "CREATED")
                .containsEntry("settled_owner_version", null)
                .containsEntry("version", 1);
        assertThat(mapper.findCandidateSubmissionRun(scenario.runId())).isEmpty();
        assertThat(count("acceptance_candidate_internal_launch_run_requirement",
                "candidate_run_id=?", scenario.runId())).isZero();
        assertThat(count("acceptance_candidate_internal_launch_settlement_certificate",
                "candidate_run_id=?", scenario.runId())).isZero();
        assertThat(count("state_transition_event", "entity_id=?", scenario.compilationId())).isZero();
        assertThat(count("state_transition_event", "entity_id=?", scenario.runId())).isZero();
        assertThat(count("state_transition_event", "entity_id=?", scenario.launchId())).isZero();
    }

    @Test
    void standaloneInternalAcceptanceOpenFailsAtCommitWithoutRunOrLifecycleAudit() {
        Scenario scenario = prepareScenario("standalone", "S");
        assertThat(transactionManager).isInstanceOf(DataSourceTransactionManager.class);
        assertThat(((DataSourceTransactionManager) transactionManager).isRollbackOnCommitFailure()).isTrue();
        assertThat(mapper.advanceAcceptanceCandidateCompilationForInternalLaunch(
                scenario.launchId(), 1, 4, scenario.remoteId(), "attached-at")).isEqualTo(1);

        assertThatThrownBy(() -> submissions.open(openCommand(scenario)))
                .isInstanceOf(RuntimeException.class);

        assertThat(mapper.findCandidateSubmissionRun(scenario.runId())).isEmpty();
        assertThat(count("state_transition_event",
                "machine_type='CANDIDATE_SUBMISSION_RUN' AND entity_id=?", scenario.runId())).isZero();
        assertThat(count("acceptance_candidate_internal_launch_run_requirement",
                "candidate_run_id=?", scenario.runId())).isZero();
        assertThat(count("acceptance_candidate_internal_launch_settlement_certificate",
                "candidate_run_id=?", scenario.runId())).isZero();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(jdbc.update("UPDATE project SET name=? WHERE id=?",
                        "usable after failed commit", scenario.projectId())).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT name FROM project WHERE id=?", String.class, scenario.projectId()))
                .isEqualTo("usable after failed commit");
    }

    @Test
    void outerInternalSettlementRollbackRestoresOwnerLaunchRunRequirementCertificateAndAudit() {
        Scenario scenario = prepareScenario("rollback", "R");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(mapper.advanceAcceptanceCandidateCompilationForInternalLaunch(
                    scenario.launchId(), 1, 4, scenario.remoteId(), "attached-at")).isEqualTo(1);
            submissions.open(openCommand(scenario));
            assertThat(mapper.settleAcceptanceCandidateInternalLaunch(
                    scenario.launchId(), 1, 5, "settled-at")).isEqualTo(1);
            throw new IllegalStateException("force outer rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForMap("SELECT state,version FROM loop_spec_compilation WHERE id=?",
                scenario.compilationId()))
                .containsEntry("state", "PENDING_HANDOFF")
                .containsEntry("version", 4);
        assertThat(jdbc.queryForMap("SELECT state,settled_owner_version,version "
                        + "FROM acceptance_candidate_internal_launch WHERE id=?", scenario.launchId()))
                .containsEntry("state", "CREATED")
                .containsEntry("settled_owner_version", null)
                .containsEntry("version", 1);
        assertThat(mapper.findCandidateSubmissionRun(scenario.runId())).isEmpty();
        assertThat(count("state_transition_event",
                "machine_type='CANDIDATE_SUBMISSION_RUN' AND entity_id=?", scenario.runId())).isZero();
        assertThat(count("acceptance_candidate_internal_launch_run_requirement",
                "candidate_run_id=?", scenario.runId())).isZero();
        assertThat(count("acceptance_candidate_internal_launch_settlement_certificate",
                "candidate_run_id=?", scenario.runId())).isZero();
    }

    private Scenario prepareScenario(String prefix, String credentialSeed) {
        String creationCredential = (credentialSeed + "_" + prefix.replace('-', '_') + "_".repeat(43))
                .substring(0, 43);
        Scenario scenario = new Scenario(
                "project-" + prefix, "draft-" + prefix, "session-" + prefix,
                "message-" + prefix, "revision-" + prefix, "decomposition-" + prefix,
                "work-package-" + prefix, "WP-" + prefix, "compilation-" + prefix,
                "remote-" + prefix, "generation-" + prefix, "launch-" + prefix, "run-" + prefix);
        String root = "/tmp/v53-settlement-" + prefix;
        jdbc.update("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES(?,?,?,?,?)",
                scenario.projectId(), "V53 " + prefix, root, "now", "now");
        jdbc.update("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?)",
                scenario.draftId(), scenario.projectId(), "Goal", "{}", "DRAFT_READY", "now", "now");
        jdbc.update("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?)",
                scenario.sessionId(), scenario.projectId(), "RUNNING", "READ_ONLY", scenario.draftId(),
                "now", "now");
        jdbc.update("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)",
                scenario.messageId(), scenario.sessionId(), 1, "ASSISTANT", "design", "PERSISTED", "now");
        jdbc.update("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,"
                        + "requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                scenario.revisionId(), scenario.sessionId(), 1, scenario.messageId(), "requirement", "[]", 0,
                "ACTIVE", "now", "now");
        jdbc.update("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,"
                        + "source_draft_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                scenario.decompositionId(), scenario.sessionId(), scenario.revisionId(), "RUNNING", 0,
                "now", "now");
        jdbc.update("INSERT INTO design_work_package(id,designer_session_id,requirement_revision_id,decomposition_id,"
                        + "package_id,ordinal,title,objective,scope_in_json,scope_out_json,dependencies_json,"
                        + "deliverables_json,acceptance_intent_json,requirement_refs_json,state,design_revision,"
                        + "design_message_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                scenario.workPackageRowId(), scenario.sessionId(), scenario.revisionId(), scenario.decompositionId(),
                scenario.packageId(), 0, "Package", "Deliver", "[]", "[]", "[]", "[]", "[]", "[]",
                "DESIGNING", 1, scenario.messageId(), "now", "now");
        jdbc.update("INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,"
                        + "source_design_message_id,source_draft_version,work_package_id,created_at,updated_at,version) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                scenario.compilationId(), scenario.sessionId(), 1, "PENDING_HANDOFF", scenario.messageId(), 0,
                scenario.packageId(), "now", "now", 4);
        jdbc.update("INSERT INTO design_acceptance_planning(compilation_id,designer_session_id,work_package_id,"
                        + "design_revision,contract_version,design_sha256,state,facts_json,capabilities_json,"
                        + "binding_json,diagnostics_json,created_at,updated_at,version,binding_source) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                scenario.compilationId(), scenario.sessionId(), scenario.packageId(), 1,
                "DESIGN_ACCEPTANCE_V7", "d".repeat(64), "EXTRACTED", "[]", "[]",
                "{\"selection\":1}", "[]", "now", "now", 3, "AI_DISAMBIGUATION_V6");
        jdbc.update("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,"
                        + "ownership_mode,endpoint_fingerprint,internal_mcp_server,created_at) VALUES(?,?,?,?,?,?)",
                scenario.remoteId(), scenario.generationId(), "MANAGED", "a".repeat(64),
                "loopper_internal_" + prefix, "now");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM loop_spec_compilation compilation "
                        + "JOIN designer_message message ON message.id=? AND message.designer_session_id=? "
                        + "JOIN design_work_package work_package ON work_package.designer_session_id=? "
                        + "AND work_package.package_id=? AND work_package.design_revision=1 "
                        + "AND work_package.design_message_id=? AND work_package.id=(SELECT current.id "
                        + "FROM design_work_package current WHERE current.designer_session_id=? "
                        + "AND current.package_id=? ORDER BY current.plan_revision DESC,current.created_at DESC LIMIT 1) "
                        + "JOIN design_requirement_revision revision ON revision.id=work_package.requirement_revision_id "
                        + "AND revision.designer_session_id=? AND revision.source_draft_version=0 "
                        + "JOIN design_acceptance_planning planning ON planning.compilation_id=compilation.id "
                        + "AND planning.designer_session_id=? AND planning.work_package_id=? "
                        + "AND planning.design_revision=1 AND planning.contract_version='DESIGN_ACCEPTANCE_V7' "
                        + "AND planning.design_sha256=? AND planning.version=3 AND planning.state='EXTRACTED' "
                        + "AND planning.binding_source='AI_DISAMBIGUATION_V6' "
                        + "AND planning.binding_json=? WHERE compilation.id=? AND compilation.designer_session_id=? "
                        + "AND compilation.work_package_id=? AND compilation.design_revision=1 "
                        + "AND compilation.source_design_message_id=? AND compilation.source_draft_version=0 "
                        + "AND compilation.state='PENDING_HANDOFF' AND compilation.version=4 "
                        + "AND compilation.external_session_id IS NULL", Integer.class,
                scenario.messageId(), scenario.sessionId(), scenario.sessionId(), scenario.packageId(),
                scenario.messageId(), scenario.sessionId(), scenario.packageId(), scenario.sessionId(),
                scenario.sessionId(), scenario.packageId(), "d".repeat(64), "{\"selection\":1}",
                scenario.compilationId(), scenario.sessionId(), scenario.packageId(), scenario.messageId()))
                .as("fixture must satisfy the production launch anchor")
                .isEqualTo(1);

        assertThat(mapper.insertAcceptanceCandidateInternalLaunch(new AcceptanceCandidateInternalLaunchRow(
                scenario.launchId(), scenario.compilationId(), scenario.sessionId(), scenario.packageId(),
                1, scenario.messageId(), 0, "d".repeat(64), 3, "AI_DISAMBIGUATION_V6",
                "{\"selection\":1}", "e".repeat(64), "{\"candidates\":[0,1]}", "f".repeat(64),
                scenario.runId(), "ACCEPTANCE_CLOSED_CHOICE_V7", "ACCEPTANCE_CLOSED_CHOICE_V7", "PREPARED",
                4, null, null, "Acceptance internal " + prefix, root, scenario.generationId(), true,
                "loopper_internal_" + prefix, "a".repeat(64), null, null, null,
                "ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS", "[]", "1".repeat(64), "2".repeat(64),
                creationCredential, "LOCAL_REQUEST_ATTESTED", null, null, null, 0, false, null,
                null, null, null, null, null, null, null, "now", "now", 0))).isEqualTo(1);
        assertThat(jdbc.update("UPDATE acceptance_candidate_internal_launch SET state='CREATED',"
                        + "create_claim_owner='worker',create_claim_token='claim',"
                        + "create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,"
                        + "create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',"
                        + "external_session_id=?,external_attested_at='attested-at',version=version+1 WHERE id=?",
                scenario.remoteId(), scenario.launchId())).isEqualTo(1);
        return scenario;
    }

    private MachineCandidateSubmission.OpenCommand openCommand(Scenario scenario) {
        return new MachineCandidateSubmission.OpenCommand(
                scenario.runId(), MachineCandidateSubmission.CandidateScope.designerSession(scenario.sessionId()),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation(scenario.compilationId()),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP, 1, 5,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                AcceptanceClosedChoiceCandidateCoordinator.CONTRACT_VERSION,
                scenario.generationId(), scenario.remoteId(), 2);
    }

    private TerminationScenario prepareTerminationScenario(String prefix, MachineCandidateRunState runState) {
        Scenario scenario = prepareScenario(prefix, "T");
        settlements.settle(scenario.launchId());
        assertThat(jdbc.update("UPDATE designer_session SET state='STOPPING',version=version+1 WHERE id=?",
                scenario.sessionId())).isEqualTo(1);
        long designerVersion = jdbc.queryForObject(
                "SELECT version FROM designer_session WHERE id=?", Long.class, scenario.sessionId());
        String intentId = "intent-" + prefix;
        terminationIntents.create(new AcceptanceCandidateInternalTerminationIntentRow(
                intentId, scenario.launchId(), scenario.sessionId(), scenario.compilationId(), scenario.runId(),
                "DESIGNER_CANCEL", "CANCELLED", false, null, "DESIGNER_CANCEL", "REQUESTED", designerVersion,
                null, null, null, null, null, null, "now", "now", 0));
        if (runState != MachineCandidateRunState.OPEN) {
            String closeReason = runState == MachineCandidateRunState.CLOSED
                    ? MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED.name() : null;
            String terminalAttemptId = runState == MachineCandidateRunState.CLOSED
                    ? null : "terminal-" + prefix;
            if (terminalAttemptId != null) {
                assertThat(jdbc.update("INSERT INTO ai_candidate_submission_attempt(id,run_id,ordinal,"
                                + "idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,"
                                + "canonical_result_sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        terminalAttemptId, scenario.runId(), 1, "terminal-race-" + prefix, "a".repeat(64),
                        runState.name(), false, "[]", "{}",
                        runState == MachineCandidateRunState.ACCEPTED ? "b".repeat(64) : null, "now"))
                        .isEqualTo(1);
            }
            assertThat(jdbc.update("UPDATE ai_candidate_submission_run SET state=?,attempts_used=1,"
                            + "terminal_attempt_id=?,close_reason=?,version=version+1 WHERE id=?",
                    runState.name(), terminalAttemptId, closeReason, scenario.runId())).isEqualTo(1);
        }
        assertThat(mapper.insertAcceptanceCandidateInternalLaunchCleanupRemote(
                new AcceptanceCandidateInternalLaunchCleanupRemoteRow(
                        scenario.launchId(), scenario.remoteId(), scenario.generationId(), "a".repeat(64),
                        "b".repeat(64), "c".repeat(64), "TERMINATION_INTENT", intentId,
                        "STOPPED", "ABORT_ACKNOWLEDGED", "proof-at", null, null, null,
                        1, true, "dispatch-at", null, null, "now", "now", 0))).isEqualTo(1);
        return new TerminationScenario(scenario, intentId);
    }

    private TerminationScenario prepareInitialTerminationScenario(
            String prefix, MachineCandidateRunState runState) {
        Scenario scenario = prepareScenario(prefix, "I");
        settlements.settle(scenario.launchId());
        assertThat(jdbc.update("UPDATE designer_session SET current_requirement_revision=1,"
                + "active_work_package_id=?,workflow_phase='COMPILING' WHERE id=?",
                scenario.packageId(), scenario.sessionId())).isEqualTo(1);
        assertThat(jdbc.update("UPDATE design_work_package SET state='COMPILING' WHERE id=?",
                scenario.workPackageRowId())).isEqualTo(1);
        assertThat(jdbc.update("UPDATE design_requirement_revision SET model_calls_used=max_model_calls WHERE id=?",
                scenario.revisionId())).isEqualTo(1);
        long designerVersion = jdbc.queryForObject(
                "SELECT version FROM designer_session WHERE id=?", Long.class, scenario.sessionId());
        int discussionRevision = jdbc.queryForObject(
                "SELECT discussion_revision FROM designer_session WHERE id=?", Integer.class, scenario.sessionId());
        String intentId = "intent-" + prefix;
        terminationIntents.create(new AcceptanceCandidateInternalTerminationIntentRow(
                intentId, scenario.launchId(), scenario.sessionId(), scenario.compilationId(), scenario.runId(),
                "INITIAL_PROMPT_FAILURE", "FAILED_STOPPED", false, "BUDGET_EXHAUSTED", "NONE", "REQUESTED",
                designerVersion, scenario.revisionId(), discussionRevision,
                null, null, null, null, "now", "now", 0));
        if (runState == MachineCandidateRunState.CLOSED) {
            assertThat(jdbc.update("UPDATE ai_candidate_submission_run SET state='CLOSED',"
                            + "close_reason='REMOTE_FAILED',version=version+1 WHERE id=?", scenario.runId()))
                    .isEqualTo(1);
        } else if (runState != MachineCandidateRunState.OPEN) {
            String terminalAttemptId = "terminal-" + prefix;
            assertThat(jdbc.update("INSERT INTO ai_candidate_submission_attempt(id,run_id,ordinal,"
                            + "idempotency_key,request_sha256,outcome,retryable,problems_json,response_json,"
                            + "canonical_result_sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    terminalAttemptId, scenario.runId(), 1, "terminal-race-" + prefix, "a".repeat(64),
                    runState.name(), false, "[]", "{}",
                    runState == MachineCandidateRunState.ACCEPTED ? "b".repeat(64) : null, "now")).isEqualTo(1);
            assertThat(jdbc.update("UPDATE ai_candidate_submission_run SET state=?,attempts_used=1,"
                            + "terminal_attempt_id=?,version=version+1 WHERE id=?",
                    runState.name(), terminalAttemptId, scenario.runId())).isEqualTo(1);
        }
        assertThat(mapper.insertAcceptanceCandidateInternalLaunchCleanupRemote(
                new AcceptanceCandidateInternalLaunchCleanupRemoteRow(
                        scenario.launchId(), scenario.remoteId(), scenario.generationId(), "a".repeat(64),
                        "b".repeat(64), "c".repeat(64), "TERMINATION_INTENT", intentId,
                        "STOPPED", "ABORT_ACKNOWLEDGED", "proof-at", null, null, null,
                        1, true, "dispatch-at", null, null, "now", "now", 0))).isEqualTo(1);
        return new TerminationScenario(scenario, intentId);
    }

    private void assertTerminationReady(TerminationScenario scenario, MachineCandidateRunState expectedState,
            MachineCandidateSubmission.CandidateCloseReason expectedReason) {
        MachineCandidateSubmission.RunSnapshot run = submissions.find(scenario.runId()).orElseThrow();
        assertThat(run.state()).isEqualTo(expectedState);
        assertThat(run.closeReason()).isEqualTo(expectedReason);
        assertThat(mapper.findAcceptanceCandidateInternalLaunch(scenario.launchId()).orElseThrow().state())
                .isEqualTo("CANCELLED");
        assertThat(mapper.findAcceptanceCandidateInternalTerminationIntent(scenario.intentId()).orElseThrow().state())
                .isEqualTo("READY");
        assertThat(mapper.findLoopSpecCompilation(scenario.compilationId()).orElseThrow().state())
                .isEqualTo("SESSION_ERROR");
    }

    private int count(String table, String where, String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where,
                Integer.class, id);
        return count == null ? 0 : count;
    }

    private static Path temporaryDataDirectory() {
        try {
            return Files.createTempDirectory("loopper-v53-settlement-fk-");
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record Scenario(
            String projectId, String draftId, String sessionId, String messageId, String revisionId,
            String decompositionId, String workPackageRowId, String packageId, String compilationId,
            String remoteId, String generationId, String launchId, String runId) { }

    private record TerminationScenario(Scenario scenario, String intentId) {
        String runId() { return scenario.runId(); }
        String launchId() { return scenario.launchId(); }
        String compilationId() { return scenario.compilationId(); }
    }
}
