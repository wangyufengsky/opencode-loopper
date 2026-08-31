package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h",
        "loopper.data-dir=target/machine-candidate-submission-test",
        "loopper.internal-candidate.runtime-guard-enabled=false"
})
@Import(MachineCandidateSubmissionIntegrationTest.TestAdapters.class)
class MachineCandidateSubmissionIntegrationTest {
    private static final String INVALID = "{\"valid\":false,\"secretCandidate\":\"must-not-persist\"}";
    private static final MachineCandidateSubmission.SubmissionChannel LEGACY =
            MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY;
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoopperMapper mapper;
    @Autowired private MachineCandidateSubmission submissions;
    @MockitoBean(name = "decompositionCandidatePolicy", enforceOverride = true)
    private CandidatePolicy candidatePolicy;
    @MockitoBean(name = "decompositionAcceptedCandidateWriter", enforceOverride = true)
    private AcceptedCandidateWriter acceptedCandidateWriter;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        insertCandidateOwners();
        configureCandidateAdapters();
    }

    @Test
    void rejectedCandidateCanBeCorrectedAndAcceptedWithExactIdempotentReplay() {
        MachineCandidateSubmission.RunSnapshot opened = submissions.open(decomposerRun("run-1", 5));
        assertThat(opened.state()).isEqualTo(MachineCandidateRunState.OPEN);
        assertThat(opened.submissionChannel()).isEqualTo(LEGACY);

        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand("run-1", "attempt-1", INVALID, 0, LEGACY));
        assertThat(rejected.outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        assertThat(rejected.runState()).isEqualTo(MachineCandidateRunState.OPEN);
        assertThat(rejected.attemptOrdinal()).isEqualTo(1);
        assertThat(rejected.remainingAttempts()).isEqualTo(4);
        assertThat(rejected.submissionRevision()).isEqualTo(1);
        assertThat(rejected.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("CANDIDATE_INVALID");
            assertThat(problem.detail()).isEqualTo("候选不满足测试合同");
        });

        assertThat(submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-1", "attempt-1", INVALID, 0, LEGACY))).isEqualTo(rejected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_submission_attempt WHERE run_id='run-1'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT problems_json || response_json FROM ai_candidate_submission_attempt "
                + "WHERE run_id='run-1'", String.class)).doesNotContain("secretCandidate", "must-not-persist");
        assertThat(submissions.terminal("run-1")).isEmpty();
        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-1", "attempt-1", "{\"valid\":false,\"changed\":true}", 0, LEGACY)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_IDEMPOTENCY_KEY_REUSED"));

        MachineCandidateSubmission.SubmissionResult accepted = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-1", "attempt-2", "{\"valid\":true}", 1, LEGACY));
        assertThat(accepted.outcome()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        assertThat(accepted.runState()).isEqualTo(MachineCandidateRunState.ACCEPTED);
        assertThat(accepted.canonicalResultSha256()).hasSize(64);
        assertThat(submissions.terminal("run-1")).contains(accepted);
        assertThat(mapper.countCandidateSubmissionRunsForDecomposition("dec")).isEqualTo(1);
        assertThat(mapper.countCandidateSubmissionAttemptsForDecomposition("dec")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT planning_json FROM task_decomposition WHERE id='dec'", String.class))
                .isEqualTo("{\"valid\":true}");
        assertThat(jdbc.queryForList("SELECT event FROM state_transition_event "
                + "WHERE machine_type='CANDIDATE_SUBMISSION_RUN' AND entity_id='run-1' ORDER BY sequence", String.class))
                .containsExactly("CREATED", "APPROVE");
    }

    @Test
    void attemptBudgetExhaustionTransitionsToWaitingInputWithoutCallingAcceptedWriter() {
        submissions.open(decomposerRun("run-budget", 2));

        assertThat(submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-budget", "attempt-1", INVALID, 0, LEGACY)).outcome())
                .isEqualTo(MachineCandidateOutcome.REJECTED);
        MachineCandidateSubmission.SubmissionResult terminal = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand("run-budget", "attempt-2", INVALID, 1, LEGACY));

        assertThat(terminal.outcome()).isEqualTo(MachineCandidateOutcome.WAITING_INPUT);
        assertThat(terminal.runState()).isEqualTo(MachineCandidateRunState.WAITING_INPUT);
        assertThat(terminal.remainingAttempts()).isZero();
        assertThat(submissions.terminal("run-budget")).contains(terminal);
        assertThat(jdbc.queryForObject("SELECT planning_json FROM task_decomposition WHERE id='dec'", String.class))
                .isEqualTo("{}");
        assertThat(jdbc.queryForList("SELECT event FROM state_transition_event "
                + "WHERE machine_type='CANDIDATE_SUBMISSION_RUN' AND entity_id='run-budget' ORDER BY sequence", String.class))
                .containsExactly("CREATED", "REQUIRE_INPUT");
    }

    @Test
    void acceptedWriterFailureRollsBackTheAttemptAndRunTransition() {
        submissions.open(decomposerRun("run-writer-failure", 5));

        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-writer-failure", "attempt-1", "{\"valid\":true,\"writerFailure\":true}",
                0, LEGACY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated accepted writer failure");

        assertThat(submissions.find("run-writer-failure")).get().satisfies(run -> {
            assertThat(run.state()).isEqualTo(MachineCandidateRunState.OPEN);
            assertThat(run.attemptsUsed()).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                + "WHERE run_id='run-writer-failure'", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT event FROM state_transition_event "
                + "WHERE machine_type='CANDIDATE_SUBMISSION_RUN' AND entity_id='run-writer-failure' ORDER BY sequence",
                String.class)).containsExactly("CREATED");
    }

    @Test
    void v7CandidateBudgetIsHardBoundedAtTwoSubmissions() {
        MachineCandidateSubmission.OpenCommand command = new MachineCandidateSubmission.OpenCommand(
                "run-v7", "s", MachineCandidateSubmission.CandidateOwner.loopSpecCompilation("cmp"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, "CLOSED_CHOICE", 1, 0,
                LEGACY, "ACCEPTANCE_CLOSED_CHOICE_V7", "generation-1", "remote-1", 3);

        assertThatThrownBy(() -> submissions.open(command))
                .isInstanceOfSatisfying(BadRequestException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_ATTEMPT_LIMIT_INVALID"));
    }

    @Test
    void submissionChannelCannotCrossFromInternalMcpIntoALegacyRun() {
        submissions.open(decomposerRun("run-channel", 5));

        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-channel", "attempt-1", INVALID, 0,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_SUBMISSION_CHANNEL_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                + "WHERE run_id='run-channel'", Integer.class)).isZero();
    }

    @Test
    void staleSubmissionRevisionAndOwnerRevisionAreRejectedBeforePolicyPersistence() {
        submissions.open(decomposerRun("run-stale", 5));
        MachineCandidateSubmission.SubmissionResult first = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand("run-stale", "attempt-1", INVALID, 0, LEGACY));

        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-stale", "attempt-2", INVALID, 0, LEGACY)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_SUBMISSION_REVISION_CONFLICT"));
        jdbc.update("UPDATE task_decomposition SET version=version+1 WHERE id='dec'");
        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-stale", "attempt-2", INVALID, first.submissionRevision(), LEGACY)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                + "WHERE run_id='run-stale'", Integer.class)).isEqualTo(1);
    }

    @Test
    void staleSourceRevisionIsDistinctFromTheFrozenOwnerVersion() {
        MachineCandidateSubmission.RunSnapshot run = submissions.open(decomposerRun("run-stale-source", 5));
        assertThat(run.sourceRevision()).isEqualTo(1);
        assertThat(run.ownerVersion()).isZero();

        jdbc.update("UPDATE design_requirement_revision SET revision=2 WHERE id='r'");

        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                run.runId(), "attempt-1", INVALID, run.version(), LEGACY)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_SOURCE_REVISION_STALE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                + "WHERE run_id='run-stale-source'", Integer.class)).isZero();
    }

    @Test
    void closeAndPolicyBoundsFailClosedWithoutCandidateAttempts() {
        MachineCandidateSubmission.RunSnapshot open = submissions.open(decomposerRun("run-close", 5));
        MachineCandidateSubmission.RunSnapshot closed = submissions.close(
                new MachineCandidateSubmission.CloseCommand(open.runId(), open.version()));
        assertThat(closed.state()).isEqualTo(MachineCandidateRunState.CLOSED);
        assertThat(submissions.close(new MachineCandidateSubmission.CloseCommand(
                closed.runId(), closed.version()))).isEqualTo(closed);
        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-close", "attempt-1", INVALID, closed.version(), LEGACY)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_RUN_TERMINAL"));

        submissions.open(decomposerRun("run-policy-bound", 5));
        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-policy-bound", "attempt-1", "{\"tooManyProblems\":true}", 0, LEGACY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Candidate policy returned an invalid problem set");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                + "WHERE run_id IN ('run-close','run-policy-bound')", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT event FROM state_transition_event "
                + "WHERE machine_type='CANDIDATE_SUBMISSION_RUN' AND entity_id='run-close' ORDER BY sequence", String.class))
                .containsExactly("CREATED", "ABORT");
    }

    @Test
    void productionDecomposerProblemsNeverPersistCandidateProvidedValues() {
        CandidatePolicy productionPolicy = new DecompositionCandidatePolicy(
                mapper, new DesignerDecompositionCandidateCompiler(new ObjectMapper()));
        when(candidatePolicy.evaluate(any(CandidatePolicy.Context.class), anyString()))
                .thenAnswer(invocation -> productionPolicy.evaluate(invocation.getArgument(0), invocation.getArgument(1)));
        submissions.open(decomposerRun("run-redaction", 5));
        String candidate = """
                {"outcome":"READY","normalizedGoal":"bounded plan","globalConstraints":[],
                 "workPackages":[{"title":"Vertical result","objective":"deliver result",
                   "scopeIn":[],"scopeOut":[],"deliverables":["result"],
                   "acceptanceIntent":["result is verified"],"dependsOn":[]}],
                 "coverage":[{"requirementRef":"must-not-persist","targetType":"WORK_PACKAGE","targetIndex":0}],
                 "designGaps":[],"reason":null}
                """;

        MachineCandidateSubmission.SubmissionResult result = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-redaction", "attempt-1", candidate, 0, LEGACY));

        assertThat(result.outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        assertThat(result.problems()).extracting(MachineCandidateSubmission.Problem::code)
                .contains("REQUIREMENT_REFERENCE_INVALID");
        assertThat(jdbc.queryForObject("SELECT problems_json || response_json FROM ai_candidate_submission_attempt "
                + "WHERE run_id='run-redaction'", String.class)).doesNotContain("must-not-persist");
    }

    private MachineCandidateSubmission.OpenCommand decomposerRun(String id, int maxAttempts) {
        return new MachineCandidateSubmission.OpenCommand(
                id, "s", MachineCandidateSubmission.CandidateOwner.taskDecomposition("dec"),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, "PLANNING", 1, 0,
                LEGACY, "DECOMPOSITION_PLAN_V2", "generation-1", "remote-1", maxAttempts);
    }

    private void configureCandidateAdapters() {
        when(candidatePolicy.supports(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == MachineCandidateKind.DECOMPOSITION_PLAN_V2);
        when(candidatePolicy.evaluate(any(CandidatePolicy.Context.class), anyString()))
                .thenAnswer(invocation -> evaluateCandidate(invocation.getArgument(1)));
        when(acceptedCandidateWriter.supports(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == MachineCandidateKind.DECOMPOSITION_PLAN_V2);
        doAnswer(invocation -> {
            CandidatePolicy.Context context = invocation.getArgument(0);
            String canonicalCandidateJson = invocation.getArgument(1);
            if (canonicalCandidateJson.contains("writerFailure")) {
                throw new IllegalStateException("simulated accepted writer failure");
            }
            assertThat(jdbc.update("UPDATE task_decomposition SET planning_json=?, version=version+1 "
                            + "WHERE id=? AND version=?", canonicalCandidateJson,
                    context.owner().taskDecompositionId(), context.ownerVersion())).isEqualTo(1);
            return null;
        }).when(acceptedCandidateWriter).write(any(CandidatePolicy.Context.class), anyString(), anyString());
    }

    private CandidatePolicy.Decision evaluateCandidate(String candidateJson) {
        if (candidateJson.contains("tooManyProblems")) {
            return CandidatePolicy.Decision.rejected(true, java.util.stream.IntStream.range(0, 17)
                    .mapToObj(index -> new MachineCandidateSubmission.Problem(
                            "CANDIDATE_INVALID_" + index, "/value", "候选不满足测试合同"))
                    .toList());
        }
        return candidateJson.contains("\"valid\":true")
                ? CandidatePolicy.Decision.accepted(candidateJson)
                : CandidatePolicy.Decision.rejected(true, List.of(
                        new MachineCandidateSubmission.Problem(
                                "CANDIDATE_INVALID", "/valid", "候选不满足测试合同")));
    }

    private void insertCandidateOwners() {
        jdbc.update("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','P','/tmp/p','now','now')");
        jdbc.update("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) "
                + "VALUES('d','p','G','{}','DRAFT_READY','now','now')");
        jdbc.update("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,created_at,updated_at) "
                + "VALUES('s','p','RUNNING','READ_ONLY','d','now','now')");
        jdbc.update("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) "
                + "VALUES('m','s',1,'ASSISTANT','design','PERSISTED','now')");
        jdbc.update("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,"
                + "requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) "
                + "VALUES('r','s',1,'m','requirement',?,0,'ACTIVE','now','now')",
                "[{\"id\":\"RQ-1\",\"text\":\"deliver one verified result\"}]");
        jdbc.update("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,"
                + "planning_json,source_draft_version,created_at,updated_at) "
                + "VALUES('dec','s','r','RUNNING','{}',0,'now','now')");
        jdbc.update("INSERT INTO loop_spec_compilation(id,designer_session_id,design_revision,state,"
                + "source_design_message_id,source_draft_version,created_at,updated_at) "
                + "VALUES('cmp','s',1,'RUNNING','m',0,'now','now')");
        jdbc.update("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,"
                + "ownership_mode,endpoint_fingerprint,created_at) VALUES('remote-1','generation-1','MANAGED',?, 'now')",
                "a".repeat(64));
    }

    @TestConfiguration
    static class TestAdapters {
        @Bean
        CandidateRunGuard testCandidateRunGuard(JdbcTemplate jdbc) {
            return (run, submissionChannel) -> {
                assertThat(submissionChannel).isEqualTo(run.submissionChannel());
                Integer binding = jdbc.queryForObject("SELECT COUNT(*) FROM open_code_session_runtime_binding "
                        + "WHERE external_session_id=? AND runtime_generation_id=?", Integer.class,
                        run.externalSessionId(), run.runtimeGenerationId());
                if (binding == null || binding != 1) {
                    throw new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE", "运行时代际绑定已经变化");
                }
                if (run.candidateKind() == MachineCandidateKind.DECOMPOSITION_PLAN_V2) {
                    Long ownerRevision = jdbc.queryForObject(
                            "SELECT version FROM task_decomposition WHERE id=?", Long.class,
                            run.owner().taskDecompositionId());
                    if (ownerRevision == null || ownerRevision != run.ownerVersion()) {
                        throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE", "候选拥有者修订已经变化");
                    }
                    Long sourceRevision = jdbc.queryForObject("SELECT r.revision FROM task_decomposition d "
                                    + "JOIN design_requirement_revision r ON r.id=d.requirement_revision_id "
                                    + "WHERE d.id=?", Long.class, run.owner().taskDecompositionId());
                    if (sourceRevision == null || sourceRevision != run.sourceRevision()) {
                        throw new ConflictException("CANDIDATE_SOURCE_REVISION_STALE", "候选来源修订已经变化");
                    }
                }
            };
        }
    }
}
