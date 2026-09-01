package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.PackageDesignAcceptedResultRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired private CandidatePromptDispatchService promptDispatches;
    @Autowired private DesignerTerminationService designerTermination;
    @Autowired private AcceptanceCandidateProofService acceptanceCandidateProofs;
    @Autowired private AcceptanceCandidateHandoffCleanupLedger handoffCleanupLedger;
    @Autowired private TaskTerminalConsistencyService taskTerminals;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private DesignerSessionRuntimeControl designerRuntimeControl;
    @MockitoBean(name = "decompositionCandidatePolicy", enforceOverride = true)
    private CandidatePolicy decompositionCandidatePolicy;
    @MockitoBean(name = "packageDesignCandidatePolicy", enforceOverride = true)
    private CandidatePolicy packageDesignCandidatePolicy;
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
    void correctionPromptIsDurableSingleDispatchAndSettlesProofBeforeOwnerRequestedClose() {
        MachineCandidateSubmission.RunSnapshot opened = submissions.open(decomposerRun("run-prompt", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand("run-prompt", "attempt-1", INVALID, 0, LEGACY));
        assertThat(promptDispatches.rejectedProblems("run-prompt")).isEqualTo(rejected.problems());
        MachineCandidateSubmission.RunSnapshot run = submissions.find("run-prompt").orElseThrow();
        String messageId = CandidatePromptDispatchService.messageId(run.runId(), rejected.attemptOrdinal());
        OpenCodeClient.PromptRequest request = new OpenCodeClient.PromptRequest(
                "repair the rejected candidate", null, null, new OpenCodeClient.ResponseFormat.Text(),
                messageId, List.of());
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/p"));
        AtomicInteger posts = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean posted = new java.util.concurrent.atomic.AtomicBoolean();
        CandidatePromptDispatchService.PromptIo io = new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected, String sha256) {
                return posted.get() ? new OpenCodeClient.MessageLookup(true, true, sha256)
                        : new OpenCodeClient.MessageLookup(true, false);
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected) {
                posts.incrementAndGet();
                posted.set(true);
            }
        };

        CandidatePromptDispatchService.Result first = promptDispatches.advance(run, rejected, remote, request,
                () -> jdbc.update("UPDATE design_requirement_revision SET model_calls_used=model_calls_used+1 "
                        + "WHERE id='r' AND model_calls_used<max_model_calls") == 1,
                io, "integration-worker", Instant.now());
        CandidatePromptDispatchService.Result replay = promptDispatches.advance(run, rejected, remote, request,
                () -> jdbc.update("UPDATE design_requirement_revision SET model_calls_used=model_calls_used+1 "
                        + "WHERE id='r' AND model_calls_used<max_model_calls") == 1,
                io, "integration-worker", Instant.now());

        assertThat(first.status()).isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(replay.status()).isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(posts).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT model_calls_used FROM design_requirement_revision WHERE id='r'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT dispatch_kind,source_attempt_ordinal,state,dispatch_attempted,acknowledged "
                + "FROM ai_candidate_prompt_dispatch WHERE run_id='run-prompt'"))
                .containsEntry("dispatch_kind", "CORRECTION")
                .containsEntry("source_attempt_ordinal", 1)
                .containsEntry("state", "ACKNOWLEDGED")
                .containsEntry("dispatch_attempted", 1)
                .containsEntry("acknowledged", 1);
        assertThat(jdbc.queryForList("SELECT event FROM state_transition_event "
                + "WHERE machine_type='CANDIDATE_PROMPT_DISPATCH' ORDER BY sequence", String.class))
                .containsExactly("CREATED", "COMPLETE");

        assertThat(promptDispatches.completeForRun("run-prompt", "REMOTE_COMPLETED")).isTrue();
        MachineCandidateSubmission.RunSnapshot closed = submissions.close(
                new MachineCandidateSubmission.CloseCommand(run.runId(), run.version(),
                        MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED));
        assertThat(jdbc.queryForMap("SELECT state,termination_proof FROM ai_candidate_prompt_dispatch "
                + "WHERE run_id='run-prompt'"))
                .containsEntry("state", "STOPPED")
                .containsEntry("termination_proof", "REMOTE_COMPLETED");
        assertThat(closed.closeReason()).isEqualTo(
                MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT event FROM state_transition_event "
                + "WHERE machine_type='CANDIDATE_PROMPT_DISPATCH' ORDER BY sequence", String.class))
                .containsExactly("CREATED", "COMPLETE", "ABORT", "COMPLETE");
    }

    @Test
    void correctionPromptAndOwnerSettlementRollBackAsOneTransaction() {
        submissions.open(decomposerRun("run-prompt-settlement", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-prompt-settlement", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(rejected.runId()).orElseThrow();
        assertThat(promptDispatches.advance(run, rejected, remote(), correctionRequest(run, rejected),
                reserveModelCall(), noOpPromptIo(), "acceptance-correction", Instant.now()).status())
                .isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(promptDispatches.prepareRunTermination(run.runId(), Instant.now())).isTrue();

        assertThatThrownBy(() -> promptDispatches.settleForRun(run.runId(), "REMOTE_COMPLETED", () -> {
            jdbc.update("UPDATE design_requirement_revision SET model_calls_used=model_calls_used+1 WHERE id='r'");
            throw new IllegalStateException("owner settlement failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForMap("SELECT state,termination_proof FROM ai_candidate_prompt_dispatch "
                + "WHERE run_id=?", run.runId()))
                .containsEntry("state", "STOPPING")
                .containsEntry("termination_proof", null);
        assertThat(mapper.listActiveCandidatePromptDispatchesForRun(run.runId())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT model_calls_used FROM design_requirement_revision WHERE id='r'",
                Integer.class)).isEqualTo(1);

        assertThat(promptDispatches.settleForRun(run.runId(), "REMOTE_COMPLETED", () ->
                jdbc.update("UPDATE design_requirement_revision SET model_calls_used=model_calls_used+1 "
                        + "WHERE id='r'"))).isTrue();
        assertThat(jdbc.queryForMap("SELECT state,termination_proof FROM ai_candidate_prompt_dispatch "
                + "WHERE run_id=?", run.runId()))
                .containsEntry("state", "STOPPED")
                .containsEntry("termination_proof", "REMOTE_COMPLETED");
        assertThat(mapper.listActiveCandidatePromptDispatchesForRun(run.runId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT model_calls_used FROM design_requirement_revision WHERE id='r'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void initialPromptUsesTheRealAcceptanceCoordinatorRunContractAndGatesZeroSubmissionClose() {
        prepareInternalLaunch("run-initial");
        MachineCandidateSubmission.RunSnapshot run = new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(mapper.advanceAcceptanceCandidateCompilationForInternalLaunch(
                    "launch-run-initial", 1, 4, "remote-1", "attached-at")).isEqualTo(1);
            MachineCandidateSubmission.RunSnapshot opened = submissions.open(acceptanceInitialRun("run-initial"));
            assertThat(mapper.settleAcceptanceCandidateInternalLaunch(
                    "launch-run-initial", 1, 5, "settled-at")).isEqualTo(1);
            return opened;
        });
        OpenCodeClient.PromptRequest request = new OpenCodeClient.PromptRequest(
                "submit one closed-choice candidate", null, null, new OpenCodeClient.ResponseFormat.Text(),
                CandidatePromptDispatchService.initialMessageId(run.runId()), List.of());

        CandidatePromptDispatchService.Result result = promptDispatches.advanceInitial(
                run, "launch-run-initial", remote(), request, reserveModelCall(), noOpPromptIo(),
                "acceptance-initial", Instant.now());

        assertThat(result.status()).isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(jdbc.queryForMap("SELECT dispatch_kind,source_attempt_ordinal,state,acknowledged "
                + "FROM ai_candidate_prompt_dispatch WHERE run_id=?", run.runId()))
                .containsEntry("dispatch_kind", "INITIAL")
                .containsEntry("source_attempt_ordinal", null)
                .containsEntry("state", "ACKNOWLEDGED")
                .containsEntry("acknowledged", 1);
        assertThat(jdbc.queryForObject("SELECT model_calls_used FROM design_requirement_revision WHERE id='r'",
                Integer.class)).isEqualTo(1);

        MachineCandidateSubmission.RunSnapshot closed = submissions.close(
                new MachineCandidateSubmission.CloseCommand(run.runId(), run.version(),
                        MachineCandidateSubmission.CandidateCloseReason.NORMAL_COMPLETION_ZERO_SUBMISSION));
        assertThat(closed.state()).isEqualTo(MachineCandidateRunState.CLOSED);
        assertThat(closed.attemptsUsed()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABSENT", "UNSUPPORTED", "FAILURE"})
    void successfulPostNeedsExactReadbackAndAnAttemptedDispatchNeverPostsAgain(String readback) {
        submissions.open(decomposerRun("run-readback-" + readback.toLowerCase(), 5));
        String runId = "run-readback-" + readback.toLowerCase();
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(runId, "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(runId).orElseThrow();
        OpenCodeClient.PromptRequest request = correctionRequest(run, rejected);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        CandidatePromptDispatchService.PromptIo io = new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected, String sha256) {
                int lookup = lookups.incrementAndGet();
                if (lookup == 1) return new OpenCodeClient.MessageLookup(true, false);
                if (lookup == 2 && "FAILURE".equals(readback)) {
                    throw new IllegalStateException("readback unavailable");
                }
                if (lookup == 2 && "UNSUPPORTED".equals(readback)) {
                    return new OpenCodeClient.MessageLookup(false, false);
                }
                if (lookup == 2) return new OpenCodeClient.MessageLookup(true, false);
                return new OpenCodeClient.MessageLookup(true, true, sha256);
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected) { posts.incrementAndGet(); }
        };

        assertThat(promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(), io,
                "acceptance-correction", Instant.now()).status())
                .isEqualTo(CandidatePromptDispatchService.Status.RESULT_UNKNOWN);
        assertThat(promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(), io,
                "acceptance-correction", Instant.now()).status())
                .isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(posts).hasValue(1);
        assertThat(jdbc.queryForMap("SELECT dispatch_attempted,acknowledged FROM ai_candidate_prompt_dispatch "
                + "WHERE run_id=?", runId))
                .containsEntry("dispatch_attempted", 1)
                .containsEntry("acknowledged", 1);
    }

    @Test
    void concurrentCorrectionWorkersWithSameLogicalNameCannotShareOneClaim() throws Exception {
        submissions.open(decomposerRun("run-prompt-race", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-prompt-race", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find("run-prompt-race").orElseThrow();
        OpenCodeClient.PromptRequest request = new OpenCodeClient.PromptRequest(
                "repair the rejected candidate", null, null, new OpenCodeClient.ResponseFormat.Text(),
                CandidatePromptDispatchService.messageId(run.runId(), rejected.attemptOrdinal()), List.of());
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/p"));
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean posted = new java.util.concurrent.atomic.AtomicBoolean();
        CandidatePromptDispatchService.PromptIo io = new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected, String sha256) {
                lookups.incrementAndGet();
                lookupEntered.countDown();
                try {
                    if (!releaseLookup.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lookup blocked");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return posted.get() ? new OpenCodeClient.MessageLookup(true, true, sha256)
                        : new OpenCodeClient.MessageLookup(true, false);
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected) {
                posts.incrementAndGet();
                posted.set(true);
            }
        };
        CandidatePromptDispatchService.BudgetReservation budget = () -> jdbc.update(
                "UPDATE design_requirement_revision SET model_calls_used=model_calls_used+1 "
                        + "WHERE id='r' AND model_calls_used<max_model_calls") == 1;

        CompletableFuture<CandidatePromptDispatchService.Result> first = CompletableFuture.supplyAsync(() ->
                promptDispatches.advance(run, rejected, remote, request, budget, io,
                        "same-logical-worker", Instant.now()));
        assertThat(lookupEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<CandidatePromptDispatchService.Result> loser = CompletableFuture.supplyAsync(() ->
                promptDispatches.advance(run, rejected, remote, request, budget, io,
                        "same-logical-worker", Instant.now()));
        Thread.sleep(200);
        releaseLookup.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS).status())
                .isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(loser.get(5, TimeUnit.SECONDS).status())
                .isIn(CandidatePromptDispatchService.Status.PENDING,
                        CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(lookups).hasValue(2);
        assertThat(posts).hasValue(1);
    }

    @Test
    void designerCancellationFencesCorrectionBetweenLookupAndPost() throws Exception {
        submissions.open(decomposerRun("run-prompt-cancel-lookup", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-prompt-cancel-lookup", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(rejected.runId()).orElseThrow();
        OpenCodeClient.PromptRequest request = correctionRequest(run, rejected);
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger();

        CompletableFuture<CandidatePromptDispatchService.Result> worker = CompletableFuture.supplyAsync(() ->
                promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(),
                        new CandidatePromptDispatchService.PromptIo() {
                            @Override public OpenCodeClient.MessageLookup lookup(
                                    OpenCodeClient.OpenCodeSession ignored,
                                    OpenCodeClient.PromptRequest expected, String sha256) {
                                lookupEntered.countDown();
                                await(releaseLookup);
                                return new OpenCodeClient.MessageLookup(true, false);
                            }
                            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                                    OpenCodeClient.PromptRequest expected) { posts.incrementAndGet(); }
                        }, "acceptance-correction", Instant.now()));

        assertThat(lookupEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(promptDispatches.prepareDesignerCancellation("s", Instant.now())).isFalse();
        releaseLookup.countDown();
        assertThat(worker.get(5, TimeUnit.SECONDS).status()).isEqualTo(
                CandidatePromptDispatchService.Status.PENDING);

        assertThat(posts).hasValue(0);
        assertThat(jdbc.queryForMap("SELECT state,claim_token,dispatch_attempted "
                + "FROM ai_candidate_prompt_dispatch WHERE run_id=?", run.runId()))
                .containsEntry("state", "STOPPING")
                .containsEntry("claim_token", null)
                .containsEntry("dispatch_attempted", 0);
        assertThat(promptDispatches.prepareDesignerCancellation("s", Instant.now())).isTrue();
    }

    @Test
    void runTerminationWaitsForActivePrePostClaimAndFencesItWithoutPosting() throws Exception {
        submissions.open(decomposerRun("run-prompt-stop", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-prompt-stop", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(rejected.runId()).orElseThrow();
        OpenCodeClient.PromptRequest request = correctionRequest(run, rejected);
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger();

        CompletableFuture<CandidatePromptDispatchService.Result> worker = CompletableFuture.supplyAsync(() ->
                promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(),
                        new CandidatePromptDispatchService.PromptIo() {
                            @Override public OpenCodeClient.MessageLookup lookup(
                                    OpenCodeClient.OpenCodeSession ignored,
                                    OpenCodeClient.PromptRequest expected, String sha256) {
                                lookupEntered.countDown();
                                await(releaseLookup);
                                return new OpenCodeClient.MessageLookup(true, false);
                            }
                            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                                    OpenCodeClient.PromptRequest expected) { posts.incrementAndGet(); }
                        }, "acceptance-correction", Instant.now()));

        assertThat(lookupEntered.await(5, TimeUnit.SECONDS)).isTrue();
        jdbc.update("UPDATE ai_candidate_prompt_dispatch SET claim_expires_at='2000-01-01T00:00:00Z' "
                + "WHERE run_id=?", run.runId());
        assertThat(promptDispatches.prepareRunTermination(run.runId(), Instant.now())).isFalse();
        assertThat(jdbc.queryForMap("SELECT state,claim_token,dispatch_attempted "
                + "FROM ai_candidate_prompt_dispatch WHERE run_id=?", run.runId()))
                .containsEntry("state", "STOPPING")
                .containsEntry("dispatch_attempted", 0);

        releaseLookup.countDown();
        assertThat(worker.get(5, TimeUnit.SECONDS).status()).isEqualTo(
                CandidatePromptDispatchService.Status.PENDING);
        assertThat(posts).hasValue(0);
        assertThat(promptDispatches.prepareRunTermination(run.runId(), Instant.now())).isTrue();
        assertThat(jdbc.queryForMap("SELECT state,claim_token,dispatch_attempted "
                + "FROM ai_candidate_prompt_dispatch WHERE run_id=?", run.runId()))
                .containsEntry("state", "STOPPING")
                .containsEntry("claim_token", null)
                .containsEntry("dispatch_attempted", 0);
    }

    @Test
    void designerCancellationPersistsLatePostResultWithoutRestartingPromptLifecycle() throws Exception {
        submissions.open(decomposerRun("run-prompt-cancel-post", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-prompt-cancel-post", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(rejected.runId()).orElseThrow();
        OpenCodeClient.PromptRequest request = correctionRequest(run, rejected);
        CountDownLatch postEntered = new CountDownLatch(1);
        CountDownLatch releasePost = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger();

        CompletableFuture<CandidatePromptDispatchService.Result> worker = CompletableFuture.supplyAsync(() ->
                promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(),
                        new CandidatePromptDispatchService.PromptIo() {
                            @Override public OpenCodeClient.MessageLookup lookup(
                                    OpenCodeClient.OpenCodeSession ignored,
                                    OpenCodeClient.PromptRequest expected, String sha256) {
                                return posts.get() > 0 ? new OpenCodeClient.MessageLookup(true, true, sha256)
                                        : new OpenCodeClient.MessageLookup(true, false);
                            }
                            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                                    OpenCodeClient.PromptRequest expected) {
                                posts.incrementAndGet();
                                postEntered.countDown();
                                await(releasePost);
                            }
                        }, "acceptance-correction", Instant.now()));

        assertThat(postEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(promptDispatches.prepareDesignerCancellation("s", Instant.now())).isFalse();
        releasePost.countDown();
        assertThat(worker.get(5, TimeUnit.SECONDS).status()).isEqualTo(
                CandidatePromptDispatchService.Status.PENDING);

        assertThat(posts).hasValue(1);
        assertThat(jdbc.queryForMap("SELECT state,claim_token,dispatch_attempted,acknowledged "
                + "FROM ai_candidate_prompt_dispatch WHERE run_id=?", run.runId()))
                .containsEntry("state", "STOPPING")
                .containsEntry("claim_token", null)
                .containsEntry("dispatch_attempted", 1)
                .containsEntry("acknowledged", 1);
        assertThat(promptDispatches.prepareDesignerCancellation("s", Instant.now())).isTrue();
    }

    @Test
    void correctionDispatchInsertConflictRollsBackBudgetReservation() {
        submissions.open(decomposerRun("run-prompt-budget-rollback", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-prompt-budget-rollback", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(rejected.runId()).orElseThrow();
        jdbc.execute("CREATE TRIGGER reject_prompt_dispatch BEFORE INSERT ON ai_candidate_prompt_dispatch "
                + "BEGIN SELECT RAISE(ABORT,'simulated insert conflict'); END");

        assertThatThrownBy(() -> promptDispatches.advance(run, rejected, remote(),
                correctionRequest(run, rejected), reserveModelCall(), noOpPromptIo(),
                "acceptance-correction", Instant.now())).isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT model_calls_used FROM design_requirement_revision WHERE id='r'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_prompt_dispatch", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM state_transition_event "
                + "WHERE machine_type='CANDIDATE_PROMPT_DISPATCH'", Integer.class)).isZero();
    }

    @Test
    void correctionLookupFailureBeforePostCanRecoverWithoutDoubleBudgetOrPost() {
        submissions.open(decomposerRun("run-prompt-before-post", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-prompt-before-post", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(rejected.runId()).orElseThrow();
        OpenCodeClient.PromptRequest request = correctionRequest(run, rejected);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        CandidatePromptDispatchService.PromptIo io = new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected, String sha256) {
                if (lookups.incrementAndGet() == 1) throw new IllegalStateException("crash before POST");
                return posts.get() > 0 ? new OpenCodeClient.MessageLookup(true, true, sha256)
                        : new OpenCodeClient.MessageLookup(true, false);
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected) { posts.incrementAndGet(); }
        };

        assertThat(promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(), io,
                "acceptance-correction", Instant.now()).status())
                .isEqualTo(CandidatePromptDispatchService.Status.PENDING);
        assertThat(jdbc.queryForMap("SELECT state,dispatch_attempted FROM ai_candidate_prompt_dispatch "
                + "WHERE run_id=?", run.runId()))
                .containsEntry("state", "DISCONNECTED").containsEntry("dispatch_attempted", 0);
        assertThat(promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(), io,
                "acceptance-correction", Instant.now()).status())
                .isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(posts).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT model_calls_used FROM design_requirement_revision WHERE id='r'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void correctionPostUncertaintyNeverResendsWhileExactLookupLags() {
        submissions.open(decomposerRun("run-prompt-after-post", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-prompt-after-post", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(rejected.runId()).orElseThrow();
        OpenCodeClient.PromptRequest request = correctionRequest(run, rejected);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        CandidatePromptDispatchService.PromptIo io = new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected, String sha256) {
                int attempt = lookups.incrementAndGet();
                return attempt < 3 ? new OpenCodeClient.MessageLookup(true, false)
                        : new OpenCodeClient.MessageLookup(true, true, sha256);
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected) {
                posts.incrementAndGet();
                throw new IllegalStateException("connection lost after POST");
            }
        };

        assertThat(promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(), io,
                "acceptance-correction", Instant.now()).status())
                .isEqualTo(CandidatePromptDispatchService.Status.RESULT_UNKNOWN);
        assertThat(promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(), io,
                "acceptance-correction", Instant.now()).status())
                .isEqualTo(CandidatePromptDispatchService.Status.RESULT_UNKNOWN);
        assertThat(jdbc.queryForMap("SELECT state,dispatch_attempted,last_error_code "
                + "FROM ai_candidate_prompt_dispatch WHERE run_id=?", run.runId()))
                .containsEntry("state", "DISCONNECTED")
                .containsEntry("dispatch_attempted", 1)
                .containsEntry("last_error_code", "OPENCODE_PROMPT_RESULT_UNKNOWN");
        assertThat(promptDispatches.advance(run, rejected, remote(), request, reserveModelCall(), io,
                "acceptance-correction", Instant.now()).status())
                .isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(posts).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT model_calls_used FROM design_requirement_revision WHERE id='r'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void designerCancellationPersistsPromptProofClosesRunThenFinalizesParent() {
        submissions.open(decomposerRun("run-designer-cancel", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-designer-cancel", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(rejected.runId()).orElseThrow();
        assertThat(promptDispatches.advance(run, rejected, remote(), correctionRequest(run, rejected),
                reserveModelCall(), noOpPromptIo(), "acceptance-correction", Instant.now()).status())
                .isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        when(designerRuntimeControl.abort(anyString(), anyString()))
                .thenReturn(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED);

        DesignerTerminationService.Result result = designerTermination.stop("s", false);

        assertThat(result.complete()).isTrue();
        assertThat(jdbc.queryForMap("SELECT state,termination_proof FROM ai_candidate_prompt_dispatch "
                + "WHERE run_id=?", run.runId()))
                .containsEntry("state", "STOPPED")
                .containsEntry("termination_proof", "ABORT_ACKNOWLEDGED");
        assertThat(jdbc.queryForMap("SELECT state,close_reason FROM ai_candidate_submission_run WHERE id=?",
                run.runId()))
                .containsEntry("state", "CLOSED")
                .containsEntry("close_reason", "OWNER_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT state FROM designer_session WHERE id='s'", String.class))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("SELECT event FROM state_transition_event "
                + "WHERE machine_type='CANDIDATE_PROMPT_DISPATCH' ORDER BY sequence", String.class))
                .containsExactly("CREATED", "COMPLETE", "ABORT", "COMPLETE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task", Integer.class)).isZero();
    }

    @Test
    void handedOffLegacyProofSurvivesRunCloseCasFailureAndRetryDoesNotAbortAgain() {
        jdbc.update("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,"
                + "ownership_mode,endpoint_fingerprint,internal_mcp_server,created_at) "
                + "VALUES('remote-legacy','generation-legacy','MANAGED',?,'mcp','now')", "b".repeat(64));
        MachineCandidateSubmission.RunSnapshot run = openAndMarkHandedOffLegacyRun("run-handoff-cancel");
        jdbc.execute("CREATE TRIGGER fail_candidate_run_close BEFORE UPDATE ON ai_candidate_submission_run "
                + "WHEN OLD.id='run-handoff-cancel' AND NEW.state='CLOSED' "
                + "BEGIN SELECT RAISE(ABORT,'simulated close CAS failure'); END");
        when(designerRuntimeControl.abort(anyString(), anyString()))
                .thenReturn(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED);

        DesignerTerminationService.Result first = designerTermination.stop("s", false);

        assertThat(first.stopStatus()).isEqualTo("STOPPING");
        assertThat(first.failedSessions()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT state,legacy_termination_proof "
                + "FROM acceptance_candidate_legacy_handoff WHERE id='handoff-cancel'"))
                .containsEntry("state", "CANCELLED")
                .containsEntry("legacy_termination_proof", "ABORT_ACKNOWLEDGED");
        assertThat(submissions.find(run.runId())).get().extracting(MachineCandidateSubmission.RunSnapshot::state)
                .isEqualTo(MachineCandidateRunState.OPEN);
        assertThat(jdbc.queryForObject("SELECT state FROM designer_session WHERE id='s'", String.class))
                .isEqualTo("STOPPING");

        jdbc.execute("DROP TRIGGER fail_candidate_run_close");
        DesignerTerminationService.Result retried = designerTermination.stop("s", false);

        assertThat(retried.complete()).isTrue();
        assertThat(submissions.find(run.runId())).get().satisfies(closed -> {
            assertThat(closed.state()).isEqualTo(MachineCandidateRunState.CLOSED);
            assertThat(closed.closeReason()).isEqualTo(
                    MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
        });
        verify(designerRuntimeControl, times(1)).abort("remote-legacy", "p");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task", Integer.class)).isZero();
    }

    @Test
    void ambiguousHandoffCleanupPersistsStoppedLifecycleEvent() {
        jdbc.update("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,"
                + "ownership_mode,endpoint_fingerprint,internal_mcp_server,created_at) "
                + "VALUES('cleanup-remote','cleanup-generation','MANAGED',?,'mcp','now')", "9".repeat(64));
        alignHandoffSourceAnchors();
        assertThat(mapper.insertAcceptanceCandidateLegacyHandoff(handedOffHandoff())).isEqualTo(1);
        jdbc.update("UPDATE acceptance_candidate_legacy_handoff SET state='STOPPING_LEGACY' "
                + "WHERE id='handoff-cancel'");
        handoffCleanupLedger.register("handoff-cancel", List.of(
                new AcceptanceCandidateLegacyHandoffService.RemoteIdentity(
                        "cleanup-remote", "cleanup-generation", "9".repeat(64),
                        "8".repeat(64), "7".repeat(64))));

        var claim = handoffCleanupLedger.claimStop("handoff-cancel", "cleanup-remote", "worker-1",
                Instant.parse("2026-09-01T00:00:00Z"), Duration.ofSeconds(30));
        assertThat(claim.acquired()).isTrue();
        assertThat(handoffCleanupLedger.stopped(
                "handoff-cancel", "cleanup-remote", claim, "ABORT_ACKNOWLEDGED"))
                .satisfies(cleanup -> {
                    assertThat(cleanup.state()).isEqualTo("STOPPED");
                    assertThat(cleanup.terminationProof()).isEqualTo("ABORT_ACKNOWLEDGED");
                });
        assertThat(jdbc.queryForList("SELECT event FROM state_transition_event "
                + "WHERE machine_type='ACCEPTANCE_CANDIDATE_HANDOFF_CLEANUP' "
                + "AND entity_id='handoff-cancel:cleanup-remote' ORDER BY sequence", String.class))
                .containsExactly("CREATED", "ABORT", "COMPLETE");
    }

    @Test
    void cleanupStopClaimFencesConcurrentAbortAndOnlyExpiresIntoOneCrashTakeover() throws Exception {
        jdbc.update("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,"
                + "ownership_mode,endpoint_fingerprint,internal_mcp_server,created_at) "
                + "VALUES('cleanup-remote','cleanup-generation','MANAGED',?,'mcp','now')", "9".repeat(64));
        alignHandoffSourceAnchors();
        assertThat(mapper.insertAcceptanceCandidateLegacyHandoff(handedOffHandoff())).isEqualTo(1);
        jdbc.update("UPDATE acceptance_candidate_legacy_handoff SET state='STOPPING_LEGACY' "
                + "WHERE id='handoff-cancel'");
        handoffCleanupLedger.register("handoff-cancel", List.of(
                new AcceptanceCandidateLegacyHandoffService.RemoteIdentity(
                        "cleanup-remote", "cleanup-generation", "9".repeat(64),
                        "8".repeat(64), "7".repeat(64))));

        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger aborts = new AtomicInteger();
        CompletableFuture<AcceptanceCandidateHandoffCleanupLedger.StopClaim> first = CompletableFuture.supplyAsync(
                () -> claimAfter(ready, start, "worker-1", now, aborts));
        CompletableFuture<AcceptanceCandidateHandoffCleanupLedger.StopClaim> second = CompletableFuture.supplyAsync(
                () -> claimAfter(ready, start, "worker-2", now, aborts));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        var firstClaim = first.get(5, TimeUnit.SECONDS);
        var secondClaim = second.get(5, TimeUnit.SECONDS);
        var winner = firstClaim.acquired() ? firstClaim : secondClaim;

        assertThat(List.of(firstClaim.acquired(), secondClaim.acquired())).containsExactlyInAnyOrder(true, false);
        assertThat(aborts).hasValue(1);
        assertThat(handoffCleanupLedger.claimStop("handoff-cancel", "cleanup-remote", "worker-3",
                now.plusSeconds(29), Duration.ofSeconds(30)).acquired()).isFalse();
        var takeover = handoffCleanupLedger.claimStop("handoff-cancel", "cleanup-remote", "worker-3",
                now.plusSeconds(31), Duration.ofSeconds(30));
        assertThat(takeover.acquired()).isTrue();
        assertThat(takeover.fence()).isEqualTo(winner.fence() + 1);
        assertThatThrownBy(() -> handoffCleanupLedger.stopped(
                "handoff-cancel", "cleanup-remote", winner, "ABORT_ACKNOWLEDGED"))
                .isInstanceOf(ConflictException.class);
        assertThat(handoffCleanupLedger.disconnected(
                "handoff-cancel", "cleanup-remote", takeover, "STOP_UNCERTAIN", "lost reply").state())
                .isEqualTo("DISCONNECTED");
    }

    @Test
    void proofedLegacyHandoffSettlesIdempotentlyAndAllowsDesignerAndTaskCompletion() {
        MachineCandidateSubmission.RunSnapshot run = acceptedHandedOffRun("run-handoff-settle");
        assertThat(mapper.listActiveAcceptanceCandidateLegacyHandoffs())
                .extracting(AcceptanceCandidateLegacyHandoffRow::state).containsExactly("HANDED_OFF");

        assertThat(acceptanceCandidateProofs.persistIfOwned(run, "ABORT_ACKNOWLEDGED")).isPresent();
        Map<String, Object> settled = jdbc.queryForMap("SELECT state,current_owner_version,"
                + "legacy_external_state,legacy_termination_proof,version "
                + "FROM acceptance_candidate_legacy_handoff WHERE id='handoff-cancel'");
        assertThat(settled)
                .containsEntry("state", "SETTLED")
                .containsEntry("current_owner_version", 3)
                .containsEntry("legacy_external_state", "ABORT_ACKNOWLEDGED")
                .containsEntry("legacy_termination_proof", "ABORT_ACKNOWLEDGED")
                .containsEntry("version", 1);
        assertThat(mapper.listActiveAcceptanceCandidateLegacyHandoffs()).isEmpty();

        assertThat(acceptanceCandidateProofs.persistIfOwned(run, "ABORT_ACKNOWLEDGED")).isPresent();
        assertThat(jdbc.queryForObject("SELECT version FROM acceptance_candidate_legacy_handoff "
                + "WHERE id='handoff-cancel'", Integer.class)).isEqualTo(1);

        TaskRow awaiting = bindTaskForCompletion();
        TaskRow completed = taskTerminals.complete(awaiting, LifecycleEvent.COMPLETE, Map.of("proof", "test"));

        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT state FROM designer_session WHERE id='s'", String.class))
                .isEqualTo("COMPLETED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COMPILATION_WORK_PACKAGE", "COMPILATION_SOURCE_MESSAGE",
            "COMPILATION_DRAFT", "WORK_PACKAGE_SOURCE_MESSAGE"
    })
    void handoffSettlementRejectsEveryDriftedOwnerAndSourceAnchor(String drift) {
        MachineCandidateSubmission.RunSnapshot run = acceptedHandedOffRun("run-handoff-anchor-drift");
        if (drift.contains("SOURCE_MESSAGE")) {
            jdbc.update("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,"
                    + "delivery_state,created_at) VALUES('m-other','s',2,'ASSISTANT','other',"
                    + "'PERSISTED','now')");
        }
        switch (drift) {
            case "COMPILATION_WORK_PACKAGE" -> jdbc.update(
                    "UPDATE loop_spec_compilation SET work_package_id='WP-OTHER' WHERE id='cmp'");
            case "COMPILATION_SOURCE_MESSAGE" -> jdbc.update(
                    "UPDATE loop_spec_compilation SET source_design_message_id='m-other' WHERE id='cmp'");
            case "COMPILATION_DRAFT" -> jdbc.update(
                    "UPDATE loop_spec_compilation SET source_draft_version=9 WHERE id='cmp'");
            case "WORK_PACKAGE_SOURCE_MESSAGE" -> jdbc.update(
                    "UPDATE design_work_package SET design_message_id='m-other' WHERE id='wp'");
            default -> throw new IllegalArgumentException(drift);
        }

        assertThat(acceptanceCandidateProofs.persistIfOwned(run, "ABORT_ACKNOWLEDGED")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT external_session_state FROM loop_spec_compilation WHERE id='cmp'", String.class))
                .isEqualTo("PROMPTED");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM acceptance_candidate_legacy_handoff WHERE id='handoff-cancel'", String.class))
                .isEqualTo("HANDED_OFF");
    }

    @Test
    void rawHandoffSettleCasAlsoRejectsDriftedCompilationBinding() {
        MachineCandidateSubmission.RunSnapshot run = acceptedHandedOffRun("run-handoff-raw-cas");
        AcceptanceCandidateLegacyHandoffRow handoff = mapper
                .findAcceptanceCandidateLegacyHandoff("handoff-cancel").orElseThrow();
        AcceptanceCandidateLegacyHandoffRow settled = AcceptanceCandidateLegacyHandoffRows.copy(handoff,
                "SETTLED", 3, handoff.oldExternalState(), handoff.oldTerminationProof(), handoff.oldProofAt(),
                handoff.legacyExternalSessionId(), handoff.legacyRuntimeGenerationId(),
                handoff.legacyEndpointFingerprint(), "ABORT_ACKNOWLEDGED", "ABORT_ACKNOWLEDGED", "proof-at",
                handoff.legacyPromptSha256(), handoff.modelCallConsumed(), handoff.modelCallConsumedAt(),
                null, null, null);
        jdbc.update("UPDATE loop_spec_compilation SET work_package_id='WP-OTHER' WHERE id='cmp'");

        assertThat(mapper.settleAcceptanceCandidateLegacyHandoff(settled, run.ownerVersion())).isZero();
    }

    @Test
    void handoffSettleWriteFailureRollsBackCompilationProofAndBlocksTheParentTerminal() {
        MachineCandidateSubmission.RunSnapshot run = acceptedHandedOffRun("run-handoff-settle-failure");
        jdbc.execute("CREATE TRIGGER fail_handoff_settle BEFORE UPDATE ON acceptance_candidate_legacy_handoff "
                + "WHEN OLD.id='handoff-cancel' AND NEW.state='SETTLED' "
                + "BEGIN SELECT RAISE(ABORT,'simulated handoff settle failure'); END");

        assertThatThrownBy(() -> acceptanceCandidateProofs.persistIfOwned(run, "ABORT_ACKNOWLEDGED"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForMap("SELECT external_session_state,version FROM loop_spec_compilation "
                + "WHERE id='cmp'"))
                .containsEntry("external_session_state", "PROMPTED")
                .containsEntry("version", 2);
        assertThat(jdbc.queryForMap("SELECT state,legacy_termination_proof FROM "
                + "acceptance_candidate_legacy_handoff WHERE id='handoff-cancel'"))
                .containsEntry("state", "HANDED_OFF")
                .containsEntry("legacy_termination_proof", null);

        TaskRow awaiting = bindTaskForCompletion();
        assertThatThrownBy(() -> taskTerminals.complete(awaiting, LifecycleEvent.COMPLETE, Map.of()))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("DESIGNER_CANDIDATE_WRITER_STILL_ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id='task-terminal'", String.class))
                .isEqualTo("AWAITING_DECISION");
    }

    @Test
    void cancellationScanThenConcurrentCorrectionReserveIsRejectedByDesignerScopeGuard() {
        submissions.open(decomposerRun("run-cancel-reserve-race", 5));
        MachineCandidateSubmission.SubmissionResult rejected = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-cancel-reserve-race", "attempt-1", INVALID, 0, LEGACY));
        MachineCandidateSubmission.RunSnapshot run = submissions.find(rejected.runId()).orElseThrow();
        assertThat(promptDispatches.prepareDesignerCancellation("s", Instant.now())).isTrue();
        jdbc.update("UPDATE designer_session SET state='STOPPING',version=version+1 WHERE id='s'");

        assertThatThrownBy(() -> promptDispatches.advance(run, rejected, remote(),
                correctionRequest(run, rejected), reserveModelCall(), noOpPromptIo(),
                "acceptance-correction", Instant.now()))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_SCOPE_NOT_WRITABLE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_prompt_dispatch", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT model_calls_used FROM design_requirement_revision WHERE id='r'",
                Integer.class)).isZero();
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
    void packageDesignBudgetExhaustionTransitionsToFallbackRequired() {
        MachineCandidateSubmission.RunSnapshot opened = submissions.open(packageRun("run-package", 3));

        assertThat(opened.owner()).isEqualTo(
                MachineCandidateSubmission.CandidateOwnerRef.designWorkPackage("wp"));
        assertThat(MachineCandidateKind.PACKAGE_DESIGN_V1.maximumAttempts()).isEqualTo(3);
        assertThat(submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-package", "attempt-1", "{\"fallbackEligible\":true}", 0, LEGACY)).outcome())
                .isEqualTo(MachineCandidateOutcome.REJECTED);
        assertThat(submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-package", "attempt-2", "{\"fallbackEligible\":true}", 1, LEGACY)).outcome())
                .isEqualTo(MachineCandidateOutcome.REJECTED);
        MachineCandidateSubmission.SubmissionResult terminal = submissions.submit(
                new MachineCandidateSubmission.SubmitCommand(
                        "run-package", "attempt-3", "{\"fallbackEligible\":true}", 2, LEGACY));

        assertThat(terminal.outcome()).isEqualTo(MachineCandidateOutcome.FALLBACK_REQUIRED);
        assertThat(terminal.runState()).isEqualTo(MachineCandidateRunState.FALLBACK_REQUIRED);
        assertThat(terminal.remainingAttempts()).isZero();
        assertThat(terminal.retryable()).isFalse();
        assertThat(terminal.canonicalResultSha256()).isNull();
        assertThat(submissions.terminal("run-package")).contains(terminal);
        assertThat(jdbc.queryForList("SELECT event FROM state_transition_event "
                + "WHERE machine_type='CANDIDATE_SUBMISSION_RUN' AND entity_id='run-package' ORDER BY sequence",
                String.class)).containsExactly("CREATED", "REQUIRE_FALLBACK");
    }

    @Test
    void fallbackEligibilityIsRejectedForExistingCandidateKinds() {
        submissions.open(decomposerRun("run-invalid-fallback", 1));

        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-invalid-fallback", "attempt-1", "{\"fallbackEligible\":true}", 0, LEGACY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fallback eligibility is restricted to package-design candidates");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                + "WHERE run_id='run-invalid-fallback'", Integer.class)).isZero();
    }

    @Test
    void fallbackEligibilityCannotTurnANonRetryablePackageProblemIntoMarkdownFallback() {
        submissions.open(packageRun("run-invalid-package-fallback", 3));

        assertThatThrownBy(() -> submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run-invalid-package-fallback", "attempt-1", "{\"nonRetryableFallback\":true}", 0, LEGACY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fallback eligibility requires a retryable package-design rejection");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_submission_attempt "
                + "WHERE run_id='run-invalid-package-fallback'", Integer.class)).isZero();
    }

    @Test
    void candidateScopeAndOwnerAreTypedStableReferences() {
        assertThat(MachineCandidateSubmission.CandidateScope.designerSession("s"))
                .extracting(MachineCandidateSubmission.CandidateScope::type,
                        MachineCandidateSubmission.CandidateScope::id)
                .containsExactly(MachineCandidateSubmission.CandidateScopeType.DESIGNER_SESSION, "s");
        assertThat(MachineCandidateSubmission.CandidateScope.task("task").type())
                .isEqualTo(MachineCandidateSubmission.CandidateScopeType.TASK);
        assertThat(MachineCandidateSubmission.CandidateScope.project("project").type())
                .isEqualTo(MachineCandidateSubmission.CandidateScopeType.PROJECT);
        assertThat(MachineCandidateSubmission.CandidateOwnerRef.designWorkPackage("wp"))
                .extracting(MachineCandidateSubmission.CandidateOwnerRef::type,
                        MachineCandidateSubmission.CandidateOwnerRef::id)
                .containsExactly(MachineCandidateSubmission.CandidateOwnerType.DESIGN_WORK_PACKAGE, "wp");
        assertThatThrownBy(() -> MachineCandidateSubmission.CandidateScope.task(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MachineCandidateSubmission.CandidateOwnerRef.judgeRun(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservedCandidateKindsExposeBudgetsButFailClosedUntilTheirAdaptersExist() {
        assertThat(MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1.maximumAttempts()).isEqualTo(3);
        assertThat(MachineCandidateKind.REVIEWER_REPORT_V1.maximumAttempts()).isEqualTo(3);
        assertThat(MachineCandidateKind.PROJECT_CONVENTION_V1.maximumAttempts()).isEqualTo(3);
        assertThat(MachineCandidateKind.JUDGE_DECISION_V1.maximumAttempts()).isEqualTo(2);

        List<MachineCandidateSubmission.OpenCommand> commands = List.of(
                futureRun("future-rolling", MachineCandidateSubmission.CandidateScope.task("task"),
                        MachineCandidateSubmission.CandidateOwnerRef.taskPackagePlanRevision("plan"),
                        MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1, 3),
                futureRun("future-reviewer", MachineCandidateSubmission.CandidateScope.designerSession("s"),
                        MachineCandidateSubmission.CandidateOwnerRef.analysisReport("report"),
                        MachineCandidateKind.REVIEWER_REPORT_V1, 3),
                futureRun("future-convention", MachineCandidateSubmission.CandidateScope.project("p"),
                        MachineCandidateSubmission.CandidateOwnerRef.projectConventionDraft("convention"),
                        MachineCandidateKind.PROJECT_CONVENTION_V1, 3),
                futureRun("future-judge", MachineCandidateSubmission.CandidateScope.task("task"),
                        MachineCandidateSubmission.CandidateOwnerRef.judgeRun("judge"),
                        MachineCandidateKind.JUDGE_DECISION_V1, 2));

        for (MachineCandidateSubmission.OpenCommand command : commands) {
            assertThatThrownBy(() -> submissions.open(command))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_KIND_NOT_INTEGRATED"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_candidate_submission_run WHERE id LIKE 'future-%'",
                Integer.class)).isZero();
    }

    @Test
    void packageAcceptedResultSupportsRecoveryReadsAndConcurrentSingleWinnerSettlement() throws Exception {
        submissions.open(packageRun("accepted-package-run", 3));
        PackageDesignAcceptedResultRow row = new PackageDesignAcceptedResultRow(
                "accepted-package-run", "wp", 1, 0, "PACKAGE_DESIGN_V1",
                "{\"design\":\"canonical\"}", "# Canonical package design",
                "{\"compiled\":true}", "d".repeat(64), null,
                "2026-08-31T00:00:00Z", "2026-08-31T00:00:00Z", 0);

        assertThat(mapper.insertPackageDesignAcceptedResult(row)).isEqualTo(1);
        assertThat(mapper.findPackageDesignAcceptedResult("accepted-package-run")).contains(row);
        assertThat(mapper.findLatestPackageDesignAcceptedResultForWorkPackage("wp")).contains(row);
        assertThat(mapper.listUnsettledPackageDesignAcceptedResults()).containsExactly(row);
        assertThat(mapper.settlePackageDesignAcceptedResult(
                "accepted-package-run", 0, null, "2026-08-31T00:00:00Z")).isZero();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> settleAfter(
                ready, start, "2026-08-31T00:00:01Z"));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> settleAfter(
                ready, start, "2026-08-31T00:00:02Z"));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(0, 1);
        assertThat(mapper.findPackageDesignAcceptedResult("accepted-package-run")).get().satisfies(settled -> {
            assertThat(settled.settledCompilationId()).isEqualTo("cmp");
            assertThat(settled.version()).isEqualTo(1);
            assertThat(settled.updatedAt()).isIn("2026-08-31T00:00:01Z", "2026-08-31T00:00:02Z");
        });
        assertThat(mapper.listUnsettledPackageDesignAcceptedResults()).isEmpty();
    }

    private int settleAfter(CountDownLatch ready, CountDownLatch start, String updatedAt) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("settlement start timed out");
            return mapper.settlePackageDesignAcceptedResult(
                    "accepted-package-run", 0, "cmp", updatedAt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
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
                "run-v7", MachineCandidateSubmission.CandidateScope.designerSession("s"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("cmp"),
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
                new MachineCandidateSubmission.CloseCommand(open.runId(), open.version(),
                        MachineCandidateSubmission.CandidateCloseReason.TIMEOUT));
        assertThat(closed.state()).isEqualTo(MachineCandidateRunState.CLOSED);
        assertThat(closed.closeReason()).isEqualTo(MachineCandidateSubmission.CandidateCloseReason.TIMEOUT);
        assertThat(submissions.find(open.runId())).get().extracting(
                MachineCandidateSubmission.RunSnapshot::closeReason)
                .isEqualTo(MachineCandidateSubmission.CandidateCloseReason.TIMEOUT);
        assertThat(jdbc.queryForObject("SELECT close_reason FROM ai_candidate_submission_run WHERE id='run-close'",
                String.class)).isEqualTo("TIMEOUT");
        assertThat(submissions.close(new MachineCandidateSubmission.CloseCommand(
                closed.runId(), closed.version(), MachineCandidateSubmission.CandidateCloseReason.TIMEOUT)))
                .isEqualTo(closed);
        assertThatThrownBy(() -> submissions.close(new MachineCandidateSubmission.CloseCommand(
                closed.runId(), closed.version(), MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_CLOSE_REASON_CONFLICT"));
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
        when(decompositionCandidatePolicy.evaluate(any(CandidatePolicy.Context.class), anyString()))
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
                id, MachineCandidateSubmission.CandidateScope.designerSession("s"),
                MachineCandidateSubmission.CandidateOwnerRef.taskDecomposition("dec"),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, "PLANNING", 1, 0,
                LEGACY, "DECOMPOSITION_PLAN_V2", "generation-1", "remote-1", maxAttempts);
    }

    private MachineCandidateSubmission.OpenCommand acceptanceInitialRun(String id) {
        return new MachineCandidateSubmission.OpenCommand(
                id, MachineCandidateSubmission.CandidateScope.designerSession("s"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("cmp"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP, 1, 5,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                AcceptanceClosedChoiceCandidateCoordinator.CONTRACT_VERSION,
                "generation-1", "remote-1", 2);
    }

    private OpenCodeClient.PromptRequest correctionRequest(MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.SubmissionResult rejected) {
        return new OpenCodeClient.PromptRequest("repair the rejected candidate", null, null,
                new OpenCodeClient.ResponseFormat.Text(),
                CandidatePromptDispatchService.messageId(run.runId(), rejected.attemptOrdinal()), List.of());
    }

    private void prepareInternalLaunch(String runId) {
        jdbc.update("UPDATE design_work_package SET design_revision=1,design_message_id='m' WHERE id='wp'");
        jdbc.update("UPDATE loop_spec_compilation SET state='PENDING_HANDOFF',external_session_id=NULL,"
                + "external_session_state=NULL,work_package_id='WP-1',version=4 WHERE id='cmp'");
        jdbc.update("UPDATE open_code_session_runtime_binding "
                + "SET internal_mcp_server='loopper_internal_generation_1' WHERE external_session_id='remote-1'");
        jdbc.update("INSERT INTO design_acceptance_planning(compilation_id,designer_session_id,work_package_id,"
                + "design_revision,contract_version,design_sha256,state,facts_json,capabilities_json,binding_json,"
                + "diagnostics_json,created_at,updated_at,version,binding_source) VALUES('cmp','s','WP-1',1,"
                + "'DESIGN_ACCEPTANCE_V7',?,'EXTRACTED','[]','[]','{\"selection\":1}','[]','now','now',3,"
                + "'AI_DISAMBIGUATION_V6')", "d".repeat(64));
        String launchId = "launch-" + runId;
        mapper.insertAcceptanceCandidateInternalLaunch(new AcceptanceCandidateInternalLaunchRow(
                launchId, "cmp", "s", "WP-1", 1, "m", 0, "d".repeat(64),
                3, "AI_DISAMBIGUATION_V6", "{\"selection\":1}", "e".repeat(64),
                "{\"candidates\":[0,1]}", "f".repeat(64), runId,
                "ACCEPTANCE_CLOSED_CHOICE_V7", "ACCEPTANCE_CLOSED_CHOICE_V7", "PREPARED",
                4, null, null, "Acceptance internal " + runId, "/tmp/p", "generation-1",
                true, "loopper_internal_generation_1", "a".repeat(64), null, null, null,
                "ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS", "[]", "1".repeat(64), "2".repeat(64),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        java.util.Arrays.copyOf(runId.getBytes(java.nio.charset.StandardCharsets.UTF_8), 32)),
                "LOCAL_REQUEST_ATTESTED", null, null, null, 0, false, null,
                null, null, null, null, null, null, null, "now", "now", 0));
        jdbc.update("UPDATE acceptance_candidate_internal_launch SET state='CREATED',"
                + "create_claim_owner='worker',create_claim_token='claim',"
                + "create_claim_expires_at='2099-01-01T00:00:00Z',create_fence=1,"
                + "create_dispatch_attempted=1,create_dispatch_started_at='dispatch-at',"
                + "external_session_id='remote-1',external_attested_at='attested-at',version=version+1 "
                + "WHERE id=?", launchId);
    }

    private OpenCodeClient.OpenCodeSession remote() {
        return new OpenCodeClient.OpenCodeSession("remote-1", Path.of("/tmp/p"));
    }

    private AcceptanceCandidateLegacyHandoffRow handedOffHandoff() {
        String now = Instant.now().toString();
        return new AcceptanceCandidateLegacyHandoffRow(
                "handoff-cancel", "cmp", "s", "WP-1", 1, "m", 0, "a".repeat(64),
                "ACCEPTANCE_CLOSED_CHOICE_V7", "HANDED_OFF", 1, 1,
                null, null, null, "NOT_STARTED", null, null,
                "acceptance-v7-legacy-session:cancel", "legacy [loopper-create:" + "c".repeat(43) + "]",
                "/tmp/p", "generation-legacy", true, "mcp", "b".repeat(64),
                null, null, null, "COMPILER_BINDING_NO_TOOLS", "[]", "d".repeat(64),
                "e".repeat(64), "c".repeat(43), "LOCAL_REQUEST_ATTESTED",
                null, null, null, 1, true, now,
                "remote-legacy", "generation-legacy", "b".repeat(64), "PROMPTED", null, null,
                "legacy-prompt-cancel", "f".repeat(64), true, now,
                null, null, null, 1, true, now, null, null, null, now, now, 0);
    }

    private AcceptanceCandidateLegacyHandoffRow legacyCreatedHandoff() {
        String now = Instant.now().toString();
        return new AcceptanceCandidateLegacyHandoffRow(
                "handoff-cancel", "cmp", "s", "WP-1", 1, "m", 0, "a".repeat(64),
                "ACCEPTANCE_CLOSED_CHOICE_V7", "LEGACY_CREATED", 0, 0,
                null, null, null, "NOT_STARTED", null, null,
                "acceptance-v7-legacy-session:cancel", "legacy [loopper-create:" + "c".repeat(43) + "]",
                "/tmp/p", "generation-legacy", true, "mcp", "b".repeat(64),
                null, null, null, "COMPILER_BINDING_NO_TOOLS", "[]", "d".repeat(64),
                "e".repeat(64), "c".repeat(43), "LOCAL_REQUEST_ATTESTED",
                null, null, null, 1, true, now,
                "remote-legacy", "generation-legacy", "b".repeat(64), "CREATED", null, null,
                "legacy-prompt-cancel", null, false, null,
                null, null, null, 0, false, null, null, null, null, now, now, 0);
    }

    private MachineCandidateSubmission.RunSnapshot openAndMarkHandedOffLegacyRun(String runId) {
        alignHandoffSourceAnchors();
        jdbc.update("UPDATE loop_spec_compilation SET state='RUNNING',external_session_id='remote-legacy',"
                + "external_session_state='CANDIDATE_LEGACY_RUNNING',version=1 WHERE id='cmp'");
        assertThat(mapper.insertAcceptanceCandidateLegacyHandoff(legacyCreatedHandoff())).isEqualTo(1);
        MachineCandidateSubmission.RunSnapshot run = submissions.open(new MachineCandidateSubmission.OpenCommand(
                runId, MachineCandidateSubmission.CandidateScope.designerSession("s"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("cmp"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP, 1, 1,
                LEGACY, "ACCEPTANCE_CLOSED_CHOICE_V7", "generation-legacy", "remote-legacy", 2));
        String now = Instant.now().toString();
        jdbc.update("UPDATE acceptance_candidate_legacy_handoff SET state='HANDED_OFF',"
                + "current_owner_version=1,legacy_external_state='PROMPTED',legacy_prompt_sha256=?,"
                + "legacy_prompt_dispatch_attempted=1,legacy_prompt_dispatch_started_at=?,"
                + "model_call_consumed=1,model_call_consumed_at=? WHERE id='handoff-cancel'",
                "f".repeat(64), now, now);
        return run;
    }

    private MachineCandidateSubmission.RunSnapshot acceptedHandedOffRun(String runId) {
        jdbc.update("INSERT INTO open_code_session_runtime_binding(external_session_id,runtime_generation_id,"
                + "ownership_mode,endpoint_fingerprint,internal_mcp_server,created_at) "
                + "VALUES('remote-legacy','generation-legacy','MANAGED',?,'mcp','now')", "b".repeat(64));
        openAndMarkHandedOffLegacyRun(runId);
        jdbc.update("UPDATE loop_spec_compilation SET external_session_state='PROMPTED',version=2 WHERE id='cmp'");
        jdbc.update("UPDATE ai_candidate_submission_run SET state='ACCEPTED',attempts_used=1,"
                + "terminal_attempt_id='accepted-attempt',version=1 WHERE id=?", runId);
        return submissions.find(runId).orElseThrow();
    }

    private void alignHandoffSourceAnchors() {
        jdbc.update("UPDATE design_work_package SET design_revision=1,design_message_id='m' WHERE id='wp'");
        jdbc.update("UPDATE loop_spec_compilation SET work_package_id='WP-1' WHERE id='cmp'");
    }

    private TaskRow bindTaskForCompletion() {
        jdbc.update("INSERT INTO task(id,project_id,loop_draft_id,title,state,created_at,updated_at) "
                + "VALUES('task-terminal','p','d','Task','AWAITING_DECISION','now','now')");
        jdbc.update("UPDATE designer_session SET task_id='task-terminal',state='REVIEWING',"
                + "external_session_id=NULL,external_session_state=NULL,version=version+1 WHERE id='s'");
        jdbc.update("UPDATE design_work_package SET state='APPROVED',designer_external_session_id=NULL,"
                + "designer_external_session_state=NULL WHERE id='wp'");
        return mapper.findTask("task-terminal").orElseThrow();
    }

    private CandidatePromptDispatchService.BudgetReservation reserveModelCall() {
        return () -> jdbc.update("UPDATE design_requirement_revision SET model_calls_used=model_calls_used+1 "
                + "WHERE id='r' AND model_calls_used<max_model_calls") == 1;
    }

    private CandidatePromptDispatchService.PromptIo noOpPromptIo() {
        java.util.concurrent.atomic.AtomicBoolean posted = new java.util.concurrent.atomic.AtomicBoolean();
        return new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest request, String sha256) {
                return posted.get() ? new OpenCodeClient.MessageLookup(true, true, sha256)
                        : new OpenCodeClient.MessageLookup(true, false);
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest request) { posted.set(true); }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private MachineCandidateSubmission.OpenCommand packageRun(String id, int maxAttempts) {
        return new MachineCandidateSubmission.OpenCommand(
                id, MachineCandidateSubmission.CandidateScope.designerSession("s"),
                MachineCandidateSubmission.CandidateOwnerRef.designWorkPackage("wp"),
                MachineCandidateKind.PACKAGE_DESIGN_V1, "PACKAGE_DESIGN", 1, 0,
                LEGACY, "PACKAGE_DESIGN_V1", "generation-1", "remote-1", maxAttempts);
    }

    private MachineCandidateSubmission.OpenCommand futureRun(
            String id, MachineCandidateSubmission.CandidateScope scope,
            MachineCandidateSubmission.CandidateOwnerRef owner, MachineCandidateKind kind, int maxAttempts) {
        return new MachineCandidateSubmission.OpenCommand(
                id, scope, owner, kind, kind.name(), 1, 0, LEGACY, kind.name(),
                "generation-1", "remote-1", maxAttempts);
    }

    private void configureCandidateAdapters() {
        when(decompositionCandidatePolicy.supports(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == MachineCandidateKind.DECOMPOSITION_PLAN_V2);
        when(decompositionCandidatePolicy.evaluate(any(CandidatePolicy.Context.class), anyString()))
                .thenAnswer(invocation -> evaluateCandidate(invocation.getArgument(1)));
        when(packageDesignCandidatePolicy.supports(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == MachineCandidateKind.PACKAGE_DESIGN_V1);
        when(packageDesignCandidatePolicy.evaluate(any(CandidatePolicy.Context.class), anyString()))
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
                    context.owner().id(), context.ownerVersion())).isEqualTo(1);
            return null;
        }).when(acceptedCandidateWriter).write(any(CandidatePolicy.Context.class), anyString(), anyString());
    }

    private CandidatePolicy.Decision evaluateCandidate(String candidateJson) {
        if (candidateJson.contains("nonRetryableFallback")) {
            return CandidatePolicy.Decision.rejected(false, true, List.of(
                    new MachineCandidateSubmission.Problem(
                            "PACKAGE_DESIGN_INPUT_REQUIRED", "/design", "Package design requires user input")));
        }
        if (candidateJson.contains("fallbackEligible")) {
            return CandidatePolicy.Decision.rejected(true, true, List.of(
                    new MachineCandidateSubmission.Problem(
                            "PACKAGE_DESIGN_INVALID", "/design", "Package design candidate is invalid")));
        }
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

    private AcceptanceCandidateHandoffCleanupLedger.StopClaim claimAfter(
            CountDownLatch ready, CountDownLatch start, String claimant, Instant now, AtomicInteger aborts) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("claim start timed out");
            var claim = handoffCleanupLedger.claimStop(
                    "handoff-cancel", "cleanup-remote", claimant, now, Duration.ofSeconds(30));
            if (claim.acquired()) aborts.incrementAndGet();
            return claim;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
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
        jdbc.update("INSERT INTO design_work_package(id,designer_session_id,requirement_revision_id,decomposition_id,"
                + "package_id,ordinal,title,objective,scope_in_json,scope_out_json,dependencies_json,deliverables_json,"
                + "acceptance_intent_json,requirement_refs_json,state,designer_external_session_id,"
                + "designer_external_session_state,design_revision,created_at,updated_at) "
                + "VALUES('wp','s','r','dec','WP-1',0,'Package','Deliver','[]','[]','[]','[]','[]','[]',"
                + "'DESIGNING','remote-1','RUNNING',0,'now','now')");
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
                if (run.scope().type() == MachineCandidateSubmission.CandidateScopeType.DESIGNER_SESSION) {
                    String sessionState = jdbc.queryForObject(
                            "SELECT state FROM designer_session WHERE id=?", String.class, run.scope().id());
                    if (!"RUNNING".equals(sessionState)) {
                        throw new ConflictException("CANDIDATE_SCOPE_NOT_WRITABLE",
                                "Designer session is stopping or cancelled");
                    }
                }
                Integer binding = jdbc.queryForObject("SELECT COUNT(*) FROM open_code_session_runtime_binding "
                        + "WHERE external_session_id=? AND runtime_generation_id=?", Integer.class,
                        run.externalSessionId(), run.runtimeGenerationId());
                if (binding == null || binding != 1) {
                    throw new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE", "运行时代际绑定已经变化");
                }
                if (run.candidateKind() == MachineCandidateKind.DECOMPOSITION_PLAN_V2) {
                    Long ownerRevision = jdbc.queryForObject(
                            "SELECT version FROM task_decomposition WHERE id=?", Long.class,
                            run.owner().id());
                    if (ownerRevision == null || ownerRevision != run.ownerVersion()) {
                        throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE", "候选拥有者修订已经变化");
                    }
                    Long sourceRevision = jdbc.queryForObject("SELECT r.revision FROM task_decomposition d "
                                    + "JOIN design_requirement_revision r ON r.id=d.requirement_revision_id "
                                    + "WHERE d.id=?", Long.class, run.owner().id());
                    if (sourceRevision == null || sourceRevision != run.sourceRevision()) {
                        throw new ConflictException("CANDIDATE_SOURCE_REVISION_STALE", "候选来源修订已经变化");
                    }
                }
            };
        }
    }
}
